package reservas.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Sirve los archivos de la carpeta web/ (la página, sus estilos y su JavaScript). */
class ArchivosEstaticos implements HttpHandler {

    private static final Map<String, String> TIPOS_DE_CONTENIDO = new HashMap<>();

    static {
        TIPOS_DE_CONTENIDO.put("html", "text/html; charset=utf-8");
        TIPOS_DE_CONTENIDO.put("css", "text/css; charset=utf-8");
        TIPOS_DE_CONTENIDO.put("js", "text/javascript; charset=utf-8");
        TIPOS_DE_CONTENIDO.put("svg", "image/svg+xml");
        TIPOS_DE_CONTENIDO.put("png", "image/png");
        TIPOS_DE_CONTENIDO.put("ico", "image/x-icon");
    }

    private final Path carpeta;

    ArchivosEstaticos(Path carpeta) {
        this.carpeta = carpeta;
    }

    @Override
    public void handle(HttpExchange intercambio) throws IOException {
        try {
            String metodo = intercambio.getRequestMethod();
            if (!metodo.equals("GET") && !metodo.equals("HEAD")) {
                responderTexto(intercambio, 405, "Método no permitido.");
                return;
            }

            Path archivo = resolver(intercambio.getRequestURI().getPath());
            if (archivo == null) {
                responderTexto(intercambio, 404, "No se encontró la página.");
                return;
            }

            byte[] contenido = Files.readAllBytes(archivo);
            intercambio.getResponseHeaders().set("Content-Type", tipoDeContenido(archivo));
            intercambio.getResponseHeaders().set("Cache-Control", "no-cache");
            intercambio.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            if (metodo.equals("HEAD")) {
                intercambio.sendResponseHeaders(200, -1);
            } else {
                intercambio.sendResponseHeaders(200, contenido.length);
                try (OutputStream salida = intercambio.getResponseBody()) {
                    salida.write(contenido);
                }
            }
        } finally {
            intercambio.close();
        }
    }

    /** Devuelve el archivo pedido, o null si no existe o queda fuera de la carpeta web/ (por ejemplo, con "../"). */
    private Path resolver(String ruta) {
        if (ruta.equals("/")) {
            ruta = "/index.html";
        }
        try {
            Path archivo = carpeta.resolve(ruta.substring(1)).normalize();
            if (!archivo.startsWith(carpeta) || !Files.isRegularFile(archivo)) {
                return null;
            }
            return archivo;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static String tipoDeContenido(Path archivo) {
        String nombre = archivo.getFileName().toString();
        String extension = nombre.substring(nombre.lastIndexOf('.') + 1).toLowerCase();
        String tipo = TIPOS_DE_CONTENIDO.get(extension);
        return tipo != null ? tipo : "application/octet-stream";
    }

    private static void responderTexto(HttpExchange intercambio, int estado, String texto) throws IOException {
        byte[] bytes = texto.getBytes(StandardCharsets.UTF_8);
        intercambio.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        intercambio.sendResponseHeaders(estado, bytes.length);
        try (OutputStream salida = intercambio.getResponseBody()) {
            salida.write(bytes);
        }
    }
}
