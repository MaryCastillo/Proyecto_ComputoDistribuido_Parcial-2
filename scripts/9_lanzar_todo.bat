@echo off
setlocal enabledelayedexpansion
REM ============================================================
REM  9_lanzar_todo.bat  -  LANZADOR COMPLETO de la config minima ADS
REM     - 4 nodos en malla completa (N1..N4)
REM     - 16 celulas servidor (SUMA/RESTA/MULT/DIV en cada nodo)
REM     - 4 celulas GUI (una por nodo)
REM
REM  Detecta Java de forma robusta, evita procesos duplicados y
REM  espera a que los nodos esten realmente escuchando antes de
REM  arrancar servidores y GUIs. Funciona con espacios/parentesis.
REM ============================================================
cd /d "%~dp0.."

REM ---- Detectar Java -----------------------------------------
call "%~dp0_buscar_java.bat"
if not defined JAVA_EXE (
    echo ERROR: no se encontro Java ^(JDK/JRE 21^) en el sistema.
    echo Instala el JDK 21 o define JAVA_HOME y vuelve a intentar.
    pause
    exit /b 1
)

REM ---- Verificar compilacion ---------------------------------
if not exist "out\node\Node.class" (
    echo ERROR: no se encuentra la carpeta out\ compilada.
    echo Ejecuta primero scripts\0_compilar.bat
    pause
    exit /b 1
)

set "SH=%SystemRoot%\System32\cmd.exe"

REM ---- Anti-duplicados: si el puerto 6001 ya escucha, avisar --
call :puerto_ocupado 6001
if "%PORT_BUSY%"=="1" (
    echo ============================================================
    echo  AVISO: el puerto 6001 ya esta en uso. Parece que el sistema
    echo  ADS ya esta en ejecucion. No se lanzaran procesos duplicados.
    echo  Si quieres reiniciar, ejecuta primero DETENER_PROYECTO.bat
    echo ============================================================
    pause
    exit /b 2
)

echo [1/3] Arrancando los 4 nodos del Campo de Datos (malla completa)...
start "NODO N1 (6001)" "%SH%" /k ""%JAVA_EXE%" -cp "out" node.Node N1 6001 localhost:6002 localhost:6003 localhost:6004"
start "NODO N2 (6002)" "%SH%" /k ""%JAVA_EXE%" -cp "out" node.Node N2 6002 localhost:6001 localhost:6003 localhost:6004"
start "NODO N3 (6003)" "%SH%" /k ""%JAVA_EXE%" -cp "out" node.Node N3 6003 localhost:6001 localhost:6002 localhost:6004"
start "NODO N4 (6004)" "%SH%" /k ""%JAVA_EXE%" -cp "out" node.Node N4 6004 localhost:6001 localhost:6002 localhost:6003"

echo Esperando a que los 4 nodos escuchen (6001-6004)...
call :esperar_puerto 6001
call :esperar_puerto 6002
call :esperar_puerto 6003
call :esperar_puerto 6004

echo [2/3] Arrancando las 16 celulas servidor...
call "%~dp02_servidores_nodo1.bat"
call "%~dp02_servidores_nodo2.bat"
call "%~dp02_servidores_nodo3.bat"
call "%~dp02_servidores_nodo4.bat"

echo Esperando a que los servidores se registren (presencia)...
call :pausa 5

echo [3/3] Arrancando las 4 celulas GUI...
start "GUI G1 (N1)" "%SH%" /k ""%JAVA_EXE%" -cp "out" client.CalculatorClient G1 localhost 6001"
start "GUI G2 (N2)" "%SH%" /k ""%JAVA_EXE%" -cp "out" client.CalculatorClient G2 localhost 6002"
start "GUI G3 (N3)" "%SH%" /k ""%JAVA_EXE%" -cp "out" client.CalculatorClient G3 localhost 6003"
start "GUI G4 (N4)" "%SH%" /k ""%JAVA_EXE%" -cp "out" client.CalculatorClient G4 localhost 6004"

echo.
echo ============================================================
echo  Sistema ADS completo lanzado: 4 nodos + 16 servidores + 4 GUIs.
echo  Para detener todo: DETENER_PROYECTO.bat (o scripts\detener_todo.bat)
echo ============================================================
pause
exit /b 0

REM ------------------------------------------------------------
REM  :puerto_ocupado  <puerto>   -> define PORT_BUSY=1 si escucha
REM ------------------------------------------------------------
:puerto_ocupado
set "PORT_BUSY=0"
for /f "tokens=*" %%L in ('netstat -ano -p tcp ^| findstr /r /c:":%~1 .*LISTENING"') do set "PORT_BUSY=1"
goto :eof

REM ------------------------------------------------------------
REM  :esperar_puerto <puerto>  -> espera (max ~20s) a que escuche
REM ------------------------------------------------------------
:esperar_puerto
set "P=%~1"
set /a TRIES=0
:esperar_loop
call :puerto_ocupado %P%
if "%PORT_BUSY%"=="1" (
    echo   - Nodo en puerto %P%: LISTO.
    goto :eof
)
set /a TRIES+=1
if %TRIES% geq 20 (
    echo   - AVISO: el nodo en puerto %P% no respondio a tiempo. Continuo igual.
    goto :eof
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
