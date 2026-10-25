@echo off
REM ============================================================
REM  _buscar_java.bat  -  Utilidad interna (NO ejecutar por doble clic)
REM ------------------------------------------------------------
REM  Detecta de forma robusta el JDK/JRE y deja en el entorno del
REM  script que la invoca estas variables:
REM     JAVA_EXE   -> ruta completa a java.exe   (siempre, si hay JDK o JRE)
REM     JAVAC_EXE  -> ruta completa a javac.exe  (solo si hay JDK)
REM     JAVA_BIN   -> carpeta 'bin' del Java detectado
REM
REM  Orden de deteccion (sin modificar variables del usuario):
REM     1) JAVA_HOME\bin
REM     2) 'where java' / 'where javac' (PATH actual)
REM     3) Rutas estandar de instalacion de JDK (Microsoft, Adoptium,
REM        Oracle/Java, Amazon Corretto, Zulu) en Program Files y (x86)
REM
REM  Uso (desde otro .bat):
REM     call "%~dp0_buscar_java.bat"
REM     if not defined JAVA_EXE  (... no hay java ...)
REM     if not defined JAVAC_EXE (... hay java pero no javac: es JRE ...)
REM
REM  Verifica ademas que java/javac realmente se EJECUTEN (no solo que
REM  exista el archivo), para evitar falsos positivos.
REM ============================================================

setlocal enabledelayedexpansion
set "JAVA_EXE="
set "JAVAC_EXE="
set "JAVA_BIN="

REM ---- 1) JAVA_HOME -------------------------------------------------
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe"  set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
    if exist "%JAVA_HOME%\bin\javac.exe" set "JAVAC_EXE=%JAVA_HOME%\bin\javac.exe"
)

REM ---- 2) PATH (where) ---------------------------------------------
if not defined JAVA_EXE (
    for /f "delims=" %%P in ('where java 2^>nul') do (
        if not defined JAVA_EXE set "JAVA_EXE=%%P"
    )
)
if not defined JAVAC_EXE (
    for /f "delims=" %%P in ('where javac 2^>nul') do (
        if not defined JAVAC_EXE set "JAVAC_EXE=%%P"
    )
)

REM ---- 3) Rutas estandar de instalacion ----------------------------
REM  Si falta java.exe O falta javac.exe, barremos las carpetas tipicas
REM  buscando una instalacion que, de preferencia, sea un JDK (con javac).
if not defined JAVAC_EXE call :barrer "%ProgramFiles%\Microsoft"
if not defined JAVAC_EXE call :barrer "%ProgramFiles%\Eclipse Adoptium"
if not defined JAVAC_EXE call :barrer "%ProgramFiles%\Java"
if not defined JAVAC_EXE call :barrer "%ProgramFiles%\Amazon Corretto"
if not defined JAVAC_EXE call :barrer "%ProgramFiles%\Zulu"
if not defined JAVAC_EXE call :barrer "%ProgramFiles(x86)%\Java"

REM  Si aun no hay java.exe, intentar al menos con un JRE estandar.
if not defined JAVA_EXE call :barrer_java "%ProgramFiles%\Java"
if not defined JAVA_EXE call :barrer_java "%ProgramFiles(x86)%\Java"

REM ---- Verificacion de ejecucion real ------------------------------
if defined JAVA_EXE (
    "%JAVA_EXE%" -version >nul 2>&1
    if errorlevel 1 set "JAVA_EXE="
)
if defined JAVAC_EXE (
    "%JAVAC_EXE%" -version >nul 2>&1
    if errorlevel 1 set "JAVAC_EXE="
)

REM  Si tenemos javac pero no java (raro), deducir java junto a javac.
if defined JAVAC_EXE if not defined JAVA_EXE (
    for %%J in ("%JAVAC_EXE%") do if exist "%%~dpJjava.exe" set "JAVA_EXE=%%~dpJjava.exe"
)

REM  Calcular carpeta bin a partir de java.exe (preferente) o javac.exe.
if defined JAVA_EXE  for %%J in ("%JAVA_EXE%")  do set "JAVA_BIN=%%~dpJ"
if not defined JAVA_BIN if defined JAVAC_EXE for %%J in ("%JAVAC_EXE%") do set "JAVA_BIN=%%~dpJ"
if defined JAVA_BIN if "%JAVA_BIN:~-1%"=="\" set "JAVA_BIN=%JAVA_BIN:~0,-1%"

REM ---- Exportar al entorno del script que nos llamo ----------------
endlocal & (
    set "JAVA_EXE=%JAVA_EXE%"
    set "JAVAC_EXE=%JAVAC_EXE%"
    set "JAVA_BIN=%JAVA_BIN%"
)
goto :eof

REM ============================================================
REM  :barrer  base
REM  Busca un JDK (con javac.exe) un nivel bajo 'base'. Si lo
REM  encuentra, fija JAVAC_EXE y, si falta, tambien JAVA_EXE.
REM ============================================================
:barrer
if not exist "%~1" goto :eof
for /d %%D in ("%~1\*") do (
    if not defined JAVAC_EXE if exist "%%D\bin\javac.exe" (
        set "JAVAC_EXE=%%D\bin\javac.exe"
        if exist "%%D\bin\java.exe" if not defined JAVA_EXE set "JAVA_EXE=%%D\bin\java.exe"
    )
)
goto :eof

REM ============================================================
REM  :barrer_java  base
REM  Busca cualquier java.exe (JDK o JRE) un nivel bajo 'base'.
REM ============================================================
:barrer_java
if not exist "%~1" goto :eof
for /d %%D in ("%~1\*") do (
    if not defined JAVA_EXE if exist "%%D\bin\java.exe" set "JAVA_EXE=%%D\bin\java.exe"
)
goto :eof
