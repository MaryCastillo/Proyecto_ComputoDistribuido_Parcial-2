# Calculadora Distribuida — Arquitectura ADS (Campo de Datos)

Proyecto del **Segundo Parcial** de **Cómputo Distribuido**. Adecúa la calculadora
del primer parcial a una **arquitectura ADS**: un **Campo de Datos** formado por
nodos en **malla completa**, **células GUI** y **células servidor** distribuidas,
comunicación por **broadcast** y **Códigos de Contenido** para solicitudes y
respuestas. Escrita en **Java** (solo biblioteca estándar), GUI **Swing** y
persistencia/bitácora en **archivos CSV**.

---

## 1. Arquitectura ADS

No hay nodo central ni coordinador. Todos los nodos ejecutan **el mismo programa**
y forman una malla donde cada mensaje se propaga por **broadcast**.

```
        Celulas de N1                     Celulas de N2
   GUI  +  -  x  /                    GUI  +  -  x  /
        \  \ | / /                         \  \ | / /
         [ NODO N1 ]======================[ NODO N2 ]
             ||   \\                        //   ||
             ||     \\                    //     ||
             ||       \\                //       ||
             ||         \\            //         ||
             ||           \\        //           ||
         [ NODO N3 ]======================[ NODO N4 ]
        /  / | \ \                         /  / | \ \
   GUI  +  -  x  /                    GUI  +  -  x  /
        Celulas de N3                     Celulas de N4
```

- **4 nodos** (N1..N4) en **malla completa**: cada nodo está enlazado con los
  otros tres.
- Cada nodo tiene conectadas **5 células**: 1 GUI + 4 servidores (+, -, ×, ÷).
- Total de la configuración mínima: **20 células** (4 GUI + 16 servidor).

La solución **no está limitada** a 4 nodos ni a 4 instancias por tipo: el mismo
programa de nodo y de célula admite **cualquier número** de instancias sin cambiar
el código (los peers y puertos se pasan por línea de comandos).

### Componentes
- **Nodo del Campo de Datos (`node.Node`)**: forma la malla, acepta conexiones de
  células y de otros nodos, difunde por broadcast y previene ciclos/duplicados.
  Si un enlace de nodo se cae, lo **reconstruye automáticamente**.
- **Célula GUI (`client.CalculatorClient`)**: interfaz Swing; arma la solicitud con
  su Código de Contenido e identificador único, y muestra la respuesta asociándola
  a su solicitud. Maneja **estados de solicitud, timeout, reintentos** y
  **reconexión automática** al nodo.
- **Célula servidor (`server.CalculationServer`)**: configurable para UN servicio
  (SUMA, RESTA, MULT o DIV); atiende solo las solicitudes de su Código de Contenido.
  Participa en la **selección distribuida con failover** y se **reconecta**
  automáticamente si pierde el nodo.

### Tolerancia a fallos (sin punto único de fallo)
- Si la célula servidor que debía responder está caída, **otra célula del mismo
  servicio** cubre la solicitud (failover), sin duplicar respuestas.
- Si un **nodo** se cae y vuelve, sus enlaces de malla y sus células se **reconectan**
  solas.
- Si una **GUI** pierde su nodo, se reconecta, se vuelve a registrar y **reenvía** las
  solicitudes pendientes.

---

## 2. Códigos de Contenido

| Operación | Código solicitud | Código respuesta |
|---|---|---|
| Suma | `SUM_REQ` | `SUM_RES` |
| Resta | `SUB_REQ` | `SUB_RES` |
| Multiplicación | `MUL_REQ` | `MUL_RES` |
| División | `DIV_REQ` | `DIV_RES` |

Cada célula servidor mira el campo `cc` del mensaje y determina si la solicitud
corresponde a su servicio.

---

## 3. Formato de los mensajes

Cada mensaje es **una línea de texto** con campos `clave=valor` separados por `|`.
Este formato es extensible e independiente del número de instancias.

| Tipo | Campos principales |
|---|---|
| `HELLO` | `type=HELLO` · `role=GUI\|SERVER\|NODE` · `entity=<id>` · `svc=<servicio>` (solo servidor) |
| `WELCOME` | `type=WELCOME` · `node=<nodeId>` (el nodo informa a la célula a qué nodo quedó asociada) |
| `PRESENCE` | `type=PRESENCE` · `msgId=<uuid>` · `origin=<serverId>` · `svc=<servicio>` · `ttl` (anuncio periódico de servidor vivo) |
| `OP_REQ` | `type=OP_REQ` · `msgId=<uuid>` · `reqId=<idLógico>` · `cc=<SUM_REQ…>` · `origin=<guiId>` · `node=<nodoDeLaGui>` · `a`,`b` · `ttl` |
| `OP_RES` | `type=OP_RES` · `msgId=<uuid>` · `reqId=<idLógico>` · `cc=<SUM_RES…>` · `origin=<serverId>` · `target=<guiId>` · `expr` · `result` · `ttl` |

- **`msgId`**: identificador único de **cada mensaje concreto** (prevención de ciclos).
  En un reintento, el `msgId` cambia.
- **`reqId`**: identificador **lógico** de la solicitud; **se conserva entre
  reintentos**. Permite asociar la respuesta con su solicitud y reconocer reintentos.
- **`ttl`**: tiempo de vida; cada reenvío lo decrementa.

---

## 4. Broadcast, prevención de ciclos y duplicados

- Cada nodo **retransmite** todo mensaje a sus demás enlaces, excepto por el que llegó.
- Cada nodo **recuerda** los `msgId` ya procesados (con expiración temporal) y
  **descarta** cualquier copia repetida: así un mensaje no circula indefinidamente.
- El `ttl` acota la propagación como salvaguarda adicional.
- Las respuestas generan su **propio `msgId`** y conservan el `reqId` de la solicitud.

### Evitar respuestas duplicadas con tolerancia a fallos (selección distribuida)
Como hay una célula servidor de cada tipo en cada nodo, varias reciben la misma
solicitud por broadcast. Se necesita que responda **solo una**, pero que si esa está
caída, **otra la cubra**. La estrategia (sin coordinador central) es:

1. **Descubrimiento (`PRESENCE`)**: cada servidor difunde un anuncio periódico (cada
   ~2 s). Cada servidor construye, por su cuenta, la lista de servidores **vivos** de
   su mismo servicio (con expiración por inactividad de ~6 s). Nadie coordina a nadie.

2. **Asignación determinista**: al llegar una `OP_REQ`, todos los servidores vivos del
   servicio se ordenan igual (por id) y calculan el **primario** como
   `hash(reqId) % N`. El primario responde de inmediato.

3. **Failover escalonado**: los demás servidores programan una respuesta diferida
   (retardo creciente). Antes de emitir, comprueban si **ya vieron pasar** una
   respuesta para ese `reqId` (las respuestas también viajan por broadcast). Si ya
   existe, cancelan; si el primario estaba caído, el primer secundario cubre.

**Resultado:** una sola respuesta por solicitud en condiciones normales, y respaldo
automático si el servidor elegido no está disponible. No depende del nodo de origen,
así que funciona aunque el servidor del nodo de la GUI esté caído.

---

## 5. Estados de solicitud, timeout y reintentos (en la GUI)

Cada solicitud de la GUI tiene un **estado** visible en la tabla:
`PENDIENTE → EN_PROCESO → COMPLETADA` (o `REINTENTANDO`, `TIMEOUT`).

- Si no llega respuesta en el **timeout** (por defecto 4 s), la GUI **reintenta**
  (hasta 3 veces) reusando el **mismo `reqId`** (identificador lógico) pero con un
  **nuevo `msgId`**. Así el reintento no se confunde con una operación nueva.
- Agotados los reintentos, la solicitud pasa a **TIMEOUT** (error controlado; ya no
  queda colgada en `(esperando...)`).
- La GUI acepta **una sola respuesta por `reqId`** (ignora duplicadas o tardías) y
  verifica que el Código de Contenido de respuesta corresponda a la solicitud.

---

## 6. Flujo de una operación

1. El usuario arma la operación en una célula GUI y la envía.
2. La GUI genera `OP_REQ` con `msgId` único, `reqId` lógico y Código de Contenido.
3. El nodo hace **broadcast** por la malla y a sus células.
4. Los nodos **descartan** las copias ya vistas (por `msgId`).
5. Las células servidor del servicio aplican la **selección distribuida**: una responde
   (o la cubre un secundario por failover si la primaria no está).
6. El servidor genera `OP_RES` (nuevo `msgId`, `reqId` de la solicitud, resultado y
   Código de Contenido de respuesta) y lo difunde.
7. La GUI de destino recibe la respuesta, la asocia por `reqId` y muestra el resultado.

---

## 7. Requisito previo: instalar el JDK

Necesitas el **JDK 17 o superior** (recomendado **Temurin / Eclipse Adoptium 21 LTS**).
El JDK incluye el compilador `javac`; un simple JRE **no basta** para compilar.

1. Descárgalo desde: https://adoptium.net/es/temurin/releases/
   (Windows, x64, paquete **JDK**, versión **21 - LTS**, instalador `.msi`).
2. Durante la instalación marca **"Set JAVA_HOME variable"** y **"Add to PATH"**.
3. Reabre la terminal y verifica:

```powershell
java -version
javac -version
```

Ambos deben mostrar la versión instalada.

---

## 8. Compilar

Lo más simple: doble clic en **`INICIAR_PROYECTO.bat`** (carpeta principal); él
compila solo si hace falta. Para compilar sin ejecutar, doble clic en
`scripts\0_compilar.bat`. Las clases quedan en `out\`.

Alternativa manual en PowerShell, desde la carpeta del proyecto:

```powershell
javac -d out (Get-ChildItem -Recurse src -Filter *.java).FullName
```

Los scripts detectan el JDK por `JAVA_HOME`, el `PATH` y las carpetas estándar
de instalación, aunque la ruta tenga espacios o paréntesis.

---

## 9. Ejecutar

### Opción recomendada — doble clic (sin abrir PowerShell)

Doble clic en **`INICIAR_PROYECTO.bat`** (en la carpeta principal del proyecto).
Hace todo en orden: comprueba el JDK, compila si hace falta, **se detiene si la
compilación falla**, arranca los 4 nodos, espera a que escuchen, levanta las 16
células servidor, abre las 4 calculadoras y muestra si todo quedó bien. Si el
sistema ya estaba corriendo, **no duplica procesos**.

Para **detener solo este proyecto** (sin tocar otros programas Java): doble clic
en **`DETENER_PROYECTO.bat`**.

**El orden recomendado** (que el lanzador respeta): primero los nodos (para que la
malla se forme), luego los servidores y por último las GUIs. Como todos los
componentes **reintentan la conexión**, el orden exacto no es crítico y pueden
reiniciarse en caliente.

### Opción A — todo de una vez (ya compilado)
```
scripts\9_lanzar_todo.bat
```
Levanta los 4 nodos, las 16 células servidor y las 4 GUIs (configuración mínima).

### Opción B — manual, por componente
Puertos: **N1=6001, N2=6002, N3=6003, N4=6004**.

```powershell
# 4 nodos (una terminal cada uno)
java -cp out node.Node N1 6001 localhost:6002 localhost:6003 localhost:6004
java -cp out node.Node N2 6002 localhost:6001 localhost:6003 localhost:6004
java -cp out node.Node N3 6003 localhost:6001 localhost:6002 localhost:6004
java -cp out node.Node N4 6004 localhost:6001 localhost:6002 localhost:6003

# Células servidor del nodo 1 (ejemplo; repetir por nodo cambiando puerto e id)
java -cp out server.CalculationServer S1-SUMA  SUMA  localhost 6001
java -cp out server.CalculationServer S1-RESTA RESTA localhost 6001
java -cp out server.CalculationServer S1-MULT  MULT  localhost 6001
java -cp out server.CalculationServer S1-DIV   DIV   localhost 6001

# Célula GUI del nodo 1
java -cp out client.CalculatorClient G1 localhost 6001
```

Scripts equivalentes: `1_nodoX.bat`, `2_servidores_nodoX.bat`, `3_gui_nodoX.bat`.
Para **detener todo** de forma ordenada: `scripts\detener_todo.bat`.

### Escalar a más nodos/células
Añade otro nodo indicando sus peers, y conecta a él las células que quieras:
```powershell
java -cp out node.Node N5 6005 localhost:6001 localhost:6002 localhost:6003 localhost:6004
java -cp out client.CalculatorClient G5 localhost 6005
```
No hay que tocar el código: todo se parametriza por línea de comandos.

---

## 10. Persistencia y bitácora (archivos CSV)

Se crean en la carpeta desde donde ejecutes cada componente:

- **Nodo**: `nodo_<id>_bitacora.csv`
  - Columnas: `timestamp, evento, desdeHaciaLink, type, msgId, cc, detalle`
  - Registra RECIBIDO, BROADCAST, ENTREGA-LOCAL, DUPLICADO-DESCARTADO, HELLO.
  - Permite **observar el recorrido** de los mensajes por la malla.
- **Célula servidor**: `servidor_<id>_resultados.csv`
  - Columnas: `timestamp, evento, msgId, reqId, servicio, serverId, nodo, expresion, resultado`
  - `evento` indica si respondió como `PRIMARIO` o por `FAILOVER` (respaldo).
- **Célula GUI**: `gui_<id>_historial.csv`
  - Columnas: `timestamp, tipo, estado, msgId, reqId, cc, guiId, nodo, servidor, expresion, resultado`
  - `tipo` = `SOLICITUD` / `RESPUESTA` / `RESULTADO`; `estado` = estado final de la solicitud.

---

## 11. Guía para la demostración (video)

1. Arranca los **4 nodos**; observa el enlace con los peers (malla completa).
2. Arranca las **16 células servidor** (4 por nodo); cada una imprime su nodo y empieza
   a anunciar `PRESENCE`.
3. Arranca las **4 GUIs** (una por nodo).
4. En una GUI envía una **suma** (`10 + 5`). Observa en los nodos `RECIBIDO`,
   `broadcast` y `DUPLICADO-DESCARTADO`, y que **solo una** célula de suma responde.
5. Repite con **resta, multiplicación y división** (incluye **división entre cero**).
6. **Tolerancia a fallos — servidor**: detén `S1-SUMA` y envía `25 + 15` desde G1.
   Otra célula de suma responde por **failover**; la GUI obtiene `40`.
7. **Tolerancia a fallos — nodo**: cierra un nodo (p. ej. N4) y comprueba que los demás
   siguen operando; reinícialo y verás que sus enlaces y células se **reconectan** solos.
8. **Timeout/estados**: con los servidores de un servicio detenidos, envía esa operación
   y observa el paso a `REINTENTANDO` y finalmente `TIMEOUT` (nunca queda colgada).
9. **Escalabilidad**: añade `N5` y conéctale una GUI; envía y recibe sin tocar código.
10. Abre los CSV para evidenciar recorrido, failover y estados.

---

## 12. Estructura del proyecto

```
Proyecto_ComputoDistribuido_v2/
├── INICIAR_PROYECTO.bat                      (doble clic: compila y arranca todo)
├── DETENER_PROYECTO.bat                      (doble clic: detiene solo este proyecto)
├── README.md
├── REPORTE_TECNICO.md
├── scripts/
│   ├── _buscar_java.bat                      (detección robusta del JDK/JRE; uso interno)
│   ├── 0_compilar.bat
│   ├── 1_nodo1.bat .. 1_nodo4.bat          (los 4 nodos de la malla)
│   ├── 2_servidores_nodo1.bat .. _nodo4.bat (4 servidores por nodo)
│   ├── 3_gui_nodo1.bat .. _nodo4.bat        (1 GUI por nodo)
│   ├── 9_lanzar_todo.bat                     (configuración mínima completa)
│   └── detener_todo.bat                      (detiene los componentes lanzados)
└── src/
    ├── common/
    │   ├── Protocol.java     (Códigos de Contenido + formato clave=valor)
    │   └── CsvStore.java     (persistencia/bitácora CSV thread-safe)
    ├── node/
    │   └── Node.java         (nodo del Campo de Datos: malla + broadcast + anti-ciclos)
    ├── server/
    │   └── CalculationServer.java  (célula servidor por servicio)
    └── client/
        └── CalculatorClient.java   (célula GUI Swing)
```
