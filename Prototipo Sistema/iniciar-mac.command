#!/bin/bash
# Doble clic (macOS): compila el proyecto, inicia el servidor y abre el sistema en el navegador.
# En Linux: ejecutar "bash iniciar-mac.command" desde una terminal.
# Para detenerlo, cerrar esta ventana o presionar Ctrl+C.

cd "$(dirname "$0")" || exit 1

esperar_y_salir() {
  echo
  read -r -p "Presione Enter para cerrar esta ventana..." _
  exit "$1"
}

if [ ! -f "src/reservas/Main.java" ]; then
  echo "No se encontró el código del proyecto. Descomprima el zip completo y vuelva a intentar."
  esperar_y_salir 1
fi

# En macOS, "javac" existe aunque no haya Java instalado: por eso se prueba que funcione.
if ! javac -version >/dev/null 2>&1; then
  echo "No se encontró Java (JDK) en esta computadora."
  echo "Se abre la página de descarga: instale Temurin 21 (JDK) y vuelva a hacer doble clic en este archivo."
  open "https://adoptium.net/temurin/releases/?version=21" 2>/dev/null \
    || xdg-open "https://adoptium.net/temurin/releases/?version=21" 2>/dev/null
  esperar_y_salir 1
fi

echo "Compilando..."
rm -rf out
if ! javac -encoding UTF-8 -d out src/reservas/*.java src/reservas/web/*.java; then
  echo
  echo "No se pudo compilar. Revise los mensajes de arriba."
  esperar_y_salir 1
fi

echo
java -cp out reservas.web.ServidorWeb "$@"
