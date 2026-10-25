@echo off
setlocal enabledelayedexpansion
REM ============================================================
REM  0_compilar.bat
REM  Compila TODO el proyecto ADS (src\**\*.java) en la carpeta out\.
REM
REM  - Detecta el JDK de forma robusta (JAVA_HOME, PATH y rutas
REM    estandar de instalacion) mediante scripts\_buscar_java.bat.
REM  - Funciona aunque la ruta tenga espacios o parentesis.
REM  - Se puede ejecutar por doble clic desde cualquier ubicacion.
REM  - Comprueba el codigo de salida REAL de javac: NO muestra
REM    "Compilacion correcta" si hubo cualquier error.
REM
REM  Parametro opcional: /nopause  (para llamadas desde otros scripts)
REM ============================================================

REM Respetar /nopause si viene como argumento.
set "NOPAUSE=0"
if /i "%~1"=="/nopause" set "NOPAUSE=1"

REM Raiz del proyecto = carpeta padre de este script.
cd /d "%~dp0.."

echo ============================================================
echo  Compilando codigo fuente ADS
echo  Carpeta del proyecto: "%CD%"
echo ============================================================

REM ---- 1) Detectar el JDK (necesitamos javac) ----------------
call "%~dp0_buscar_java.bat"

if not defined JAVAC_EXE (
    if defined JAVA_EXE (
        echo.
        echo ERROR: se encontro Java ^(JRE^) pero NO el compilador 'javac'.
        echo        Java detectado: "%JAVA_EXE%"
        echo        Para compilar necesitas el JDK ^(no solo el JRE^).
    ) else (
        echo.
        echo ERROR: no se encontro ningun Java ^(ni JDK ni JRE^) utilizable.
    )
    echo.
    echo   - Instala el JDK 21 ^(Microsoft OpenJDK o Temurin/Adoptium^).
    echo   - O define JAVA_HOME apuntando a la carpeta del JDK.
    echo   - Verifica en una terminal NUEVA:  javac -version
    echo.
    set "RC=1"
    goto :salir
)

echo Compilador detectado: "%JAVAC_EXE%"

REM ---- 2) Comprobar carpeta src ------------------------------
if not exist "src" (
    echo.
    echo ERROR: no se encuentra la carpeta 'src' en "%CD%".
    echo Coloca este script dentro de la carpeta 'scripts' del proyecto.
    echo.
    set "RC=1"
    goto :salir
)

REM ---- 3) Listar fuentes, CADA ruta ENTRECOMILLADA -----------
REM  (asi javac no se rompe con espacios ni parentesis en la ruta)
set "SRCLIST=%TEMP%\ads_sources_%RANDOM%.txt"
if exist "%SRCLIST%" del "%SRCLIST%"
set "NSRC=0"
for /r "src" %%F in (*.java) do (
    REM javac @argfile interpreta la barra invertida como escape.
    REM Convertirla a / para conservar las rutas de Windows.
    set "JAVA_SOURCE=%%~fF"
    set "JAVA_SOURCE=!JAVA_SOURCE:\=/!"
    >> "%SRCLIST%" echo "!JAVA_SOURCE!"
    set /a NSRC+=1
)

if "%NSRC%"=="0" (
    echo.
    echo ERROR: no se encontro ningun archivo .java dentro de 'src'.
    echo.
    if exist "%SRCLIST%" del "%SRCLIST%"
    set "RC=1"
    goto :salir
)
echo Archivos .java encontrados: %NSRC%

REM ---- 4) Compilar y capturar el codigo de salida REAL -------
if not exist "out" mkdir "out"
"%JAVAC_EXE%" -d "out" @"%SRCLIST%"
set "JAVAC_EXIT=%ERRORLEVEL%"

REM ---- 5) Limpiar temporal -----------------------------------
if exist "%SRCLIST%" del "%SRCLIST%"

REM ---- 6) Informar segun el resultado REAL -------------------
echo.
if "%JAVAC_EXIT%"=="0" (
    echo ============================================================
    echo  Compilacion correcta. Clases en: "%CD%\out"
    echo ============================================================
) else (
    echo ============================================================
    echo  ERROR de compilacion ^(codigo %JAVAC_EXIT%^).
    echo  Revisa los mensajes de arriba. NO se completo la compilacion.
    echo ============================================================
)
echo.
set "RC=%JAVAC_EXIT%"
goto :salir

REM ------------------------------------------------------------
:salir
if "%NOPAUSE%"=="1" exit /b %RC%
pause
exit /b %RC%
