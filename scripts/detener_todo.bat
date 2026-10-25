@echo off
setlocal enabledelayedexpansion
REM ============================================================
REM  detener_todo.bat
REM  Detiene SOLO los componentes ADS lanzados por este proyecto
REM  (nodos, servidores y GUIs), cerrando sus ventanas POR TITULO.
REM
REM  NO afecta a otros programas Java del equipo: el filtro es por
REM  el titulo exacto de las ventanas que crean nuestros scripts
REM  ("NODO N*", "SRV S*", "GUI G*").
REM ============================================================

set "TK=%SystemRoot%\System32\taskkill.exe"

echo Cerrando ventanas de NODOS...
for %%T in ("NODO N1*" "NODO N2*" "NODO N3*" "NODO N4*") do "%TK%" /FI "WINDOWTITLE eq %%~T" /T /F >nul 2>&1

echo Cerrando ventanas de SERVIDORES...
for %%T in ("SRV S1*" "SRV S2*" "SRV S3*" "SRV S4*") do "%TK%" /FI "WINDOWTITLE eq %%~T" /T /F >nul 2>&1

echo Cerrando ventanas de GUIs...
for %%T in ("GUI G1*" "GUI G2*" "GUI G3*" "GUI G4*") do "%TK%" /FI "WINDOWTITLE eq %%~T" /T /F >nul 2>&1

echo.
echo Componentes ADS detenidos (los lanzados con los scripts incluidos).
echo Si algun componente se lanzo de otra forma, cierralo desde su ventana.

REM  Si se llamo desde DETENER_PROYECTO.bat, no pausar dos veces.
if /i "%~1"=="/nopause" exit /b 0
pause
