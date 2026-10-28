package reservas;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Usuario del sistema. Es dueño de sus reservas: solo se accede a ellas a través de él.
 *
 * La contraseña nunca se guarda en texto plano: se guarda un hash PBKDF2 con una sal aleatoria
 * por usuario, y para iniciar sesión se compara el hash de lo ingresado.
 */
public class Usuario {

    private static final int LARGO_MINIMO_CONTRASENA = 6;
    private static final int ITERACIONES_HASH = 100_000;
    private static final int LARGO_HASH_BITS = 256;
    private static final SecureRandom GENERADOR = new SecureRandom();

    private final String nombreUsuario;
    private final byte[] sal;
    private final byte[] hashContrasena;
    private final List<Reserva> reservas = new ArrayList<>();

    public Usuario(String nombreUsuario, String contrasena) {
        if (nombreUsuario == null || nombreUsuario.trim().isEmpty()) {
            throw new IllegalArgumentException("El nombre de usuario es obligatorio.");
        }
        if (nombreUsuario.trim().contains(" ")) {
            throw new IllegalArgumentException("El nombre de usuario no puede tener espacios.");
        }
        if (contrasena == null || contrasena.length() < LARGO_MINIMO_CONTRASENA) {
            throw new IllegalArgumentException(
                    "La contraseña debe tener al menos " + LARGO_MINIMO_CONTRASENA + " caracteres.");
        }
        this.nombreUsuario = nombreUsuario.trim();
        this.sal = new byte[16];
        GENERADOR.nextBytes(this.sal);
        this.hashContrasena = hashear(contrasena, this.sal);
    }

    public String getNombreUsuario() {
        return nombreUsuario;
    }

    public boolean verificarContrasena(String contrasena) {
        if (contrasena == null) {
            return false;
        }
        // Comparación en tiempo constante, para no dar pistas por cuánto tarda.
        return MessageDigest.isEqual(hashContrasena, hashear(contrasena, sal));
    }

    // ---------------------------------------------------------------- gestión de reservas

    public void agregarReserva(Reserva reserva) {
        if (reserva == null) {
            throw new IllegalArgumentException("La reserva no puede ser nula.");
        }
        reservas.add(reserva);
    }

    /** Devuelve la reserva del usuario con ese id, o null si no tiene ninguna con ese id. */
    public Reserva buscarReserva(int id) {
        for (Reserva reserva : reservas) {
            if (reserva.getId() == id) {
                return reserva;
            }
        }
        return null;
    }

    public boolean eliminarReserva(int id) {
        Reserva reserva = buscarReserva(id);
        return reserva != null && reservas.remove(reserva);
    }

    /** Reservas ordenadas por fecha de inicio. La lista es de solo lectura. */
    public List<Reserva> getReservas() {
        List<Reserva> copia = new ArrayList<>(reservas);
        copia.sort(Comparator.comparing(Reserva::getFechaInicio).thenComparing(Reserva::getId));
        return Collections.unmodifiableList(copia);
    }

    public double calcularTotalReservas() {
        double total = 0;
        for (Reserva reserva : reservas) {
            total += reserva.calcularTotal();
        }
        return total;
    }

    // ---------------------------------------------------------------- auxiliares

    private static byte[] hashear(String contrasena, byte[] sal) {
        try {
            PBEKeySpec especificacion =
                    new PBEKeySpec(contrasena.toCharArray(), sal, ITERACIONES_HASH, LARGO_HASH_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(especificacion)
                    .getEncoded();
        } catch (GeneralSecurityException e) {
            // PBKDF2WithHmacSHA256 viene incluido en todas las JVM estándar desde Java 8.
            throw new IllegalStateException("No se pudo calcular el hash de la contraseña.", e);
        }
    }

    @Override
    public String toString() {
        return nombreUsuario + " (" + reservas.size() + " reserva(s))";
    }
}
