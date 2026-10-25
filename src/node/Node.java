package node;

import common.CsvStore;
import common.Protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Nodo del Campo de Datos (arquitectura ADS).
 *
 * Un nodo:
 *   - Escucha en un puerto TCP y acepta conexiones de CELULAS (GUI o servidor)
 *     y de OTROS NODOS, formando una malla.
 *   - Se conecta activamente a los nodos "peer" indicados por linea de comandos
 *     para completar la malla; si un enlace de peer se cae, lo RECONSTRUYE
 *     automaticamente (recuperacion de la malla).
 *   - Difunde (BROADCAST) cada mensaje que recibe a todas sus demas conexiones,
 *     excepto a aquella por la que llego el mensaje.
 *   - Previene ciclos y duplicados: recuerda los identificadores de mensaje ya
 *     procesados y descarta cualquier copia repetida; ademas decrementa un TTL.
 *
 * NO existe nodo central ni coordinador. El mismo binario sirve para 4 nodos
 * (configuracion minima) o para N nodos.
 *
 * Uso:
 *   java node.Node <nodeId> <puertoEscucha> [peerHost:peerPuerto ...]
 */
public class Node {

    /** Tiempo (ms) que un identificador de mensaje permanece "visto". */
    private static final long SEEN_TTL_MS = 60_000L;
    /** Retardo entre reintentos de conexion a un peer. */
    private static final long PEER_RETRY_MS = 1_500L;

    private final String nodeId;
    private final int listenPort;
    private final String[] peers;

    private final CopyOnWriteArrayList<Link> links = new CopyOnWriteArrayList<>();
    private final AtomicInteger linkCounter = new AtomicInteger(0);

    /** Identificadores de mensaje ya procesados -> instante de registro (ms). */
    private final ConcurrentHashMap<String, Long> seen = new ConcurrentHashMap<>();

    /** Peers hacia los que YA existe un enlace de nodo activo (evita duplicados). */
    private final ConcurrentHashMap<String, Boolean> connectedPeers = new ConcurrentHashMap<>();

    private final CsvStore log;

    public Node(String nodeId, int listenPort, String[] peers) {
        this.nodeId = nodeId;
        this.listenPort = listenPort;
        this.peers = peers;
        this.log = new CsvStore("nodo_" + nodeId + "_bitacora.csv",
                "timestamp,evento,desdeHaciaLink,type,msgId,cc,detalle");
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Uso: java node.Node <nodeId> <puerto> [peerHost:peerPuerto ...]");
            return;
        }
        String nodeId = args[0];
        int port;
        try {
            port = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.err.println("Puerto invalido: " + args[1]);
            return;
        }
        String[] peers = new String[Math.max(0, args.length - 2)];
        System.arraycopy(args, 2, peers, 0, peers.length);
        new Node(nodeId, port, peers).start();
    }

    public void start() {
        System.out.println("=== NODO " + nodeId + " (Campo de Datos) ===");
        System.out.println("Escuchando en el puerto " + listenPort);
        System.out.println("Bitacora en: " + log.path());

        Thread cleaner = new Thread(this::cleanupLoop, "seen-cleaner-" + nodeId);
        cleaner.setDaemon(true);
        cleaner.start();

        // Un hilo por peer que mantiene vivo el enlace (reconexion automatica).
        for (String peer : peers) {
            startPeerMaintainer(peer);
        }

        try (ServerSocket serverSocket = new ServerSocket(listenPort)) {
            while (true) {
                Socket socket = serverSocket.accept();
                Link link = new Link(socket, linkCounter.incrementAndGet(), null);
                links.add(link);
                link.start();
            }
        } catch (IOException e) {
            System.err.println("Nodo " + nodeId + " error de escucha: " + e.getMessage());
        }
    }

    /**
     * Mantiene vivo el enlace con un peer: lo conecta y, cada vez que se cae, lo
     * vuelve a conectar. Evita abrir un segundo enlace si ya hay uno activo.
     */
    private void startPeerMaintainer(String peer) {
        String[] hp = peer.split(":");
        if (hp.length != 2) {
            System.err.println("Peer invalido (se espera host:puerto): " + peer);
            return;
        }
        final String host = hp[0];
        final int port;
        try {
            port = Integer.parseInt(hp[1]);
        } catch (NumberFormatException e) {
            System.err.println("Puerto de peer invalido: " + peer);
            return;
        }

        Thread t = new Thread(() -> {
            while (true) {
                // Si ya hay un enlace activo con este peer, no abrir otro.
                if (Boolean.TRUE.equals(connectedPeers.get(peer))) {
                    sleep(PEER_RETRY_MS);
                    continue;
                }
                try {
                    Socket socket = new Socket();
                    socket.connect(new InetSocketAddress(host, port), 2000);
                    connectedPeers.put(peer, Boolean.TRUE);
                    Link link = new Link(socket, linkCounter.incrementAndGet(), peer);
                    links.add(link);
                    link.sendRaw(Protocol.hello(Protocol.ROLE_NODE, nodeId));
                    link.start();
                    System.out.println("[" + nodeId + "] enlazado con nodo peer " + peer);
                } catch (IOException e) {
                    // No se pudo: reintentar tras una pausa.
                    sleep(PEER_RETRY_MS);
                }
            }
        }, "peer-maintainer-" + peer);
        t.setDaemon(true);
        t.start();
    }

    // -----------------------------------------------------------------------
    // Prevencion de ciclos / duplicados
    // -----------------------------------------------------------------------

    /** Registra un identificador. Devuelve true si es NUEVO (no visto antes). */
    private boolean markSeen(String msgId) {
        Long prev = seen.putIfAbsent(msgId, System.currentTimeMillis());
        return prev == null;
    }

    private void cleanupLoop() {
        while (true) {
            sleep(SEEN_TTL_MS);
            long cutoff = System.currentTimeMillis() - SEEN_TTL_MS;
            seen.entrySet().removeIf(e -> e.getValue() < cutoff);
        }
    }

    // -----------------------------------------------------------------------
    // Broadcast
    // -----------------------------------------------------------------------

    private void onMessage(String raw, Link source) {
        Map<String, String> f = Protocol.parse(raw);
        String type = Protocol.typeOf(f);

        // HELLO: clasifica el enlace; no se difunde.
        if (Protocol.HELLO.equals(type)) {
            source.classify(f);
            log.append(CsvStore.now(), "HELLO", source.label(), type, "",
                    f.getOrDefault("role", ""), f.getOrDefault("entity", ""));
            if (!source.isNode()) {
                source.sendRaw(Protocol.welcome(nodeId));
            }
            return;
        }

        // WELCOME nunca se reenvia.
        if (Protocol.WELCOME.equals(type)) {
            return;
        }

        String msgId = f.getOrDefault("msgId", "");
        String cc = f.getOrDefault("cc", "");

        // Prevencion de ciclos: descartar msgId ya visto.
        if (!msgId.isEmpty() && !markSeen(msgId)) {
            log.append(CsvStore.now(), "DUPLICADO-DESCARTADO", source.label(), type, msgId, cc, "");
            return;
        }

        int ttl = parseTtl(f.getOrDefault("ttl", "0"));
        log.append(CsvStore.now(), "RECIBIDO", source.label(), type, msgId, cc, "ttl=" + ttl);

        if (ttl <= 1) {
            // Entrega local (celulas) pero no se propaga a otros nodos.
            forward(raw, source, true);
            return;
        }

        f.put("ttl", Integer.toString(ttl - 1));
        String outMsg = Protocol.encode(f);
        forward(outMsg, source, false);
    }

    /**
     * Difunde 'message' a todos los enlaces excepto 'source'.
     * Si onlyLocal es true, solo se envia a celulas (no se propaga a otros nodos).
     */
    private void forward(String message, Link source, boolean onlyLocal) {
        int count = 0;
        for (Link l : links) {
            if (l == source || !l.isAlive()) {
                continue;
            }
            if (onlyLocal && l.isNode()) {
                continue;
            }
            if (l.sendRaw(message)) {
                count++;
            }
        }
        Map<String, String> f = Protocol.parse(message);
        log.append(CsvStore.now(), onlyLocal ? "ENTREGA-LOCAL" : "BROADCAST", "->" + count,
                Protocol.typeOf(f), f.getOrDefault("msgId", ""), f.getOrDefault("cc", ""),
                "destinos=" + count);
        System.out.println("[" + nodeId + "] " + (onlyLocal ? "entrega-local" : "broadcast")
                + " de " + Protocol.typeOf(f) + " (cc=" + f.getOrDefault("cc", "")
                + ", msgId=" + shortId(f.getOrDefault("msgId", "")) + ") a " + count + " enlace(s)");
    }

    private void removeLink(Link link) {
        links.remove(link);
        // Si era un enlace saliente hacia un peer, liberar para permitir reconexion.
        if (link.peerKey != null) {
            connectedPeers.remove(link.peerKey);
            System.out.println("[" + nodeId + "] enlace con peer " + link.peerKey
                    + " caido; se reintentara la reconexion.");
        }
    }

    private static int parseTtl(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String shortId(String id) {
        if (id == null || id.length() <= 8) {
            return id;
        }
        return id.substring(0, 8);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -----------------------------------------------------------------------
    // Enlace: una conexion con una celula o con otro nodo
    // -----------------------------------------------------------------------

    private class Link extends Thread {
        private final Socket socket;
        private final int linkId;
        /** Si no es null, este enlace es SALIENTE hacia ese peer (host:puerto). */
        private final String peerKey;
        private PrintWriter out;
        private volatile String role = "?";    // GUI | SERVER | NODE
        private volatile String remoteId = "?";
        private volatile String service = "";

        Link(Socket socket, int linkId, String peerKey) {
            this.socket = socket;
            this.linkId = linkId;
            this.peerKey = peerKey;
            // Un enlace saliente a un peer ya se sabe que es de rol NODE.
            if (peerKey != null) {
                this.role = Protocol.ROLE_NODE;
                this.remoteId = peerKey;
            }
            setName("link-" + nodeId + "-" + linkId);
            try {
                this.out = new PrintWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            } catch (IOException e) {
                System.err.println("No se pudo abrir salida en enlace " + linkId);
            }
        }

        boolean isNode() {
            return Protocol.ROLE_NODE.equals(role);
        }

        String label() {
            return role + ":" + remoteId + "#" + linkId;
        }

        void classify(Map<String, String> f) {
            this.role = f.getOrDefault("role", "?");
            this.remoteId = f.getOrDefault("entity", "?");
            this.service = f.getOrDefault("svc", "");
            System.out.println("[" + nodeId + "] enlace " + linkId + " clasificado como "
                    + role + (service.isEmpty() ? "" : "/" + service) + " id=" + remoteId);
        }

        boolean sendRaw(String message) {
            PrintWriter w = this.out;
            if (w == null) {
                return false;
            }
            synchronized (this) {
                w.println(message);
                return !w.checkError();
            }
        }

        @Override
        public void run() {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        onMessage(line, this);
                    }
                }
            } catch (IOException e) {
                // Conexion cerrada; se limpia abajo.
            } finally {
                removeLink(this);
                System.out.println("[" + nodeId + "] enlace cerrado: " + label());
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
