package server;

import common.CsvStore;
import common.Protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Celula servidor de la arquitectura ADS, con SELECCION DISTRIBUIDA y FAILOVER.
 *
 * Atiende UN solo servicio (SUMA, RESTA, MULT o DIV), configurable al arrancar.
 * Se conecta a un nodo del Campo de Datos y escucha por BROADCAST las solicitudes
 * que circulan por la malla.
 *
 * ---------------------------------------------------------------------------
 * SELECCION DISTRIBUIDA DE SERVIDORES (sin coordinador central)
 * ---------------------------------------------------------------------------
 * Como existe una celula servidor de cada tipo por nodo, varias reciben la misma
 * solicitud. Para que responda SOLO UNA (evitando duplicados) pero con TOLERANCIA
 * A FALLOS (si la elegida esta caida, responde otra), se usa:
 *
 *   1) DESCUBRIMIENTO: cada servidor difunde un PRESENCE periodico. Cada servidor
 *      mantiene, por su cuenta, la lista de servidores VIVOS de su mismo servicio
 *      (con expiracion por inactividad). No hay maestro: cada uno decide solo.
 *
 *   2) ASIGNACION DETERMINISTA: al llegar una OP_REQ, todos los servidores vivos
 *      del servicio se ordenan determinısticamente (por id). El "primario" se
 *      calcula como hash(reqId) % N. Ese primario responde de inmediato.
 *
 *   3) FAILOVER ESCALONADO: los demas servidores (secundarios) programan una
 *      respuesta diferida (retardo creciente segun su posicion). Antes de emitir,
 *      comprueban si YA vieron pasar una OP_RES para ese reqId (las respuestas
 *      tambien viajan por broadcast y las escucha todo servidor). Si la respuesta
 *      ya existe, cancelan; si el primario estaba caido y no respondio, el primer
 *      secundario cubre el servicio. Resultado: UNA sola respuesta, con respaldo.
 *
 * ---------------------------------------------------------------------------
 * TOLERANCIA A FALLOS DE CONEXION (reconexion automatica)
 * ---------------------------------------------------------------------------
 * Si el nodo se cae, el socket se rompe; la celula reintenta conectarse en bucle,
 * reconstruye el socket y vuelve a registrarse (HELLO), recuperando el servicio
 * sin reiniciar el proceso manualmente.
 *
 * Uso:
 *   java server.CalculationServer <serverId> <SUMA|RESTA|MULT|DIV> [host] [puerto]
 */
public class CalculationServer {

    /** Periodo de anuncio de presencia. */
    private static final long PRESENCE_PERIOD_MS = 2_000L;
    /** Tiempo sin PRESENCE tras el cual se considera caido a un servidor. */
    private static final long PRESENCE_EXPIRY_MS = 6_000L;
    /** Retardo base del failover escalonado (por posicion de secundario). */
    private static final long FAILOVER_STEP_MS = 500L;
    /** Reintento de reconexion al nodo. */
    private static final long RECONNECT_DELAY_MS = 1_500L;

    private final String serverId;
    private final String service;      // SUMA | RESTA | MULT | DIV
    private final String reqCode;      // Codigo de Contenido de solicitud que atiende
    private final String host;
    private final int port;
    private final CsvStore store;

    private volatile String myNode = "?";
    private volatile PrintWriter out;  // salida actual hacia el nodo (cambia al reconectar)

    /** Servidores vivos del MISMO servicio: serverId -> ultima vez visto (ms). */
    private final ConcurrentHashMap<String, Long> liveServers = new ConcurrentHashMap<>();
    /** reqId de solicitudes para las que YA se vio circular una respuesta. */
    private final ConcurrentHashMap<String, Long> answered = new ConcurrentHashMap<>();
    /** reqId que este servidor ya atendio (evita doble procesamiento local). */
    private final ConcurrentHashMap<String, Long> handledHere = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public CalculationServer(String serverId, String service, String host, int port) {
        this.serverId = serverId;
        this.service = service;
        this.reqCode = Protocol.reqCodeForService(service);
        this.host = host;
        this.port = port;
        this.store = new CsvStore("servidor_" + serverId + "_resultados.csv",
                "timestamp,evento,msgId,reqId,servicio,serverId,nodo,expresion,resultado");
        // Este servidor siempre se cuenta a si mismo como vivo.
        liveServers.put(serverId, System.currentTimeMillis());
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Uso: java server.CalculationServer <serverId> <SUMA|RESTA|MULT|DIV> [host] [puerto]");
            return;
        }
        String serverId = args[0];
        String service = args[1].toUpperCase();
        if (Protocol.reqCodeForService(service) == null) {
            System.err.println("Servicio invalido: " + service + " (use SUMA, RESTA, MULT o DIV)");
            return;
        }
        String host = args.length >= 3 ? args[2] : "localhost";
        int port = 6001;
        if (args.length >= 4) {
            try {
                port = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                System.err.println("Puerto invalido, usando 6001.");
            }
        }
        new CalculationServer(serverId, service, host, port).start();
    }

    public void start() {
        System.out.println("=== CELULA SERVIDOR " + serverId + " (servicio " + service + ") ===");
        System.out.println("Codigo de Contenido que atiende: " + reqCode);
        System.out.println("Persistencia en: " + store.path());

        // Tarea periodica: anunciar presencia y expirar servidores inactivos.
        scheduler.scheduleAtFixedRate(this::presenceTick, 0, PRESENCE_PERIOD_MS, TimeUnit.MILLISECONDS);
        // Limpieza periodica de registros 'answered'/'handledHere' antiguos.
        scheduler.scheduleAtFixedRate(this::cleanupTick, 30, 30, TimeUnit.SECONDS);

        // Bucle de conexion con reconexion automatica (tolerancia a fallos).
        connectLoop();
    }

    // -----------------------------------------------------------------------
    // Conexion con reconexion automatica
    // -----------------------------------------------------------------------

    private void connectLoop() {
        while (true) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 3000);
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter w = new PrintWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                this.out = w;

                // Registro ante el nodo: SERVER + servicio.
                w.println(Protocol.hello(Protocol.ROLE_SERVER, serverId, service));
                System.out.println("Conectada al nodo " + host + ":" + port + " como SERVER/" + service + ".");
                System.out.println("Esperando solicitudes por broadcast...");

                String line;
                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        handleLine(line);
                    }
                }
                // readLine devolvio null: el nodo cerro la conexion.
                System.err.println("Conexion con el nodo cerrada. Reintentando...");
            } catch (IOException e) {
                System.err.println("Celula servidor " + serverId + " sin conexion ("
                        + e.getMessage() + "). Reintentando en " + (RECONNECT_DELAY_MS / 1000.0) + "s...");
            }
            this.out = null;
            sleep(RECONNECT_DELAY_MS);
        }
    }

    // -----------------------------------------------------------------------
    // Procesamiento de mensajes entrantes
    // -----------------------------------------------------------------------

    private void handleLine(String line) {
        Map<String, String> f = Protocol.parse(line);
        String type = Protocol.typeOf(f);

        switch (type) {
            case Protocol.WELCOME:
                myNode = f.getOrDefault("node", "?");
                System.out.println("Asociada al nodo " + myNode + " del Campo de Datos.");
                break;

            case Protocol.PRESENCE:
                // Solo me interesan los servidores de MI servicio.
                if (service.equals(f.getOrDefault("svc", ""))) {
                    String other = f.getOrDefault("origin", "");
                    if (!other.isEmpty()) {
                        liveServers.put(other, System.currentTimeMillis());
                    }
                }
                break;

            case Protocol.OP_RES:
                // Registrar que ya circulo una respuesta para ese reqId (para failover).
                if (reqCode != null && reqCode.equals(reqCodeOf(f.getOrDefault("cc", "")))) {
                    answered.put(f.getOrDefault("reqId", ""), System.currentTimeMillis());
                }
                break;

            case Protocol.OP_REQ:
                handleRequest(f);
                break;

            default:
                // Mensaje desconocido o malformado: se ignora con seguridad.
                break;
        }
    }

    /** Dado un cc de respuesta (SUM_RES...), devuelve el cc de solicitud equivalente. */
    private static String reqCodeOf(String resCc) {
        if (resCc == null) return "";
        switch (resCc) {
            case Protocol.SUM_RES: return Protocol.SUM_REQ;
            case Protocol.SUB_RES: return Protocol.SUB_REQ;
            case Protocol.MUL_RES: return Protocol.MUL_REQ;
            case Protocol.DIV_RES: return Protocol.DIV_REQ;
            default: return "";
        }
    }

    // -----------------------------------------------------------------------
    // Seleccion distribuida + failover
    // -----------------------------------------------------------------------

    private void handleRequest(Map<String, String> f) {
        String cc = f.getOrDefault("cc", "");

        // Filtrado por Codigo de Contenido: solo mi servicio.
        if (reqCode == null || !reqCode.equals(cc)) {
            return;
        }

        final String reqId = f.getOrDefault("reqId", f.getOrDefault("msgId", ""));
        if (reqId.isEmpty()) {
            return;
        }
        // Si ya atendi este reqId localmente (p.ej. reintento identico), no repito.
        if (handledHere.containsKey(reqId)) {
            return;
        }

        // Vista determinista de servidores vivos de mi servicio.
        List<String> alive = aliveServersSorted();
        int n = alive.size();
        int myIndex = alive.indexOf(serverId);
        if (myIndex < 0) {
            // Aun no me he "visto" a mi mismo; me incluyo al final de forma segura.
            myIndex = n;
            n = n + 1;
        }

        // Primario determinista a partir del reqId.
        int primary = Math.floorMod(reqId.hashCode(), Math.max(1, n));

        // Posicion de este servidor en el anillo relativo al primario:
        //   0 = primario (responde ya); 1,2,... = secundarios (failover diferido).
        int rank = Math.floorMod(myIndex - primary, Math.max(1, n));
        long delay = rank * FAILOVER_STEP_MS;

        if (delay == 0) {
            respond(f, reqId, "PRIMARIO");
        } else {
            // Secundario: responde solo si nadie respondio antes (failover).
            scheduler.schedule(() -> {
                if (answered.containsKey(reqId) || handledHere.containsKey(reqId)) {
                    return; // ya hay respuesta: no duplicar.
                }
                respond(f, reqId, "FAILOVER(rank=" + rankLabel(rank) + ")");
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    private static String rankLabel(int rank) {
        return Integer.toString(rank);
    }

    /** Lista ordenada (determinista) de servidores vivos de mi servicio. */
    private List<String> aliveServersSorted() {
        long cutoff = System.currentTimeMillis() - PRESENCE_EXPIRY_MS;
        List<String> list = new ArrayList<>();
        for (Map.Entry<String, Long> e : liveServers.entrySet()) {
            if (e.getValue() >= cutoff || e.getKey().equals(serverId)) {
                list.add(e.getKey());
            }
        }
        Collections.sort(list);
        return list;
    }

    private void respond(Map<String, String> f, String reqId, String rolInfo) {
        // Marcar como atendido localmente para no repetir.
        if (handledHere.putIfAbsent(reqId, System.currentTimeMillis()) != null) {
            return;
        }
        answered.put(reqId, System.currentTimeMillis());

        String cc = f.getOrDefault("cc", "");
        String originGui = f.getOrDefault("origin", "");
        String a = f.getOrDefault("a", "");
        String b = f.getOrDefault("b", "");
        String operator = Protocol.operatorForReqCode(cc);
        String expression = a + " " + operator + " " + b;
        String resultText = compute(a, operator, b);

        System.out.println("[" + serverId + "] Atendiendo " + cc + " reqId=" + shortId(reqId)
                + " (" + rolInfo + ") (GUI " + originGui + "): " + expression + " = " + resultText);

        store.append(CsvStore.now(), "RESPONDE(" + rolInfo + ")", Protocol.newMsgId(), reqId,
                service, serverId, myNode, expression, resultText);

        String resCode = Protocol.resCodeForReqCode(cc);
        String response = Protocol.opResponse(
                Protocol.newMsgId(), reqId, resCode, serverId, originGui,
                expression, resultText, Protocol.DEFAULT_TTL);
        send(response);
    }

    // -----------------------------------------------------------------------
    // Presencia y limpieza periodica
    // -----------------------------------------------------------------------

    private void presenceTick() {
        // Refrescar mi propia marca y anunciarme.
        liveServers.put(serverId, System.currentTimeMillis());
        send(Protocol.presence(serverId, service, Protocol.DEFAULT_TTL));

        // Expirar servidores que llevan mucho sin anunciarse.
        long cutoff = System.currentTimeMillis() - PRESENCE_EXPIRY_MS;
        liveServers.entrySet().removeIf(e -> !e.getKey().equals(serverId) && e.getValue() < cutoff);
    }

    private void cleanupTick() {
        long cutoff = System.currentTimeMillis() - 120_000L;
        answered.entrySet().removeIf(e -> e.getValue() < cutoff);
        handledHere.entrySet().removeIf(e -> e.getValue() < cutoff);
    }

    /** Envia un mensaje al nodo si hay conexion. Devuelve true si se pudo. */
    private boolean send(String message) {
        PrintWriter w = this.out;
        if (w == null) {
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

    private static String shortId(String id) {
        if (id == null || id.length() <= 8) {
            return id;
        }
        return id.substring(0, 8);
    }

    // -----------------------------------------------------------------------
    // Calculo aritmetico
    // -----------------------------------------------------------------------

    /** Resuelve la operacion aritmetica. Devuelve el resultado o un texto de error. */
    private String compute(String op1, String operator, String op2) {
        if (operator == null) {
            return "ERROR: operador no soportado";
        }
        BigDecimal a;
        BigDecimal b;
        try {
            a = new BigDecimal(op1.trim());
            b = new BigDecimal(op2.trim());
        } catch (NumberFormatException e) {
            return "ERROR: operando no numerico";
        }

        try {
            BigDecimal r;
            switch (operator) {
                case "+":
                    r = a.add(b);
                    break;
                case "-":
                    r = a.subtract(b);
                    break;
                case "*":
                    r = a.multiply(b);
                    break;
                case "/":
                    if (b.compareTo(BigDecimal.ZERO) == 0) {
                        return "ERROR: division entre cero";
                    }
                    r = a.divide(b, new MathContext(20, RoundingMode.HALF_UP));
                    r = r.stripTrailingZeros();
                    break;
                default:
                    return "ERROR: operador no soportado";
            }
            return r.stripTrailingZeros().toPlainString();
        } catch (ArithmeticException e) {
            return "ERROR: " + e.getMessage();
        }
    }
}
