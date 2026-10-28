package reservas;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Reserva de una actividad turística (excursión, tour, entrada a un museo, etc.). */
public final class ReservaTurismo extends Reserva {

    private static final String CAMPO_ACTIVIDAD = "Actividad";
    private static final String CAMPO_LUGAR = "Lugar";
    private static final String CAMPO_PERSONAS = "Cantidad de personas";
    private static final String CAMPO_PRECIO = "Precio por persona";

    private String actividad;
    private String lugar;
    private int cantidadPersonas;
    private double precioPorPersona;

    public ReservaTurismo(String codigoConfirmacion, LocalDate fechaInicio, LocalDate fechaFin,
                          String actividad, String lugar, int cantidadPersonas, double precioPorPersona) {
        super(codigoConfirmacion, fechaInicio, fechaFin);
        this.actividad = validarTexto(actividad, CAMPO_ACTIVIDAD);
        this.lugar = validarTexto(lugar, CAMPO_LUGAR);
        this.cantidadPersonas = validarEnteroPositivo(cantidadPersonas, CAMPO_PERSONAS);
        this.precioPorPersona = validarMonto(precioPorPersona, CAMPO_PRECIO);
    }

    @Override
    public String getTipo() {
        return "Turismo";
    }

    @Override
    public double calcularTotal() {
        return cantidadPersonas * precioPorPersona;
    }

    @Override
    public String getDetalle() {
        return String.format("%s en %s, %s x $%,.2f",
                actividad, lugar, cantidad(cantidadPersonas, "persona", "personas"), precioPorPersona);
    }

    @Override
    public List<Campo> getCamposModificables() {
        List<Campo> campos = super.getCamposModificables();
        campos.add(Campo.texto(CAMPO_ACTIVIDAD, actividad));
        campos.add(Campo.texto(CAMPO_LUGAR, lugar));
        campos.add(Campo.entero(CAMPO_PERSONAS, cantidadPersonas));
        campos.add(Campo.monto(CAMPO_PRECIO, precioPorPersona));
        return campos;
    }

    @Override
    public void modificar(Map<String, String> cambios) {
        // 1. Validar los datos propios, sin cambiar nada todavía.
        String nuevaActividad = nuevoTexto(cambios, CAMPO_ACTIVIDAD, actividad);
        String nuevoLugar = nuevoTexto(cambios, CAMPO_LUGAR, lugar);
        int nuevasPersonas = nuevoEntero(cambios, CAMPO_PERSONAS, cantidadPersonas);
        double nuevoPrecio = nuevoMonto(cambios, CAMPO_PRECIO, precioPorPersona);

        // 2. Validar y aplicar los datos comunes (si algo falla, no cambia nada).
        super.modificar(cambios);

        // 3. Aplicar los datos propios, que ya están validados.
        this.actividad = nuevaActividad;
        this.lugar = nuevoLugar;
        this.cantidadPersonas = nuevasPersonas;
        this.precioPorPersona = nuevoPrecio;
    }

    // ---------------------------------------------------------------- getters

    public String getActividad() {
        return actividad;
    }

    public String getLugar() {
        return lugar;
    }

    public int getCantidadPersonas() {
        return cantidadPersonas;
    }

    public double getPrecioPorPersona() {
        return precioPorPersona;
    }
}
