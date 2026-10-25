package common;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Protocolo de comunicacion de la arquitectura ADS (Campo de Datos).
 *
 * Toda la comunicacion viaja por BROADCAST a traves de la malla de nodos.
 * Cada mensaje es UNA linea de texto terminada en '\n', con campos codificados
 * como pares clave=valor separados por el caracter '|'.
 *
 * Formato general:
 *     clave1=valor1|clave2=valor2|...
 *
 * Este formato clave=valor se eligio (en lugar de campos posicionales) para que
 * el protocolo sea FLEXIBLE y EXTENSIBLE: distintos tipos de mensaje pueden
 * llevar distintos campos sin romper a los lectores, y es independiente del
 * numero de nodos o celulas conectadas.
 *
 * ---------------------------------------------------------------------------
 * TIPOS DE MENSAJE (campo "type")
 * ---------------------------------------------------------------------------
 *
 * 1) HELLO    -> Registro de una entidad al conectarse a un nodo.
 *        type=HELLO|role=<GUI|SERVER|NODE>|entity=<id>|svc=<SUMA|RESTA|...>
 *
 * 2) WELCOME  -> Respuesta del nodo a una celula; le informa su nodo anfitrion.
 *        type=WELCOME|node=<nodeId>
 *
 * 3) PRESENCE -> Anuncio periodico de una celula servidor viva (descubrimiento
 *        descentralizado). Permite a cada servidor conocer que otros servidores
 *        de su mismo servicio estan activos, SIN coordinador central.
 *        type=PRESENCE|origin=<serverId>|svc=<servicio>|ttl=<n>
 *
 * 4) OP_REQ   -> Solicitud de operacion originada por una celula GUI.
 *        type=OP_REQ|msgId=<uuid>|reqId=<idLogico>|cc=<SUM_REQ|...>|
 *        origin=<guiId>|node=<nodoGui>|a=<op1>|b=<op2>|ttl=<n>
 *    - msgId:  identificador UNICO de ESTE mensaje (prevencion de ciclos).
 *    - reqId:  identificador LOGICO de la solicitud; se conserva entre reintentos
 *              para poder asociar la respuesta y detectar reintentos.
 *
 * 5) OP_RES   -> Respuesta generada por una celula servidor.
 *        type=OP_RES|msgId=<uuid>|reqId=<idLogico>|cc=<SUM_RES|...>|
 *        origin=<serverId>|target=<guiId>|expr=<expresion>|result=<resultado>|ttl=<n>
 *    - msgId:  identificador UNICO propio de la respuesta.
 *    - reqId:  referencia a la solicitud original (asociacion req/res).
 *
 * Los Codigos de Contenido permiten que cada celula servidor determine, solo
 * mirando el campo "cc", si una solicitud corresponde a su servicio.
 */
public final class Protocol {

    public static final String SEP = "\\|"; // separador para split (regex)
    public static final String DELIM = "|"; // separador para construir mensajes
    public static final char KV = '=';       // separador clave=valor

    // --- Tipos de mensaje ---
    public static final String TYPE = "type";
    public static final String HELLO = "HELLO";
    public static final String WELCOME = "WELCOME";
    public static final String PRESENCE = "PRESENCE";
    public static final String OP_REQ = "OP_REQ";
    public static final String OP_RES = "OP_RES";

    // --- Roles de entidad ---
    public static final String ROLE_GUI = "GUI";
    public static final String ROLE_SERVER = "SERVER";
    public static final String ROLE_NODE = "NODE";

    // --- Nombres de servicio (operacion que atiende una celula servidor) ---
    public static final String SVC_SUMA = "SUMA";
    public static final String SVC_RESTA = "RESTA";
    public static final String SVC_MULT = "MULT";
    public static final String SVC_DIV = "DIV";

    // --- Codigos de Contenido: SOLICITUDES ---
    public static final String SUM_REQ = "SUM_REQ";
    public static final String SUB_REQ = "SUB_REQ";
    public static final String MUL_REQ = "MUL_REQ";
    public static final String DIV_REQ = "DIV_REQ";

    // --- Codigos de Contenido: RESPUESTAS ---
    public static final String SUM_RES = "SUM_RES";
    public static final String SUB_RES = "SUB_RES";
    public static final String MUL_RES = "MUL_RES";
    public static final String DIV_RES = "DIV_RES";

    /** TTL por defecto para acotar la propagacion en la malla. */
    public static final int DEFAULT_TTL = 16;

    private Protocol() {
    }

    // -----------------------------------------------------------------------
    // Generacion de identificadores unicos
    // -----------------------------------------------------------------------

    /** Genera un identificador unico (UUID). */
    public static String newMsgId() {
        return UUID.randomUUID().toString();
    }

    // -----------------------------------------------------------------------
    // Construccion de mensajes
    // -----------------------------------------------------------------------

    /** Construye el HELLO de una celula GUI o de un nodo (sin servicio). */
    public static String hello(String role, String entityId) {
        return hello(role, entityId, null);
    }

    /** Construye el HELLO. Para celulas servidor incluye el servicio (svc). */
    public static String hello(String role, String entityId, String service) {
        Map<String, String> m = new HashMap<>();
        m.put(TYPE, HELLO);
        m.put("role", role);
        m.put("entity", entityId);
        if (service != null) {
            m.put("svc", service);
        }
        return encode(m);
    }

    /** Construye el WELCOME que un nodo envia a una celula recien conectada. */
    public static String welcome(String nodeId) {
        Map<String, String> m = new HashMap<>();
        m.put(TYPE, WELCOME);
        m.put("node", nodeId);
        return encode(m);
    }

    /**
     * Construye un anuncio de presencia de una celula servidor. Se difunde por
     * broadcast periodicamente para que todos los servidores del mismo servicio
     * se descubran entre si de forma descentralizada.
     */
    public static String presence(String serverId, String service, int ttl) {
        Map<String, String> m = new HashMap<>();
        m.put(TYPE, PRESENCE);
        m.put("msgId", newMsgId()); // identificador unico: permite deduplicacion de ciclos en la malla
        m.put("origin", serverId);
        m.put("svc", service);
        m.put("ttl", Integer.toString(ttl));
        return encode(m);
    }

    /**
     * Construye una solicitud de operacion (celula GUI).
     *
     * @param msgId identificador unico de este mensaje concreto.
     * @param reqId identificador LOGICO de la solicitud; se conserva aunque se
     *              reintente, para asociar la respuesta y reconocer reintentos.
     */
    public static String opRequest(String msgId, String reqId, String cc, String originGui,
                                   String originNode, String a, String b, int ttl) {
        Map<String, String> m = new HashMap<>();
        m.put(TYPE, OP_REQ);
        m.put("msgId", msgId);
        m.put("reqId", reqId);
        m.put("cc", cc);
        m.put("origin", originGui);
        m.put("node", originNode);
        m.put("a", a);
        m.put("b", b);
        m.put("ttl", Integer.toString(ttl));
        return encode(m);
    }

    /** Construye una respuesta de operacion (celula servidor). */
    public static String opResponse(String msgId, String reqId, String cc,
                                    String originServer, String targetGui,
                                    String expression, String result, int ttl) {
        Map<String, String> m = new HashMap<>();
        m.put(TYPE, OP_RES);
        m.put("msgId", msgId);
        m.put("reqId", reqId);
        m.put("cc", cc);
        m.put("origin", originServer);
        m.put("target", targetGui);
        m.put("expr", expression);
        m.put("result", result);
        m.put("ttl", Integer.toString(ttl));
        return encode(m);
    }

    // -----------------------------------------------------------------------
    // Codificacion / decodificacion clave=valor
    // -----------------------------------------------------------------------

    /** Codifica un mapa de campos a una linea "clave=valor|clave=valor". */
    public static String encode(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (!first) {
                sb.append(DELIM);
            }
            first = false;
            sb.append(escape(e.getKey())).append(KV).append(escape(e.getValue()));
        }
        return sb.toString();
    }

    /** Decodifica una linea "clave=valor|..." a un mapa de campos. */
    public static Map<String, String> parse(String message) {
        Map<String, String> m = new HashMap<>();
        if (message == null || message.isEmpty()) {
            return m;
        }
        String[] parts = message.split(SEP, -1);
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            int idx = indexOfUnescaped(part, KV);
            if (idx < 0) {
                m.put(unescape(part), "");
            } else {
                String k = unescape(part.substring(0, idx));
                String v = unescape(part.substring(idx + 1));
                m.put(k, v);
            }
        }
        return m;
    }

    /** Devuelve el tipo de mensaje, o cadena vacia si no lo tiene. */
    public static String typeOf(Map<String, String> fields) {
        return fields.getOrDefault(TYPE, "");
    }

    // -----------------------------------------------------------------------
    // Escape de caracteres reservados ( | y = y \ )
    // -----------------------------------------------------------------------

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '|' || c == '=') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String unescape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) {
                sb.append(c);
                esc = false;
            } else if (c == '\\') {
                esc = true;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Busca la primera aparicion no escapada del caracter dado. */
    private static int indexOfUnescaped(String s, char target) {
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) {
                esc = false;
            } else if (c == '\\') {
                esc = true;
            } else if (c == target) {
                return i;
            }
        }
        return -1;
    }

    // -----------------------------------------------------------------------
    // Utilidades de mapeo operacion <-> Codigo de Contenido
    // -----------------------------------------------------------------------

    /** Devuelve el Codigo de Contenido de solicitud para un operador aritmetico. */
    public static String reqCodeForOperator(String operator) {
        switch (operator) {
            case "+": return SUM_REQ;
            case "-": return SUB_REQ;
            case "*": return MUL_REQ;
            case "/": return DIV_REQ;
            default:  return null;
        }
    }

    /** Devuelve el operador aritmetico asociado a un Codigo de Contenido de solicitud. */
    public static String operatorForReqCode(String cc) {
        switch (cc) {
            case SUM_REQ: return "+";
            case SUB_REQ: return "-";
            case MUL_REQ: return "*";
            case DIV_REQ: return "/";
            default:      return null;
        }
    }

    /** Codigo de Contenido de respuesta correspondiente a un codigo de solicitud. */
    public static String resCodeForReqCode(String reqCode) {
        switch (reqCode) {
            case SUM_REQ: return SUM_RES;
            case SUB_REQ: return SUB_RES;
            case MUL_REQ: return MUL_RES;
            case DIV_REQ: return DIV_RES;
            default:      return null;
        }
    }

    /** Codigo de Contenido de respuesta que corresponde a un codigo de solicitud (alias). */
    public static String resCodeForReq(String reqCode) {
        return resCodeForReqCode(reqCode);
    }

    /** Codigo de Contenido de solicitud que atiende un servicio dado. */
    public static String reqCodeForService(String service) {
        switch (service) {
            case SVC_SUMA: return SUM_REQ;
            case SVC_RESTA: return SUB_REQ;
            case SVC_MULT: return MUL_REQ;
            case SVC_DIV:  return DIV_REQ;
            default:       return null;
        }
    }
}
