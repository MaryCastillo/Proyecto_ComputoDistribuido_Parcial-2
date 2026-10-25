@echo off
setlocal
REM Levanta las 4 celulas servidor conectadas al Nodo 1 (puerto 6001):
REM   SUMA, RESTA, MULT y DIV. Cada una en su propia ventana.
cd /d "%~dp0.."
call "%~dp0_buscar_java.bat"
if not defined JAVA_EXE (echo ERROR: no se encontro Java ^(JDK/JRE 21^). Instala el JDK 21 o define JAVA_HOME.& pause & exit /b 1)
if not exist "out\server\CalculationServer.class" (echo ERROR: falta compilar. Ejecuta primero scripts\0_compilar.bat & pause & exit /b 1)
set "SH=%SystemRoot%\System32\cmd.exe"
start "SRV S1-SUMA (N1)"  "%SH%" /k ""%JAVA_EXE%" -cp "out" server.CalculationServer S1-SUMA  SUMA  localhost 6001"
start "SRV S1-RESTA (N1)" "%SH%" /k ""%JAVA_EXE%" -cp "out" server.CalculationServer S1-RESTA RESTA localhost 6001"
start "SRV S1-MULT (N1)"  "%SH%" /k ""%JAVA_EXE%" -cp "out" server.CalculationServer S1-MULT  MULT  localhost 6001"
start "SRV S1-DIV (N1)"   "%SH%" /k ""%JAVA_EXE%" -cp "out" server.CalculationServer S1-DIV   DIV   localhost 6001"
echo Servidores del Nodo 1 lanzados.
