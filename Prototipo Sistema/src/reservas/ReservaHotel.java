package reservas;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/** Reserva de hotel. La fecha de inicio es el check-in y la de fin, el check-out. */
public final class ReservaHotel extends Reserva {

    private static final String CAMPO_HOTEL = "Nombre del hotel";
    private static final String CAMPO_CIUDAD = "Ciudad";
    private static final String CAMPO_HABITACIONES = "Cantidad de habitaciones";
    private static final String CAMPO_PRECIO = "Precio por noche por habitación";

    private String nombreHotel;
    private String ciudad;
    private int cantidadHabitaciones;
    private double precioPorNoche;

    public ReservaHotel(String codigoConfirmacion, LocalDate checkIn, LocalDate checkOut,
                        String nombreHotel, String ciudad, int cantidadHabitaciones, double precioPorNoche) {
        // Un hotel exige al menos una noche: el check-out tiene que ser posterior al check-in.
        super(codigoConfirmacion, checkIn, checkOut, 1);
        this.nombreHotel = validarTexto(nombreHotel, CAMPO_HOTEL);
        this.ciudad = validarTexto(ciudad, CAMPO_CIUDAD);
        this.cantidadHabitaciones = validarEnteroPositivo(cantidadHabitaciones, CAMPO_HABITACIONES);
        this.precioPorNoche = validarMonto(precioPorNoche, CAMPO_PRECIO);
    }

    public long getCantidadNoches() {
        return ChronoUnit.DAYS.between(getFechaInicio(), getFechaFin());
    }

    @Override
    public String getTipo() {
        return "Hotel";
    }

    @Override
    public double calcularTotal() {
        return getCantidadNoches() * cantidadHabitaciones * precioPorNoche;
    }

    @Override
    public String getDetalle() {
        return String.format("%s (%s), %s, %s x $%,.2f por noche",
                nombreHotel, ciudad, cantidad(getCantidadNoches(), "noche", "noches"),
                cantidad(cantidadHabitaciones, "habitación", "habitaciones"), precioPorNoche);
    }

    @Override
    public List<Campo> getCamposModificables() {
        List<Campo> campos = super.getCamposModificables();
        campos.add(Campo.texto(CAMPO_HOTEL, nombreHotel));
        campos.add(Campo.texto(CAMPO_CIUDAD, ciudad));
        campos.add(Campo.entero(CAMPO_HABITACIONES, cantidadHabitaciones));
        campos.add(Campo.monto(CAMPO_PRECIO, precioPorNoche));
        return campos;
    }

    @Override
    public void modificar(Map<String, String> cambios) {
        // 1. Validar los datos propios, sin cambiar nada todavía.
        String nuevoHotel = nuevoTexto(cambios, CAMPO_HOTEL, nombreHotel);
        String nuevaCiudad = nuevoTexto(cambios, CAMPO_CIUDAD, ciudad);
        int nuevasHabitaciones = nuevoEntero(cambios, CAMPO_HABITACIONES, cantidadHabitaciones);
        double nuevoPrecio = nuevoMonto(cambios, CAMPO_PRECIO, precioPorNoche);

        // 2. Validar y aplicar los datos comunes (si algo falla, no cambia nada).
        super.modificar(cambios);

        // 3. Aplicar los datos propios, que ya están validados.
        this.nombreHotel = nuevoHotel;
        this.ciudad = nuevaCiudad;
        this.cantidadHabitaciones = nuevasHabitaciones;
        this.precioPorNoche = nuevoPrecio;
    }

    // ---------------------------------------------------------------- getters

    public String getNombreHotel() {
        return nombreHotel;
    }

    public String getCiudad() {
        return ciudad;
    }

    public int getCantidadHabitaciones() {
        return cantidadHabitaciones;
    }

    public double getPrecioPorNoche() {
        return precioPorNoche;
    }
}
