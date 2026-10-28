@echo off
rem Doble clic: compila el proyecto, inicia el servidor y abre el sistema en el navegador.
rem Para detenerlo, cerrar esta ventana.
setlocal
chcp 65001 >nul
title Sistema de reservas
cd /d "%~dp0"

if not exist "src\reservas\Main.java" goto :sinDescomprimir

where javac >nul 2>nul
if errorlevel 1 goto :sinJava

echo Compilando...
if exist out rmdir /s /q out
javac -encoding UTF-8 -d out src\reservas\*.java src\reservas\web\*.java
if errorlevel 1 goto :errorCompilacion

echo.
java -cp out reservas.web.ServidorWeb
echo.
pause
exit /b 0

:sinDescomprimir
echo Primero descomprima el zip: clic derecho sobre el archivo .zip, "Extraer todo".
echo Despues haga doble clic en iniciar-windows.bat dentro de la carpeta extraida.
echo.
pause
exit /b 1

:sinJava
echo No se encontro Java (JDK) en esta computadora.
echo Se abre la pagina de descarga: instale Temurin 21 (JDK) y vuelva a hacer doble clic en este archivo.
start "" "https://adoptium.net/temurin/releases/?version=21"
echo.
pause
exit /b 1

:errorCompilacion
echo.
echo No se pudo compilar. Revise los mensajes de arriba.
echo.
pause
exit /b 1
