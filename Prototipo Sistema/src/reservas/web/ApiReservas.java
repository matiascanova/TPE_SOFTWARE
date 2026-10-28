package reservas.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import reservas.Campo;
import reservas.Reserva;
import reservas.ReservaAvion;
import reservas.ReservaHotel;
import reservas.ReservaTurismo;
import reservas.SistemaReservas;
import reservas.Usuario;

/**
 * API que usa la página web. Traduce cada pedido HTTP a una llamada al dominio y no tiene lógica
 * de negocio propia: las validaciones y los cálculos siguen estando en Reserva, Usuario y SistemaReservas.
 *
 * <pre>
 * POST   /api/registro        nombre, contrasena        crea el usuario e inicia sesión
 * POST   /api/login           nombre, contrasena
 * POST   /api/logout
 * GET    /api/sesion                                    usuario de la sesión actual (o null)
 * GET    /api/reservas                                  reservas del usuario y total
 * POST   /api/reservas        tipo (avion|hotel|turismo) y los datos de ese tipo
 * PUT    /api/reservas/{id}   nombre de campo = nuevo valor (uno o varios)
 * DELETE /api/reservas/{id}
 * </pre>
 *
 * Los datos llegan como formulario (application/x-www-form-urlencoded) y las respuestas son JSON.
 * Los errores responden {"error": "mensaje"}; los de validación usan el mismo mensaje que el dominio.
 */
class ApiReservas implements HttpHandler {

    private static final String COOKIE_SESION = "sesion";
    private static final String RUTA_RESERVA = "/api/reservas/";
    private static final int TAMANIO_MAXIMO_PEDIDO = 64 * 1024;

    private final SistemaReservas sistema;

    /** Sesiones abiertas: token guardado en la cookie del navegador -> usuario. */
    private final Map<String, Usuario> sesiones = new HashMap<>();

    ApiReservas(SistemaReservas sistema) {
        this.sistema = sistema;
    }

    @Override
    public void handle(HttpExchange intercambio) throws IOException {
        try {
            Respuesta respuesta = atender(intercambio);
            responder(intercambio, respuesta.estado, respuesta.cuerpo);
        } catch (ErrorHttp e) {
            responder(intercambio, e.estado, error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            // Datos inválidos: el mensaje viene del dominio y se muestra tal cual en la página.
            responder(intercambio, 400, error(e.getMessage()));
        } catch (RuntimeException e) {
            e.printStackTrace();
            responder(intercambio, 500, error("Error interno del servidor."));
        } finally {
            intercambio.close();
        }
    }

    // ================================================================ rutas

    private Respuesta atender(HttpExchange intercambio) throws IOException {
        String metodo = intercambio.getRequestMethod();
        String ruta = intercambio.getRequestURI().getPath();

        if (ruta.equals("/api/registro")) {
            exigirMetodo(metodo, "POST");
            return registrar(intercambio, leerFormulario(intercambio));
        }
        if (ruta.equals("/api/login")) {
            exigirMetodo(metodo, "POST");
            return iniciarSesion(intercambio, leerFormulario(intercambio));
        }
        if (ruta.equals("/api/logout")) {
            exigirMetodo(metodo, "POST");
            return cerrarSesion(intercambio);
        }
        if (ruta.equals("/api/sesion")) {
            exigirMetodo(metodo, "GET");
            // Responde 200 aunque no haya sesión: la página lo consulta al abrirse para saber qué pantalla mostrar.
            Usuario usuario = buscarUsuarioDeLaSesion(intercambio);
            return Respuesta.ok(mapa("usuario", usuario == null ? null : usuario.getNombreUsuario()));
        }
        if (ruta.equals("/api/reservas")) {
            Usuario usuario = usuarioDeLaSesion(intercambio);
            if (metodo.equals("GET")) {
                return Respuesta.ok(listarReservas(usuario));
            }
            exigirMetodo(metodo, "POST");
            return crearReserva(usuario, leerFormulario(intercambio));
        }
        if (ruta.startsWith(RUTA_RESERVA)) {
            Usuario usuario = usuarioDeLaSesion(intercambio);
            Reserva reserva = buscarReserva(usuario, ruta.substring(RUTA_RESERVA.length()));
            if (metodo.equals("PUT")) {
                reserva.modificar(leerFormulario(intercambio));
                return Respuesta.ok(reservaAJson(reserva));
            }
            exigirMetodo(metodo, "DELETE");
            usuario.eliminarReserva(reserva.getId());
            return Respuesta.ok(mapa("eliminada", reserva.getId()));
        }
        throw new ErrorHttp(404, "No existe la ruta " + ruta + ".");
    }

    // ================================================================ usuarios y sesiones

    private Respuesta registrar(HttpExchange intercambio, Map<String, String> datos) {
        Usuario usuario = sistema.registrar(datos.get("nombre"), datos.get("contrasena"));
        abrirSesion(intercambio, usuario);
        return Respuesta.creado(datosDelUsuario(usuario));
    }

    private Respuesta iniciarSesion(HttpExchange intercambio, Map<String, String> datos) {
        Usuario usuario = sistema.iniciarSesion(datos.get("nombre"), datos.get("contrasena"));
        if (usuario == null) {
            throw new ErrorHttp(401, "Usuario o contraseña incorrectos.");
        }
        abrirSesion(intercambio, usuario);
        return Respuesta.ok(datosDelUsuario(usuario));
    }

    private Respuesta cerrarSesion(HttpExchange intercambio) {
        String token = leerCookie(intercambio, COOKIE_SESION);
        if (token != null) {
            sesiones.remove(token);
        }
        intercambio.getResponseHeaders().add("Set-Cookie", COOKIE_SESION + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
        return Respuesta.ok(mapa("sesionCerrada", true));
    }

    /**
     * La sesión se guarda en una cookie HttpOnly (el JavaScript de la página no puede leerla)
     * y SameSite=Strict (otro sitio no puede usarla para hacer pedidos en nombre del usuario).
     */
    private void abrirSesion(HttpExchange intercambio, Usuario usuario) {
        String token = UUID.randomUUID().toString();
        sesiones.put(token, usuario);
        intercambio.getResponseHeaders().add("Set-Cookie", COOKIE_SESION + "=" + token + "; Path=/; HttpOnly; SameSite=Strict");
    }

    private Usuario buscarUsuarioDeLaSesion(HttpExchange intercambio) {
        String token = leerCookie(intercambio, COOKIE_SESION);
        return token == null ? null : sesiones.get(token);
    }

    private Usuario usuarioDeLaSesion(HttpExchange intercambio) {
        Usuario usuario = buscarUsuarioDeLaSesion(intercambio);
        if (usuario == null) {
            throw new ErrorHttp(401, "La sesión no está iniciada o expiró. Inicie sesión de nuevo.");
        }
        return usuario;
    }

    private static Map<String, Object> datosDelUsuario(Usuario usuario) {
        return mapa("usuario", usuario.getNombreUsuario());
    }

    // ================================================================ reservas

    private static Map<String, Object> listarReservas(Usuario usuario) {
        List<Object> reservas = new ArrayList<>();
        for (Reserva reserva : usuario.getReservas()) {
            reservas.add(reservaAJson(reserva));
        }
        Map<String, Object> respuesta = datosDelUsuario(usuario);
        respuesta.put("reservas", reservas);
        respuesta.put("total", usuario.calcularTotalReservas());
        return respuesta;
    }

    /** El alta es lo único que necesita conocer los tipos concretos, igual que en la consola. */
    private static Respuesta crearReserva(Usuario usuario, Map<String, String> datos) {
        String tipo = datos.get("tipo");
        if (tipo == null) {
            throw new IllegalArgumentException("Elija el tipo de reserva.");
        }
        Reserva reserva;
        switch (tipo) {
            case "avion":
                reserva = new ReservaAvion(datos.get("codigo"),
                        fecha(datos, "inicio", "Fecha de salida"), fecha(datos, "fin", "Fecha de llegada"),
                        datos.get("aerolinea"), datos.get("vuelo"), datos.get("origen"), datos.get("destino"),
                        entero(datos, "cantidad", "Pasajeros"), monto(datos, "precio", "Precio por pasajero"));
                break;
            case "hotel":
                reserva = new ReservaHotel(datos.get("codigo"),
                        fecha(datos, "inicio", "Check-in"), fecha(datos, "fin", "Check-out"),
                        datos.get("hotel"), datos.get("ciudad"),
                        entero(datos, "cantidad", "Habitaciones"), monto(datos, "precio", "Precio por noche"));
                break;
            case "turismo":
                reserva = new ReservaTurismo(datos.get("codigo"),
                        fecha(datos, "inicio", "Fecha de inicio"), fecha(datos, "fin", "Fecha de fin"),
                        datos.get("actividad"), datos.get("lugar"),
                        entero(datos, "cantidad", "Personas"), monto(datos, "precio", "Precio por persona"));
                break;
            default:
                throw new IllegalArgumentException("Tipo de reserva desconocido: " + tipo + ".");
        }
        usuario.agregarReserva(reserva);
        return Respuesta.creado(reservaAJson(reserva));
    }

    /** Solo encuentra reservas del usuario de la sesión: no se puede tocar la reserva de otro. */
    private static Reserva buscarReserva(Usuario usuario, String textoId) {
        try {
            Reserva reserva = usuario.buscarReserva(Integer.parseInt(textoId));
            if (reserva != null) {
                return reserva;
            }
        } catch (NumberFormatException e) {
            // Se responde igual que si no existiera.
        }
        throw new ErrorHttp(404, "No tiene ninguna reserva con el ID " + textoId + ".");
    }

    /**
     * Forma de una reserva en JSON. Incluye sus campos modificables con su tipo de dato,
     * así la página arma el formulario de edición sin saber de qué tipo de reserva se trata.
     */
    private static Map<String, Object> reservaAJson(Reserva reserva) {
        List<Object> campos = new ArrayList<>();
        for (Campo campo : reserva.getCamposModificables()) {
            Map<String, Object> datosCampo = mapa("nombre", campo.getNombre());
            datosCampo.put("tipo", campo.getTipo().name());
            datosCampo.put("valor", campo.getValor());
            campos.add(datosCampo);
        }
        Map<String, Object> json = mapa("id", reserva.getId());
        json.put("tipo", reserva.getTipo());
        json.put("codigo", reserva.getCodigoConfirmacion());
        json.put("fechaInicio", reserva.getFechaInicio().toString()); // aaaa-mm-dd
        json.put("fechaFin", reserva.getFechaFin().toString());
        json.put("detalle", reserva.getDetalle());
        json.put("total", reserva.calcularTotal());
        json.put("campos", campos);
        return json;
    }

    // ================================================================ conversión de datos del formulario

    private static String obligatorio(Map<String, String> datos, String clave, String nombre) {
        String valor = datos.get(clave);
        if (valor == null || valor.trim().isEmpty()) {
            throw new IllegalArgumentException("El campo \"" + nombre + "\" es obligatorio.");
        }
        return valor.trim();
    }

    private static LocalDate fecha(Map<String, String> datos, String clave, String nombre) {
        return Reserva.parsearFecha(obligatorio(datos, clave, nombre));
    }

    private static int entero(Map<String, String> datos, String clave, String nombre) {
        try {
            return Integer.parseInt(obligatorio(datos, clave, nombre));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("El campo \"" + nombre + "\" debe ser un número entero.");
        }
    }

    private static double monto(Map<String, String> datos, String clave, String nombre) {
        try {
            return Double.parseDouble(obligatorio(datos, clave, nombre).replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("El campo \"" + nombre + "\" debe ser un número.");
        }
    }

    // ================================================================ HTTP

    private static void exigirMetodo(String metodo, String esperado) {
        if (!metodo.equals(esperado)) {
            throw new ErrorHttp(405, "Método " + metodo + " no permitido en esta ruta.");
        }
    }

    private static Map<String, String> leerFormulario(HttpExchange intercambio) throws IOException {
        String cuerpo = new String(leerCuerpo(intercambio.getRequestBody()), StandardCharsets.UTF_8);
        Map<String, String> datos = new LinkedHashMap<>();
        for (String par : cuerpo.split("&")) {
            if (par.isEmpty()) {
                continue;
            }
            int igual = par.indexOf('=');
            String clave = decodificar(igual < 0 ? par : par.substring(0, igual));
            String valor = igual < 0 ? "" : decodificar(par.substring(igual + 1));
            datos.put(clave, valor);
        }
        return datos;
    }

    private static byte[] leerCuerpo(InputStream entrada) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] bloque = new byte[4096];
        int leidos;
        while ((leidos = entrada.read(bloque)) != -1) {
            bytes.write(bloque, 0, leidos);
            if (bytes.size() > TAMANIO_MAXIMO_PEDIDO) {
                throw new ErrorHttp(413, "El pedido es demasiado grande.");
            }
        }
        return bytes.toByteArray();
    }

    private static String decodificar(String texto) {
        try {
            return URLDecoder.decode(texto, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e); // UTF-8 siempre está disponible.
        }
    }

    private static String leerCookie(HttpExchange intercambio, String nombre) {
        List<String> encabezados = intercambio.getRequestHeaders().get("Cookie");
        if (encabezados == null) {
            return null;
        }
        for (String encabezado : encabezados) {
            for (String cookie : encabezado.split(";")) {
                String[] partes = cookie.trim().split("=", 2);
                if (partes.length == 2 && partes[0].equals(nombre)) {
                    return partes[1];
                }
            }
        }
        return null;
    }

    private static void responder(HttpExchange intercambio, int estado, Object cuerpo) throws IOException {
        byte[] bytes = Json.escribir(cuerpo).getBytes(StandardCharsets.UTF_8);
        intercambio.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        intercambio.getResponseHeaders().set("Cache-Control", "no-store");
        intercambio.sendResponseHeaders(estado, bytes.length);
        try (OutputStream salida = intercambio.getResponseBody()) {
            salida.write(bytes);
        }
    }

    private static Map<String, Object> error(String mensaje) {
        return mapa("error", mensaje);
    }

    private static Map<String, Object> mapa(String clave, Object valor) {
        Map<String, Object> mapa = new LinkedHashMap<>();
        mapa.put(clave, valor);
        return mapa;
    }

    // ================================================================ tipos auxiliares

    private static final class Respuesta {
        final int estado;
        final Object cuerpo;

        private Respuesta(int estado, Object cuerpo) {
            this.estado = estado;
            this.cuerpo = cuerpo;
        }

        static Respuesta ok(Object cuerpo) {
            return new Respuesta(200, cuerpo);
        }

        static Respuesta creado(Object cuerpo) {
            return new Respuesta(201, cuerpo);
        }
    }

    /** Error con un código HTTP distinto de 400 (sesión no iniciada, no encontrado, etc.). */
    private static final class ErrorHttp extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int estado;

        ErrorHttp(int estado, String mensaje) {
            super(mensaje);
            this.estado = estado;
        }
    }
}
