@echo off
setlocal
REM ============================================================
REM  DETENER_PROYECTO.bat   (doble clic para detener TODO)
REM ------------------------------------------------------------
REM  Cierra UNICAMENTE los procesos de este proyecto (nodos,
REM  servidores y calculadoras), identificados por el TITULO de
REM  sus ventanas. NO cierra otros programas Java del equipo.
REM ============================================================
title DETENER PROYECTO ADS
cd /d "%~dp0"

echo ============================================================
echo   CALCULADORA DISTRIBUIDA (ADS) - DETENER SISTEMA
echo ============================================================
echo.
echo Cerrando solo las ventanas del proyecto (NODO*, SRV*, GUI*)...
echo.

call "scripts\detener_todo.bat" /nopause

echo.
echo Listo. Si alguna ventana sigue abierta, cierrala manualmente.
echo ============================================================
pause
exit /b 0
