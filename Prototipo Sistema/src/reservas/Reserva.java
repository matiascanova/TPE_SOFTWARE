package reservas;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Núcleo común de toda reserva: identificador, código de confirmación y fechas.
 * Cada tipo concreto agrega su propio detalle y define:
 * - cómo se calcula el total ({@link #calcularTotal()}),
 * - cómo se describe ({@link #getDetalle()}),
 * - qué campos se pueden modificar ({@link #getCamposModificables()} y {@link #modificar(Map)}).
 *
 * Las invariantes se validan acá y en las subclases, así una reserva nunca queda en un estado inválido.
 */
public abstract class Reserva {

    public static final DateTimeFormatter FORMATO_FECHA =
            DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);

    protected static final String CAMPO_CODIGO = "Código de confirmación";
    protected static final String CAMPO_INICIO = "Fecha de inicio";
    protected static final String CAMPO_FIN = "Fecha de fin";

    private static int ultimoId = 0;

    private final int id;
    /** Días mínimos entre inicio y fin (0 = puede empezar y terminar el mismo día). */
    private final int diasMinimos;
    private String codigoConfirmacion;
    private LocalDate fechaInicio;
    private LocalDate fechaFin;

    protected Reserva(String codigoConfirmacion, LocalDate fechaInicio, LocalDate fechaFin) {
        this(codigoConfirmacion, fechaInicio, fechaFin, 0);
    }

    /** Para tipos que exigen una duración mínima (por ejemplo, un hotel exige al menos una noche). */
    protected Reserva(String codigoConfirmacion, LocalDate fechaInicio, LocalDate fechaFin, int diasMinimos) {
        this.diasMinimos = diasMinimos;
        this.codigoConfirmacion = validarTexto(codigoConfirmacion, CAMPO_CODIGO);
        validarFechas(fechaInicio, fechaFin);
        this.fechaInicio = fechaInicio;
        this.fechaFin = fechaFin;
        this.id = ++ultimoId;
    }

    // ---------------------------------------------------------------- polimorfismo

    /** Nombre del tipo de reserva, para mostrar ("Avión", "Hotel", ...). */
    public abstract String getTipo();

    /** Importe total de la reserva; cada tipo lo calcula a su manera. */
    public abstract double calcularTotal();

    /** Datos propios del tipo, en una línea. */
    public abstract String getDetalle();

    /** Campos que se pueden modificar, con su valor actual. Las subclases agregan los suyos. */
    public List<Campo> getCamposModificables() {
        List<Campo> campos = new ArrayList<>();
        campos.add(Campo.texto(CAMPO_CODIGO, codigoConfirmacion));
        campos.add(Campo.fecha(CAMPO_INICIO, fechaInicio));
        campos.add(Campo.fecha(CAMPO_FIN, fechaFin));
        return campos;
    }

    /**
     * Modifica uno o varios campos a la vez. Recibe nombre del campo -> nuevo valor (como texto);
     * los campos que no vienen en el mapa no cambian.
     *
     * Es todo o nada: si algún valor no es válido lanza IllegalArgumentException y la reserva queda
     * exactamente como estaba. Por eso se pueden cambiar juntos datos que dependen entre sí
     * (las dos fechas, o el origen y el destino de un vuelo).
     *
     * Las subclases que lo sobrescriben deben: 1) validar sus datos propios sin asignarlos,
     * 2) llamar a super.modificar(cambios), 3) asignar sus datos propios.
     */
    public void modificar(Map<String, String> cambios) {
        validarNombresDeCampos(cambios);
        String nuevoCodigo = nuevoTexto(cambios, CAMPO_CODIGO, codigoConfirmacion);
        LocalDate nuevoInicio = nuevaFecha(cambios, CAMPO_INICIO, fechaInicio);
        LocalDate nuevoFin = nuevaFecha(cambios, CAMPO_FIN, fechaFin);
        validarFechas(nuevoInicio, nuevoFin);

        this.codigoConfirmacion = nuevoCodigo;
        this.fechaInicio = nuevoInicio;
        this.fechaFin = nuevoFin;
    }

    // ---------------------------------------------------------------- getters

    public int getId() {
        return id;
    }

    public String getCodigoConfirmacion() {
        return codigoConfirmacion;
    }

    public LocalDate getFechaInicio() {
        return fechaInicio;
    }

    public LocalDate getFechaFin() {
        return fechaFin;
    }

    @Override
    public String toString() {
        return String.format("#%d [%s] %s | %s al %s | %s | Total: $%,.2f",
                id, getTipo(), codigoConfirmacion,
                FORMATO_FECHA.format(fechaInicio), FORMATO_FECHA.format(fechaFin),
                getDetalle(), calcularTotal());
    }

    // ---------------------------------------------------------------- reglas

    private void validarFechas(LocalDate inicio, LocalDate fin) {
        if (inicio == null || fin == null) {
            throw new IllegalArgumentException("Las fechas son obligatorias.");
        }
        if (fin.isBefore(inicio.plusDays(diasMinimos))) {
            if (diasMinimos == 0) {
                throw new IllegalArgumentException("La fecha de fin no puede ser anterior a la de inicio.");
            }
            if (diasMinimos == 1) {
                throw new IllegalArgumentException("La fecha de fin debe ser posterior a la de inicio.");
            }
            throw new IllegalArgumentException(
                    "La fecha de fin debe ser al menos " + diasMinimos + " días posterior a la de inicio.");
        }
    }

    private void validarNombresDeCampos(Map<String, String> cambios) {
        Set<String> conocidos = new HashSet<>();
        for (Campo campo : getCamposModificables()) {
            conocidos.add(campo.getNombre());
        }
        for (String nombre : cambios.keySet()) {
            if (!conocidos.contains(nombre)) {
                throw new IllegalArgumentException("Una reserva de " + getTipo() + " no tiene el campo \"" + nombre + "\".");
            }
        }
    }

    /** "1 noche", "4 noches": para que los detalles no digan "noche(s)". */
    protected static String cantidad(long numero, String singular, String plural) {
        return numero + " " + (numero == 1 ? singular : plural);
    }

    // ---------------------------------------------------------------- validación (para constructores)

    protected static String validarTexto(String valor, String campo) {
        if (valor == null || valor.trim().isEmpty()) {
            throw new IllegalArgumentException("El campo \"" + campo + "\" es obligatorio.");
        }
        return valor.trim();
    }

    protected static int validarEnteroPositivo(int valor, String campo) {
        if (valor <= 0) {
            throw new IllegalArgumentException("El campo \"" + campo + "\" debe ser mayor a cero.");
        }
        return valor;
    }

    protected static double validarMonto(double valor, String campo) {
        if (!(valor >= 0) || Double.isInfinite(valor)) {
            throw new IllegalArgumentException("El campo \"" + campo + "\" debe ser un monto mayor o igual a cero.");
        }
        return valor;
    }

    // ---------------------------------------------------------------- lectura de cambios (para modificar)
    // Si el campo no viene en el mapa devuelven el valor actual; si viene, lo convierten y validan.

    protected static String nuevoTexto(Map<String, String> cambios, String campo, String actual) {
        return cambios.containsKey(campo) ? validarTexto(cambios.get(campo), campo) : actual;
    }

    protected static LocalDate nuevaFecha(Map<String, String> cambios, String campo, LocalDate actual) {
        return cambios.containsKey(campo) ? parsearFecha(cambios.get(campo), campo) : actual;
    }

    protected static int nuevoEntero(Map<String, String> cambios, String campo, int actual) {
        if (!cambios.containsKey(campo)) {
            return actual;
        }
        try {
            return validarEnteroPositivo(Integer.parseInt(validarTexto(cambios.get(campo), campo)), campo);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("El campo \"" + campo + "\" debe ser un número entero.");
        }
    }

    protected static double nuevoMonto(Map<String, String> cambios, String campo, double actual) {
        if (!cambios.containsKey(campo)) {
            return actual;
        }
        try {
            String texto = validarTexto(cambios.get(campo), campo).replace(',', '.');
            return validarMonto(Double.parseDouble(texto), campo);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("El campo \"" + campo + "\" debe ser un número.");
        }
    }

    // ---------------------------------------------------------------- conversión de fechas

    /** Convierte un texto dd/mm/aaaa en fecha. Lanza IllegalArgumentException si no es una fecha válida. */
    public static LocalDate parsearFecha(String texto) {
        try {
            return LocalDate.parse(texto == null ? "" : texto.trim(), FORMATO_FECHA);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Fecha inválida: \"" + texto + "\". Use el formato dd/mm/aaaa.");
        }
    }

    private static LocalDate parsearFecha(String texto, String campo) {
        try {
            return LocalDate.parse(validarTexto(texto, campo), FORMATO_FECHA);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("El campo \"" + campo + "\" debe ser una fecha válida con el formato dd/mm/aaaa.");
        }
    }
}
