@echo off
setlocal enabledelayedexpansion
REM ============================================================
REM  INICIAR_PROYECTO.bat   (doble clic para arrancar TODO)
REM ------------------------------------------------------------
REM  Flujo automatico:
REM    1) Comprueba que haya Java JDK 21 disponible.
REM    2) Compila si hace falta (o si faltan clases).
REM    3) Se detiene si la compilacion falla.
REM    4) Arranca los 4 nodos (malla completa).
REM    5) Espera a que los nodos esten escuchando.
REM    6) Arranca los 16 servidores.
REM    7) Abre las 4 calculadoras (GUIs).
REM    8) Informa si todo se inicio correctamente.
REM
REM  Robusto ante: PATH sin Java, rutas con espacios/parentesis,
REM  procesos ya en ejecucion (no duplica) y falta de compilacion.
REM  No usa rutas absolutas atadas a un usuario concreto.
REM ============================================================
title INICIAR PROYECTO ADS
cd /d "%~dp0"

echo ============================================================
echo   CALCULADORA DISTRIBUIDA (ADS) - INICIO AUTOMATICO
echo   Carpeta del proyecto: "%CD%"
echo ============================================================
echo.

REM ---- (1) Comprobar Java JDK 21 -----------------------------
echo [1/8] Buscando Java (JDK) en el sistema...
call "scripts\_buscar_java.bat"

if not defined JAVA_EXE (
    echo.
    echo ERROR: no se encontro Java en este equipo.
    echo   - Instala el JDK 21 ^(Microsoft OpenJDK o Temurin/Adoptium^).
    echo   - O define la variable JAVA_HOME apuntando al JDK.
    echo.
    goto :fin_error
)
echo       Java:  "%JAVA_EXE%"

if not defined JAVAC_EXE (
    echo.
    echo ERROR: se encontro Java ^(JRE^) pero NO el compilador 'javac'.
    echo        Para compilar el proyecto necesitas el JDK ^(no solo JRE^).
    echo        Instala el JDK 21 o define JAVA_HOME al JDK.
    echo.
    goto :fin_error
)
echo       Javac: "%JAVAC_EXE%"

REM  Mostrar version (informativo; no bloquea si no es exactamente 21)
for /f "tokens=*" %%V in ('"%JAVA_EXE%" -version 2^>^&1') do (
    echo       %%V
    goto :ver_ok
)
:ver_ok
echo.

REM ---- (2) Compilar si hace falta ----------------------------
echo [2/8] Comprobando si el proyecto necesita compilacion...
set "NEED_BUILD=0"
if not exist "out\node\Node.class" set "NEED_BUILD=1"
if not exist "out\server\CalculationServer.class" set "NEED_BUILD=1"
if not exist "out\client\CalculatorClient.class" set "NEED_BUILD=1"

if "%NEED_BUILD%"=="1" (
    echo       Faltan clases compiladas. Compilando ahora...
    echo.
    call "scripts\0_compilar.bat" /nopause
    set "BUILD_EXIT=!ERRORLEVEL!"
    REM ---- (3) Detenerse si la compilacion fallo -------------
    if not "!BUILD_EXIT!"=="0" (
        echo.
        echo ERROR: la compilacion fallo ^(codigo !BUILD_EXIT!^).
        echo        Revisa los mensajes de arriba. No se iniciara el sistema.
        goto :fin_error
    )
    echo       Compilacion correcta.
) else (
    echo       Ya esta compilado. No es necesario recompilar.
)
echo.

REM ---- Anti-duplicados: si 6001 ya escucha, no relanzar -------
call :puerto_ocupado 6001
if "%PORT_BUSY%"=="1" (
    echo ============================================================
    echo  AVISO: el puerto 6001 ya esta en uso: el sistema ADS parece
    echo  estar ya en ejecucion. No se lanzaran procesos duplicados.
    echo  Para reiniciar: ejecuta primero DETENER_PROYECTO.bat
    echo ============================================================
    echo.
    goto :fin_pausa
)

set "SH=%SystemRoot%\System32\cmd.exe"

REM ---- (4) Arrancar los 4 nodos ------------------------------
echo [3/8]... 
echo [4/8] Arrancando los 4 nodos (malla completa N1..N4)...
start "NODO N1 (6001)" "%SH%" /k ""%JAVA_EXE%" -cp "out" node.Node N1 6001 localhost:6002 localhost:6003 localhost:6004"
start "NODO N2 (6002)" "%SH%" /k ""%JAVA_EXE%" -cp "out" node.Node N2 6002 localhost:6001 localhost:6003 localhost:6004"
start "NODO N3 (6003)" "%SH%" /k ""%JAVA_EXE%" -cp "out" node.Node N3 6003 localhost:6001 localhost:6002 localhost:6004"
start "NODO N4 (6004)" "%SH%" /k ""%JAVA_EXE%" -cp "out" node.Node N4 6004 localhost:6001 localhost:6002 localhost:6003"

REM ---- (5) Esperar a que los nodos escuchen ------------------
echo [5/8] Esperando a que los 4 nodos esten disponibles (6001-6004)...
set "NODOS_OK=1"
call :esperar_puerto 6001 || set "NODOS_OK=0"
call :esperar_puerto 6002 || set "NODOS_OK=0"
call :esperar_puerto 6003 || set "NODOS_OK=0"
call :esperar_puerto 6004 || set "NODOS_OK=0"

if "%NODOS_OK%"=="0" (
    echo.
    echo ADVERTENCIA: no todos los nodos respondieron a tiempo.
    echo Continuo de todos modos, pero revisa las ventanas de los nodos.
)

REM ---- (6) Arrancar los 16 servidores ------------------------
echo [6/8] Arrancando las 16 celulas servidor...
call "scripts\2_servidores_nodo1.bat"
call "scripts\2_servidores_nodo2.bat"
call "scripts\2_servidores_nodo3.bat"
call "scripts\2_servidores_nodo4.bat"

echo       Esperando a que los servidores se registren...
call :pausa 5

REM ---- (7) Abrir las 4 calculadoras (GUIs) -------------------
echo [7/8] Abriendo las 4 calculadoras (GUIs G1..G4)...
start "GUI G1 (N1)" "%SH%" /k ""%JAVA_EXE%" -cp "out" client.CalculatorClient G1 localhost 6001"
start "GUI G2 (N2)" "%SH%" /k ""%JAVA_EXE%" -cp "out" client.CalculatorClient G2 localhost 6002"
start "GUI G3 (N3)" "%SH%" /k ""%JAVA_EXE%" -cp "out" client.CalculatorClient G3 localhost 6003"
start "GUI G4 (N4)" "%SH%" /k ""%JAVA_EXE%" -cp "out" client.CalculatorClient G4 localhost 6004"

REM ---- (8) Informe final -------------------------------------
echo.
echo [8/8] Verificacion final de nodos...
set "FINAL_OK=1"
for %%P in (6001 6002 6003 6004) do (
    call :puerto_ocupado %%P
    if "!PORT_BUSY!"=="1" (
        echo       Nodo %%P: ACTIVO
    ) else (
        echo       Nodo %%P: NO responde
        set "FINAL_OK=0"
    )
)
echo.
echo ============================================================
if "%FINAL_OK%"=="1" (
    echo  TODO INICIADO CORRECTAMENTE
    echo  4 nodos + 16 servidores + 4 calculadoras en marcha.
) else (
    echo  INICIO PARCIAL: algun nodo no respondio.
    echo  Revisa sus ventanas; puedes reintentar con DETENER_PROYECTO.bat
    echo  y luego INICIAR_PROYECTO.bat de nuevo.
)
echo  Para detener todo:  DETENER_PROYECTO.bat
echo ============================================================
goto :fin_pausa

REM ============================================================
:fin_error
echo.
echo El inicio se detuvo por un error. Corrige lo indicado y reintenta.
:fin_pausa
echo.
pause
exit /b 0

REM ------------------------------------------------------------
REM  :puerto_ocupado <puerto>  -> PORT_BUSY=1 si esta escuchando
REM ------------------------------------------------------------
:puerto_ocupado
set "PORT_BUSY=0"
for /f "tokens=*" %%L in ('netstat -ano -p tcp ^| findstr /r /c:":%~1 .*LISTENING"') do set "PORT_BUSY=1"
goto :eof

REM ------------------------------------------------------------
REM  :esperar_puerto <puerto>  -> espera ~20s. exit /b 0 si OK, 1 si no.
REM ------------------------------------------------------------
:esperar_puerto
set "P=%~1"
set /a TRIES=0
:esperar_loop
call :puerto_ocupado %P%
if "%PORT_BUSY%"=="1" (
    echo       Nodo %P%: LISTO
    exit /b 0
)
set /a TRIES+=1
if %TRIES% geq 20 (
    echo       Nodo %P%: sin respuesta tras ~20s
    exit /b 1
)
call :pausa 1
goto :esperar_loop

REM ------------------------------------------------------------
REM  :pausa <segundos>  (robusta: timeout o ping)
REM ------------------------------------------------------------
:pausa
set "SEGS=%~1"
timeout /t %SEGS% /nobreak >nul 2>&1
if errorlevel 1 (
    set /a PINGS=%SEGS%+1
    ping -n !PINGS! 127.0.0.1 >nul 2>&1
)
goto :eof
