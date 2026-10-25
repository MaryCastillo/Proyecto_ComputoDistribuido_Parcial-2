@echo off
setlocal
REM Levanta las 4 celulas servidor conectadas al Nodo 4 (puerto 6004).
cd /d "%~dp0.."
call "%~dp0_buscar_java.bat"
if not defined JAVA_EXE (echo ERROR: no se encontro Java ^(JDK/JRE 21^). Instala el JDK 21 o define JAVA_HOME.& pause & exit /b 1)
if not exist "out\server\CalculationServer.class" (echo ERROR: falta compilar. Ejecuta primero scripts\0_compilar.bat & pause & exit /b 1)
set "SH=%SystemRoot%\System32\cmd.exe"
start "SRV S4-SUMA (N4)"  "%SH%" /k ""%JAVA_EXE%" -cp "out" server.CalculationServer S4-SUMA  SUMA  localhost 6004"
start "SRV S4-RESTA (N4)" "%SH%" /k ""%JAVA_EXE%" -cp "out" server.CalculationServer S4-RESTA RESTA localhost 6004"
start "SRV S4-MULT (N4)"  "%SH%" /k ""%JAVA_EXE%" -cp "out" server.CalculationServer S4-MULT  MULT  localhost 6004"
start "SRV S4-DIV (N4)"   "%SH%" /k ""%JAVA_EXE%" -cp "out" server.CalculationServer S4-DIV   DIV   localhost 6004"
echo Servidores del Nodo 4 lanzados.
