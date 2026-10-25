@echo off
setlocal
REM Celula GUI conectada al Nodo 3 (puerto 6003).
cd /d "%~dp0.."
call "%~dp0_buscar_java.bat"
if not defined JAVA_EXE (echo ERROR: no se encontro Java ^(JDK/JRE 21^). Instala el JDK 21 o define JAVA_HOME.& pause & exit /b 1)
if not exist "out\client\CalculatorClient.class" (echo ERROR: falta compilar. Ejecuta primero scripts\0_compilar.bat & pause & exit /b 1)
title GUI G3 (N3)
"%JAVA_EXE%" -cp "out" client.CalculatorClient G3 localhost 6003
