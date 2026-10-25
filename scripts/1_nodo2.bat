@echo off
setlocal
REM Nodo 2 del Campo de Datos (puerto 6002), enlazado en malla con N1, N3 y N4.
cd /d "%~dp0.."
call "%~dp0_buscar_java.bat"
if not defined JAVA_EXE (echo ERROR: no se encontro Java ^(JDK/JRE 21^). Instala el JDK 21 o define JAVA_HOME.& pause & exit /b 1)
if not exist "out\node\Node.class" (echo ERROR: falta compilar. Ejecuta primero scripts\0_compilar.bat & pause & exit /b 1)
title NODO N2 (6002)
"%JAVA_EXE%" -cp "out" node.Node N2 6002 localhost:6001 localhost:6003 localhost:6004
pause
