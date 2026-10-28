package reservas;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Un dato modificable de una reserva: su nombre, qué tipo de dato es y su valor actual como texto.
 * Las interfaces (consola o web) lo usan para mostrar y editar cualquier reserva sin conocer su tipo.
 */
public final class Campo {

    public enum Tipo { TEXTO, FECHA, ENTERO, MONTO }

    private final String nombre;
    private final Tipo tipo;
    private final String valor;

    private Campo(String nombre, Tipo tipo, String valor) {
        this.nombre = nombre;
        this.tipo = tipo;
        this.valor = valor;
    }

    public static Campo texto(String nombre, String valor) {
        return new Campo(nombre, Tipo.TEXTO, valor);
    }

    public static Campo fecha(String nombre, LocalDate valor) {
        return new Campo(nombre, Tipo.FECHA, Reserva.FORMATO_FECHA.format(valor));
    }

    public static Campo entero(String nombre, int valor) {
        return new Campo(nombre, Tipo.ENTERO, String.valueOf(valor));
    }

    /** El monto se guarda sin notación científica ni ceros de más (950000, 85000.5). */
    public static Campo monto(String nombre, double valor) {
        return new Campo(nombre, Tipo.MONTO, BigDecimal.valueOf(valor).stripTrailingZeros().toPlainString());
    }

    public String getNombre() {
        return nombre;
    }

    public Tipo getTipo() {
        return tipo;
    }

    public String getValor() {
        return valor;
    }

    @Override
    public String toString() {
        return nombre + ": " + valor;
    }
}
