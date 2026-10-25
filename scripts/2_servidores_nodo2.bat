@echo off
setlocal
REM Levanta las 4 celulas servidor conectadas al Nodo 2 (puerto 6002).
cd /d "%~dp0.."
call "%~dp0_buscar_java.bat"
if not defined JAVA_EXE (echo ERROR: no se encontro Java ^(JDK/JRE 21^). Instala el JDK 21 o define JAVA_HOME.& pause & exit /b 1)
if not exist "out\server\CalculationServer.class" (echo ERROR: falta compilar. Ejecuta primero scripts\0_compilar.bat & pause & exit /b 1)
set "SH=%SystemRoot%\System32\cmd.exe"
start "SRV S2-SUMA (N2)"  "%SH%" /k ""%JAVA_EXE%" -cp "out" server.CalculationServer S2-SUMA  SUMA  localhost 6002"
start "SRV S2-RESTA (N2)" "%SH%" /k ""%JAVA_EXE%" -cp "out" server.CalculationServer S2-RESTA RESTA localhost 6002"
start "SRV S2-MULT (N2)"  "%SH%" /k ""%JAVA_EXE%" -cp "out" server.CalculationServer S2-MULT  MULT  localhost 6002"
start "SRV S2-DIV (N2)"   "%SH%" /k ""%JAVA_EXE%" -cp "out" server.CalculationServer S2-DIV   DIV   localhost 6002"
echo Servidores del Nodo 2 lanzados.
