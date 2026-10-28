package reservas.web;

import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import reservas.SistemaReservas;

/**
 * Interfaz web del sistema de reservas. Levanta un servidor HTTP (el que viene incluido en el JDK,
 * sin librerías externas) que:
 * - sirve la página de la carpeta web/ (HTML, CSS y JavaScript),
 * - expone en /api/ las operaciones del sistema para que la página las use.
 * Al iniciar, abre la página en el navegador.
 *
 * Uso, desde la carpeta del proyecto:  java -cp out reservas.web.ServidorWeb [puerto] [--sin-navegador]
 * (o doble clic en iniciar-windows.bat / iniciar-mac.command, que además compilan).
 */
public class ServidorWeb {

    private static final int PUERTO_POR_DEFECTO = 8080;
    /** Si el puerto está ocupado se prueban los siguientes, hasta esta cantidad. */
    private static final int PUERTOS_A_PROBAR = 10;

    public static void main(String[] args) throws IOException {
        int puerto = PUERTO_POR_DEFECTO;
        boolean abrirNavegador = true;
        for (String argumento : args) {
            if (argumento.equals("--sin-navegador")) {
                abrirNavegador = false;
            } else {
                try {
                    puerto = Integer.parseInt(argumento);
                } catch (NumberFormatException e) {
                    System.err.println("Argumento no reconocido: " + argumento);
                    System.err.println("Uso: java -cp out reservas.web.ServidorWeb [puerto] [--sin-navegador]");
                    System.exit(1);
                }
            }
        }

        Path carpetaWeb = Paths.get("web").toAbsolutePath().normalize();
        if (!Files.isRegularFile(carpetaWeb.resolve("index.html"))) {
            System.err.println("No se encontró " + carpetaWeb.resolve("index.html") + ".");
            System.err.println("Ejecute el servidor desde la carpeta del proyecto (la que contiene web/ y src/).");
            System.exit(1);
        }

        // Para que los montos del detalle de cada reserva se escriban como en Argentina (1.234,50).
        Locale.setDefault(Locale.forLanguageTag("es-AR"));

        HttpServer servidor = crearServidor(puerto);
        if (servidor == null) {
            System.err.println("Los puertos " + puerto + " a " + (puerto + PUERTOS_A_PROBAR - 1) + " están ocupados.");
            System.err.println("Cierre otros programas o elija otro puerto: java -cp out reservas.web.ServidorWeb 9000");
            System.exit(1);
            return;
        }
        int puertoUsado = servidor.getAddress().getPort();

        SistemaReservas sistema = SistemaReservas.conDatosDePrueba();
        // Sin executor propio, el servidor atiende los pedidos de a uno por vez,
        // así que el dominio no necesita sincronización.
        servidor.createContext("/api/", new ApiReservas(sistema));
        servidor.createContext("/", new ArchivosEstaticos(carpetaWeb));
        servidor.start();

        String direccion = "http://localhost:" + puertoUsado;
        if (puertoUsado != puerto) {
            System.out.println("El puerto " + puerto + " estaba ocupado; se usa el " + puertoUsado + ".");
        }
        System.out.println("Sistema de reservas en " + direccion);
        System.out.println("Usuario de prueba: demo / demo123");
        if (!(abrirNavegador && abrirEnNavegador(direccion))) {
            System.out.println("Abra " + direccion + " en el navegador.");
        }
        System.out.println("Para detener el servidor, cierre esta ventana o presione Ctrl+C.");
    }

    /**
     * Crea el servidor en el primer puerto libre a partir del indicado, o devuelve null si no hay ninguno.
     * Solo escucha en esta computadora (localhost): no queda expuesto a la red.
     */
    private static HttpServer crearServidor(int puertoInicial) throws IOException {
        for (int puerto = puertoInicial; puerto < puertoInicial + PUERTOS_A_PROBAR; puerto++) {
            try {
                return HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), puerto), 0);
            } catch (BindException e) {
                // Ocupado: se prueba el siguiente.
            }
        }
        return null;
    }

    /** Abre la dirección en el navegador predeterminado. Devuelve false si el sistema no lo permite. */
    private static boolean abrirEnNavegador(String direccion) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(direccion));
                return true;
            }
        } catch (Exception e) {
            // Si no se puede abrir solo, se le indica al usuario que lo abra a mano.
        }
        return false;
    }
}
