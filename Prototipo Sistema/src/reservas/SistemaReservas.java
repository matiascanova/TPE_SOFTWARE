package reservas;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registro de usuarios del sistema: alta e inicio de sesión.
 * Lo usan las dos interfaces (la consola de {@link Main} y la web de {@code reservas.web.ServidorWeb}),
 * así ninguna de las dos repite esta lógica.
 */
public class SistemaReservas {

    /** Usuarios indexados por nombre en minúsculas: el nombre no distingue mayúsculas. */
    private final Map<String, Usuario> usuarios = new LinkedHashMap<>();

    public boolean existeUsuario(String nombreUsuario) {
        return nombreUsuario != null && usuarios.containsKey(clave(nombreUsuario));
    }

    /** Registra un usuario nuevo. Lanza IllegalArgumentException si el nombre ya existe o los datos no son válidos. */
    public Usuario registrar(String nombreUsuario, String contrasena) {
        if (existeUsuario(nombreUsuario)) {
            throw new IllegalArgumentException("Ya existe un usuario con ese nombre.");
        }
        Usuario usuario = new Usuario(nombreUsuario, contrasena);
        usuarios.put(clave(usuario.getNombreUsuario()), usuario);
        return usuario;
    }

    /**
     * Devuelve el usuario si el nombre y la contraseña son correctos, o null si no.
     * No distingue entre "no existe" y "contraseña incorrecta", para no revelar qué usuarios existen.
     */
    public Usuario iniciarSesion(String nombreUsuario, String contrasena) {
        Usuario usuario = nombreUsuario == null ? null : usuarios.get(clave(nombreUsuario));
        if (usuario == null || !usuario.verificarContrasena(contrasena)) {
            return null;
        }
        return usuario;
    }

    private static String clave(String nombreUsuario) {
        return nombreUsuario.trim().toLowerCase();
    }

    /** Sistema con un usuario de prueba (demo / demo123) y tres reservas cargadas. */
    public static SistemaReservas conDatosDePrueba() {
        SistemaReservas sistema = new SistemaReservas();
        Usuario demo = sistema.registrar("demo", "demo123");
        demo.agregarReserva(new ReservaAvion("ABC123",
                LocalDate.of(2026, 12, 15), LocalDate.of(2026, 12, 16),
                "Aerolíneas Argentinas", "AR1132", "Buenos Aires", "Madrid", 2, 950_000));
        demo.agregarReserva(new ReservaHotel("HTL-7781",
                LocalDate.of(2026, 12, 16), LocalDate.of(2026, 12, 20),
                "Hotel Central", "Madrid", 1, 120_000));
        demo.agregarReserva(new ReservaTurismo("TUR-55",
                LocalDate.of(2026, 12, 18), LocalDate.of(2026, 12, 18),
                "Visita guiada al Museo del Prado", "Madrid", 2, 45_000));
        return sistema;
    }
}
