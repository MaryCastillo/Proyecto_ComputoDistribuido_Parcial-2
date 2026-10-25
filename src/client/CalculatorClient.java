package client;

import common.CsvStore;
import common.Protocol;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Celula GUI (cliente) de la arquitectura ADS, con ESTADOS DE SOLICITUD,
 * TIMEOUT con REINTENTOS y RECONEXION automatica.
 *
 * - Se conecta a un nodo del Campo de Datos y se registra como GUI. Si el nodo
 *   se cae, reintenta conectarse, vuelve a registrarse y reenvia las solicitudes
 *   que quedaron pendientes.
 * - Cada solicitud tiene un identificador LOGICO estable (reqId) que se conserva
 *   entre reintentos; cada reintento genera un nuevo msgId, pero el reqId permite
 *   asociar la respuesta y reconocer que es la misma operacion logica.
 * - Estados de una solicitud: PENDIENTE, EN_PROCESO, REINTENTANDO, COMPLETADA,
 *   ERROR y TIMEOUT. Si no llega respuesta a tiempo, se reintenta un numero
 *   limitado de veces; agotados los reintentos, la solicitud pasa a TIMEOUT.
 * - Acepta UNA sola respuesta por reqId (ignora duplicadas o tardias) y verifica
 *   el Codigo de Contenido de respuesta.
 *
 * Uso:
 *   java client.CalculatorClient <guiId> [host] [puerto]
 */
public class CalculatorClient {

    /** Tiempo de espera de una respuesta antes de reintentar (configurable). */
    private static final long REQUEST_TIMEOUT_MS = 4_000L;
    /** Numero maximo de reintentos por solicitud. */
    private static final int MAX_RETRIES = 3;
    /** Retardo de reconexion al nodo. */
    private static final long RECONNECT_DELAY_MS = 1_500L;

    // Estados posibles de una solicitud.
    private enum State { PENDIENTE, EN_PROCESO, REINTENTANDO, COMPLETADA, ERROR, TIMEOUT }

    /** Datos de una solicitud en curso (vive hasta completarse o agotar reintentos). */
    private static final class Request {
        final String reqId;        // identificador logico estable
        final String cc;           // Codigo de Contenido de solicitud
        final String a, b, operator, expression;
        volatile String msgId;     // msgId del intento actual (cambia en cada reintento)
        volatile int attempts;     // intentos realizados
        volatile State state;
        final int rowIndex;        // fila en la tabla de la GUI

        Request(String reqId, String cc, String a, String b, String operator,
                String expression, int rowIndex) {
            this.reqId = reqId;
            this.cc = cc;
            this.a = a;
            this.b = b;
            this.operator = operator;
            this.expression = expression;
            this.rowIndex = rowIndex;
            this.attempts = 0;
            this.state = State.PENDIENTE;
        }
    }

    private final String guiId;
    private final String host;
    private final int port;
    private final CsvStore store;

    private volatile String myNode = "?";
    private volatile PrintWriter out;
    private volatile boolean connected = false;

    private final ConcurrentHashMap<String, Request> pending = new ConcurrentHashMap<>();
    private final AtomicInteger requestSeq = new AtomicInteger(0);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    // Componentes de la interfaz.
    private JFrame frame;
    private JTextField operand1Field;
    private JTextField operand2Field;
    private JComboBox<String> operatorBox;
    private JButton sendButton;
    private JLabel statusLabel;
    private DefaultTableModel tableModel;

    public CalculatorClient(String guiId, String host, int port) {
        this.guiId = guiId;
        this.host = host;
        this.port = port;
        this.store = new CsvStore("gui_" + guiId + "_historial.csv",
                "timestamp,tipo,estado,msgId,reqId,cc,guiId,nodo,servidor,expresion,resultado");
    }

    public static void main(String[] args) {
        String guiId = args.length >= 1 ? args[0] : "G1";
        String host = args.length >= 2 ? args[1] : "localhost";
        int port = 6001;
        if (args.length >= 3) {
            try {
                port = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                System.err.println("Puerto invalido, usando 6001.");
            }
        }
        CalculatorClient client = new CalculatorClient(guiId, host, port);
        SwingUtilities.invokeLater(client::buildGui);
        client.startNetworking();
    }

    // ---------------------------------------------------------------------
    // Interfaz grafica
    // ---------------------------------------------------------------------

    private void buildGui() {
        frame = new JFrame("Calculadora ADS - Celula GUI " + guiId);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(760, 500);
        frame.setLayout(new BorderLayout(8, 8));

        JPanel inputPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        inputPanel.setBorder(BorderFactory.createTitledBorder("Nueva operacion"));

        operand1Field = new JTextField(8);
        operand2Field = new JTextField(8);
        operatorBox = new JComboBox<>(new String[]{"+", "-", "*", "/"});
        sendButton = new JButton("Enviar");

        inputPanel.add(new JLabel("Operando 1:"));
        inputPanel.add(operand1Field);
        inputPanel.add(operatorBox);
        inputPanel.add(new JLabel("Operando 2:"));
        inputPanel.add(operand2Field);
        inputPanel.add(sendButton);

        tableModel = new DefaultTableModel(
                new Object[]{"Hora", "Estado", "Codigo", "Request", "Servidor", "Expresion", "Resultado"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable table = new JTable(tableModel);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createTitledBorder(
                "Historial (una fila por solicitud; el estado se actualiza en vivo)"));

        statusLabel = new JLabel("Conectando al nodo " + host + ":" + port + " ...");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        frame.add(inputPanel, BorderLayout.NORTH);
        frame.add(scroll, BorderLayout.CENTER);
        frame.add(statusLabel, BorderLayout.SOUTH);

        sendButton.addActionListener(e -> sendNewRequest());
        operand2Field.addActionListener(e -> sendNewRequest());
        operand1Field.addActionListener(e -> sendNewRequest());

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private void setStatus(String text) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(text));
    }

    /** Agrega una fila y devuelve su indice. Seguro desde el EDT o fuera de el. */
    private int addRow(String hora, String estado, String codigo, String request,
                       String servidor, String expresion, String resultado) {
        final int[] idx = new int[1];
        Runnable r = () -> {
            idx[0] = tableModel.getRowCount();
            tableModel.addRow(new Object[]{hora, estado, codigo, request, servidor, expresion, resultado});
        };
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(r);
            } catch (Exception e) {
                idx[0] = -1;
            }
        }
        return idx[0];
    }

    private void updateRow(int row, String estado, String servidor, String resultado) {
        if (row < 0) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (row < tableModel.getRowCount()) {
                tableModel.setValueAt(estado, row, 1);
                if (servidor != null) {
                    tableModel.setValueAt(servidor, row, 4);
                }
                if (resultado != null) {
                    tableModel.setValueAt(resultado, row, 6);
                }
            }
        });
    }

    // ---------------------------------------------------------------------
    // Red: conexion con reconexion automatica
    // ---------------------------------------------------------------------

    private void startNetworking() {
        Thread t = new Thread(this::connectLoop, "gui-net");
        t.setDaemon(true);
        t.start();
    }

    private void connectLoop() {
        while (true) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 3000);
                PrintWriter w = new PrintWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                this.out = w;
                this.connected = true;

                // Registro como celula GUI.
                w.println(Protocol.hello(Protocol.ROLE_GUI, guiId));
                setStatus("Conectada al nodo " + host + ":" + port + " como GUI " + guiId + " ...");

                // Al (re)conectar, reenviar las solicitudes que siguen pendientes.
                resendPending();

                String line;
                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        handleLine(line);
                    }
                }
                setStatus("Conexion con el nodo perdida. Reintentando...");
            } catch (IOException e) {
                setStatus("Sin conexion con el nodo (" + e.getMessage() + "). Reintentando...");
            }
            this.connected = false;
            this.out = null;
            sleep(RECONNECT_DELAY_MS);
        }
    }

    private void handleLine(String line) {
        Map<String, String> f = Protocol.parse(line);
        String type = Protocol.typeOf(f);
        if (Protocol.WELCOME.equals(type)) {
            myNode = f.getOrDefault("node", "?");
            setStatus("Conectada al nodo " + myNode + " (" + host + ":" + port + ") como GUI " + guiId);
        } else if (Protocol.OP_RES.equals(type)) {
            handleResult(f);
        }
    }

    // ---------------------------------------------------------------------
    // Envio de solicitudes y manejo de respuestas
    // ---------------------------------------------------------------------

    private void sendNewRequest() {
        String op1 = operand1Field.getText().trim();
        String op2 = operand2Field.getText().trim();
        String operator = (String) operatorBox.getSelectedItem();

        // Validacion local de entradas (Incidencia 11: entradas vacias).
        if (op1.isEmpty() || op2.isEmpty()) {
            setStatus("Escribe ambos operandos antes de enviar.");
            return;
        }
        String cc = Protocol.reqCodeForOperator(operator);
        if (cc == null) {
            setStatus("Operador no soportado: " + operator);
            return;
        }

        String reqId = guiId + "-" + requestSeq.incrementAndGet();
        String expression = op1 + " " + operator + " " + op2;
        int row = addRow(CsvStore.now(), State.PENDIENTE.name(), cc, reqId, "-", expression, "(enviando...)");

        Request req = new Request(reqId, cc, op1, op2, operator, expression, row);
        pending.put(reqId, req);

        store.append(CsvStore.now(), "SOLICITUD", State.PENDIENTE.name(), "", reqId, cc,
                guiId, myNode, "", expression, "");

        dispatch(req, false);

        operand1Field.setText("");
        operand2Field.setText("");
        operand1Field.requestFocus();
    }

    /** Envia (o reenvia) una solicitud por la red y programa su control de timeout. */
    private void dispatch(Request req, boolean isRetry) {
        if (req.state == State.COMPLETADA || req.state == State.TIMEOUT || req.state == State.ERROR) {
            return;
        }
        req.attempts++;
        req.msgId = Protocol.newMsgId();     // nuevo msgId por intento; reqId se conserva
        req.state = isRetry ? State.REINTENTANDO : State.EN_PROCESO;

        String msg = Protocol.opRequest(req.msgId, req.reqId, req.cc, guiId, myNode,
                req.a, req.b, Protocol.DEFAULT_TTL);

        boolean sent = send(msg);
        String estado = sent ? req.state.name() : State.PENDIENTE.name();
        updateRow(req.rowIndex, estado + " (intento " + req.attempts + ")", null,
                sent ? "(esperando...)" : "(sin conexion, se reintentara)");
        setStatus((isRetry ? "Reintentando " : "Enviada ") + req.cc + " reqId=" + req.reqId
                + " (intento " + req.attempts + "): " + req.expression);

        // Programar verificacion de timeout para este intento.
        scheduler.schedule(() -> checkTimeout(req), REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /** Si la solicitud sigue sin respuesta al vencer el timeout, reintenta o la marca TIMEOUT. */
    private void checkTimeout(Request req) {
        if (!pending.containsKey(req.reqId) || req.state == State.COMPLETADA) {
            return; // ya se completo
        }
        if (req.attempts < MAX_RETRIES) {
            dispatch(req, true);
        } else {
            req.state = State.TIMEOUT;
            pending.remove(req.reqId);
            updateRow(req.rowIndex, State.TIMEOUT.name(), null, "(sin respuesta tras "
                    + MAX_RETRIES + " intentos)");
            setStatus("Solicitud " + req.reqId + " sin respuesta (TIMEOUT).");
            store.append(CsvStore.now(), "RESULTADO", State.TIMEOUT.name(), "", req.reqId, req.cc,
                    guiId, myNode, "", req.expression, "TIMEOUT");
        }
    }

    private void handleResult(Map<String, String> f) {
        String target = f.getOrDefault("target", "");
        if (!guiId.equals(target)) {
            return; // no es para esta GUI
        }
        String reqId = f.getOrDefault("reqId", "");
        Request req = pending.get(reqId);

        // Aceptar UNA sola respuesta por reqId: si ya no esta pendiente, es
        // duplicada o tardia y se descarta.
        if (req == null) {
            return;
        }
        // Verificar el Codigo de Contenido de respuesta corresponde al de la solicitud.
        String resCc = f.getOrDefault("cc", "");
        String expectedRes = Protocol.resCodeForReqCode(req.cc);
        if (expectedRes != null && !expectedRes.equals(resCc)) {
            return; // respuesta de otro tipo: no corresponde
        }

        // Marcar completada de forma atomica (la primera gana).
        synchronized (req) {
            if (req.state == State.COMPLETADA) {
                return;
            }
            req.state = State.COMPLETADA;
        }
        pending.remove(reqId);

        String msgId = f.getOrDefault("msgId", "");
        String serverId = f.getOrDefault("origin", "");
        String expression = f.getOrDefault("expr", "");
        String result = f.getOrDefault("result", "");

        store.append(CsvStore.now(), "RESPUESTA", State.COMPLETADA.name(), msgId, reqId, resCc,
                guiId, myNode, serverId, expression, result);
        updateRow(req.rowIndex, State.COMPLETADA.name(), serverId, result);
        setStatus("Respuesta de " + serverId + " para " + reqId + ": " + expression + " = " + result);
    }

    /** Reenvia las solicitudes que siguen pendientes (tras una reconexion). */
    private void resendPending() {
        for (Request req : pending.values()) {
            if (req.state != State.COMPLETADA && req.state != State.TIMEOUT
                    && req.state != State.ERROR) {
                dispatch(req, true);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Utilidades de red
    // ---------------------------------------------------------------------

    private boolean send(String message) {
        PrintWriter w = this.out;
        if (w == null || !connected) {
            return false;
        }
        synchronized (this) {
            w.println(message);
            return !w.checkError();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
