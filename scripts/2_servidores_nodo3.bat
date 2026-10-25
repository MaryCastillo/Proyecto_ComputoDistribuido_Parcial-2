@echo off
setlocal
REM Levanta las 4 celulas servidor conectadas al Nodo 3 (puerto 6003).
cd /d "%~dp0.."
call "%~dp0_buscar_java.bat"
if not defined JAVA_EXE (echo ERROR: no se encontro Java ^(JDK/JRE 21^). Instala el JDK 21 o define JAVA_HOME.& pause & exit /b 1)
if not exist "out\server\CalculationServer.class" (echo ERROR: falta compilar. Ejecuta primero scripts\0_compilar.bat & pause & exit /b 1)
set "SH=%SystemRoot%\System32\cmd.exe"
start "SRV S3-SUMA (N3)"  "%SH%" /k ""%JAVA_EXE%" -cp "out" server.CalculationServer S3-SUMA  SUMA  localhost 6003"
start "SRV S3-RESTA (N3)" "%SH%" /k ""%JAVA_EXE%" -cp "out" server.CalculationServer S3-RESTA RESTA localhost 6003"
start "SRV S3-MULT (N3)"  "%SH%" /k ""%JAVA_EXE%" -cp "out" server.CalculationServer S3-MULT  MULT  localhost 6003"
start "SRV S3-DIV (N3)"   "%SH%" /k ""%JAVA_EXE%" -cp "out" server.CalculationServer S3-DIV   DIV   localhost 6003"
echo Servidores del Nodo 3 lanzados.
