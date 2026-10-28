package reservas;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Reserva de un vuelo. La fecha de inicio es la salida y la de fin, la llegada. */
public final class ReservaAvion extends Reserva {

    private static final String CAMPO_AEROLINEA = "Aerolínea";
    private static final String CAMPO_VUELO = "Número de vuelo";
    private static final String CAMPO_ORIGEN = "Origen";
    private static final String CAMPO_DESTINO = "Destino";
    private static final String CAMPO_PASAJEROS = "Cantidad de pasajeros";
    private static final String CAMPO_PRECIO = "Precio por pasajero";

    private String aerolinea;
    private String numeroVuelo;
    private String origen;
    private String destino;
    private int cantidadPasajeros;
    private double precioPorPasajero;

    public ReservaAvion(String codigoConfirmacion, LocalDate fechaSalida, LocalDate fechaLlegada,
                        String aerolinea, String numeroVuelo, String origen, String destino,
                        int cantidadPasajeros, double precioPorPasajero) {
        super(codigoConfirmacion, fechaSalida, fechaLlegada);
        this.aerolinea = validarTexto(aerolinea, CAMPO_AEROLINEA);
        this.numeroVuelo = validarTexto(numeroVuelo, CAMPO_VUELO).toUpperCase();
        this.origen = validarTexto(origen, CAMPO_ORIGEN);
        this.destino = validarTexto(destino, CAMPO_DESTINO);
        validarRuta(this.origen, this.destino);
        this.cantidadPasajeros = validarEnteroPositivo(cantidadPasajeros, CAMPO_PASAJEROS);
        this.precioPorPasajero = validarMonto(precioPorPasajero, CAMPO_PRECIO);
    }

    private static void validarRuta(String origen, String destino) {
        if (origen.equalsIgnoreCase(destino)) {
            throw new IllegalArgumentException("El origen y el destino no pueden ser iguales.");
        }
    }

    @Override
    public String getTipo() {
        return "Avión";
    }

    @Override
    public double calcularTotal() {
        return cantidadPasajeros * precioPorPasajero;
    }

    @Override
    public String getDetalle() {
        return String.format("%s %s, %s -> %s, %s x $%,.2f",
                aerolinea, numeroVuelo, origen, destino,
                cantidad(cantidadPasajeros, "pasajero", "pasajeros"), precioPorPasajero);
    }

    @Override
    public List<Campo> getCamposModificables() {
        List<Campo> campos = super.getCamposModificables();
        campos.add(Campo.texto(CAMPO_AEROLINEA, aerolinea));
        campos.add(Campo.texto(CAMPO_VUELO, numeroVuelo));
        campos.add(Campo.texto(CAMPO_ORIGEN, origen));
        campos.add(Campo.texto(CAMPO_DESTINO, destino));
        campos.add(Campo.entero(CAMPO_PASAJEROS, cantidadPasajeros));
        campos.add(Campo.monto(CAMPO_PRECIO, precioPorPasajero));
        return campos;
    }

    @Override
    public void modificar(Map<String, String> cambios) {
        // 1. Validar los datos propios, sin cambiar nada todavía.
        String nuevaAerolinea = nuevoTexto(cambios, CAMPO_AEROLINEA, aerolinea);
        String nuevoVuelo = nuevoTexto(cambios, CAMPO_VUELO, numeroVuelo).toUpperCase();
        String nuevoOrigen = nuevoTexto(cambios, CAMPO_ORIGEN, origen);
        String nuevoDestino = nuevoTexto(cambios, CAMPO_DESTINO, destino);
        validarRuta(nuevoOrigen, nuevoDestino);
        int nuevosPasajeros = nuevoEntero(cambios, CAMPO_PASAJEROS, cantidadPasajeros);
        double nuevoPrecio = nuevoMonto(cambios, CAMPO_PRECIO, precioPorPasajero);

        // 2. Validar y aplicar los datos comunes (si algo falla, no cambia nada).
        super.modificar(cambios);

        // 3. Aplicar los datos propios, que ya están validados.
        this.aerolinea = nuevaAerolinea;
        this.numeroVuelo = nuevoVuelo;
        this.origen = nuevoOrigen;
        this.destino = nuevoDestino;
        this.cantidadPasajeros = nuevosPasajeros;
        this.precioPorPasajero = nuevoPrecio;
    }

    // ---------------------------------------------------------------- getters

    public String getAerolinea() {
        return aerolinea;
    }

    public String getNumeroVuelo() {
        return numeroVuelo;
    }

    public String getOrigen() {
        return origen;
    }

    public String getDestino() {
        return destino;
    }

    public int getCantidadPasajeros() {
        return cantidadPasajeros;
    }

    public double getPrecioPorPasajero() {
        return precioPorPasajero;
    }
}
