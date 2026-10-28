package reservas.web;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

/**
 * Convierte mapas, listas, textos y números a JSON. Es lo mínimo que necesita la API,
 * para no depender de una librería externa.
 */
final class Json {

    private Json() {
    }

    static String escribir(Object valor) {
        StringBuilder salida = new StringBuilder();
        escribir(valor, salida);
        return salida.toString();
    }

    private static void escribir(Object valor, StringBuilder salida) {
        if (valor == null) {
            salida.append("null");
        } else if (valor instanceof String) {
            escribirTexto((String) valor, salida);
        } else if (valor instanceof Double || valor instanceof Float) {
            double numero = ((Number) valor).doubleValue();
            salida.append(Double.isNaN(numero) || Double.isInfinite(numero) ? "null" : String.valueOf(numero));
        } else if (valor instanceof Number || valor instanceof Boolean) {
            salida.append(valor);
        } else if (valor instanceof Map) {
            salida.append('{');
            Iterator<? extends Map.Entry<?, ?>> entradas = ((Map<?, ?>) valor).entrySet().iterator();
            while (entradas.hasNext()) {
                Map.Entry<?, ?> entrada = entradas.next();
                escribirTexto(String.valueOf(entrada.getKey()), salida);
                salida.append(':');
                escribir(entrada.getValue(), salida);
                if (entradas.hasNext()) {
                    salida.append(',');
                }
            }
            salida.append('}');
        } else if (valor instanceof Collection) {
            salida.append('[');
            Iterator<?> elementos = ((Collection<?>) valor).iterator();
            while (elementos.hasNext()) {
                escribir(elementos.next(), salida);
                if (elementos.hasNext()) {
                    salida.append(',');
                }
            }
            salida.append(']');
        } else {
            escribirTexto(valor.toString(), salida);
        }
    }

    private static void escribirTexto(String texto, StringBuilder salida) {
        salida.append('"');
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            switch (c) {
                case '"':
                    salida.append("\\\"");
                    break;
                case '\\':
                    salida.append("\\\\");
                    break;
                case '\n':
                    salida.append("\\n");
                    break;
                case '\r':
                    salida.append("\\r");
                    break;
                case '\t':
                    salida.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        salida.append(String.format("\\u%04x", (int) c));
                    } else {
                        salida.append(c);
                    }
            }
        }
        salida.append('"');
    }
}
