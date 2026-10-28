package reservas;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;

/**
 * Interfaz de consola: registro e inicio de sesión de usuarios y
 * alta, modificación y baja de las reservas de cada usuario.
 * (La interfaz web está en reservas.web.ServidorWeb.)
 */
public class Main {

    private static final Scanner entrada = new Scanner(System.in);

    private static final SistemaReservas sistema = SistemaReservas.conDatosDePrueba();

    public static void main(String[] args) {
        System.out.println("===== Sistema de reservas =====");
        System.out.println("(Usuario de prueba: demo / demo123)");

        boolean salir = false;
        while (!salir) {
            System.out.println();
            System.out.println("1. Registrarse");
            System.out.println("2. Iniciar sesión");
            System.out.println("0. Salir");
            int opcion = leerEntero("Opción: ", 0);
            switch (opcion) {
                case 1:
                    registrarUsuario();
                    break;
                case 2: {
                    Usuario usuario = iniciarSesion();
                    if (usuario != null) {
                        menuUsuario(usuario);
                    }
                    break;
                }
                case 0:
                    salir = true;
                    break;
                default:
                    System.out.println("Opción inválida.");
            }
        }
        System.out.println("¡Hasta luego!");
    }

    // ================================================================ usuarios

    private static void registrarUsuario() {
        System.out.println("\n--- Registro ---");
        String nombre = leerTexto("Nombre de usuario: ");
        if (sistema.existeUsuario(nombre)) {
            System.out.println("Ya existe un usuario con ese nombre.");
            return;
        }
        String contrasena = leerTexto("Contraseña: ");
        String repetida = leerTexto("Repita la contraseña: ");
        if (!contrasena.equals(repetida)) {
            System.out.println("Las contraseñas no coinciden.");
            return;
        }
        try {
            Usuario usuario = sistema.registrar(nombre, contrasena);
            System.out.println("Usuario \"" + usuario.getNombreUsuario() + "\" registrado. Ya puede iniciar sesión.");
        } catch (IllegalArgumentException e) {
            System.out.println("No se pudo registrar: " + e.getMessage());
        }
    }

    private static Usuario iniciarSesion() {
        System.out.println("\n--- Iniciar sesión ---");
        String nombre = leerTexto("Usuario: ");
        String contrasena = leerTexto("Contraseña: ");
        Usuario usuario = sistema.iniciarSesion(nombre, contrasena);
        if (usuario == null) {
            // Mismo mensaje en los dos casos, para no revelar qué usuarios existen.
            System.out.println("Usuario o contraseña incorrectos.");
            return null;
        }
        System.out.println("Bienvenido/a, " + usuario.getNombreUsuario() + ".");
        return usuario;
    }

    private static void menuUsuario(Usuario usuario) {
        boolean cerrarSesion = false;
        while (!cerrarSesion) {
            System.out.println();
            System.out.println("--- Reservas de " + usuario.getNombreUsuario() + " ---");
            System.out.println("1. Ver mis reservas");
            System.out.println("2. Agregar reserva");
            System.out.println("3. Modificar reserva");
            System.out.println("4. Eliminar reserva");
            System.out.println("0. Cerrar sesión");
            int opcion = leerEntero("Opción: ", 0);
            switch (opcion) {
                case 1:
                    listarReservas(usuario);
                    break;
                case 2:
                    agregarReserva(usuario);
                    break;
                case 3:
                    modificarReserva(usuario);
                    break;
                case 4:
                    eliminarReserva(usuario);
                    break;
                case 0:
                    cerrarSesion = true;
                    System.out.println("Sesión cerrada.");
                    break;
                default:
                    System.out.println("Opción inválida.");
            }
        }
    }

    // ================================================================ reservas

    /** Muestra las reservas del usuario. Devuelve false si no tiene ninguna. */
    private static boolean listarReservas(Usuario usuario) {
        List<Reserva> reservas = usuario.getReservas();
        if (reservas.isEmpty()) {
            System.out.println("No tiene reservas cargadas.");
            return false;
        }
        System.out.println();
        for (Reserva reserva : reservas) {
            System.out.println(reserva);
        }
        System.out.printf("Total de sus reservas: $%,.2f%n", usuario.calcularTotalReservas());
        return true;
    }

    private static void agregarReserva(Usuario usuario) {
        System.out.println("\n--- Nueva reserva ---");
        System.out.println("1. Avión");
        System.out.println("2. Hotel");
        System.out.println("3. Turismo");
        System.out.println("0. Cancelar");
        int tipo = leerEntero("Tipo de reserva: ", 0);
        try {
            Reserva reserva;
            switch (tipo) {
                case 1:
                    reserva = crearReservaAvion();
                    break;
                case 2:
                    reserva = crearReservaHotel();
                    break;
                case 3:
                    reserva = crearReservaTurismo();
                    break;
                case 0:
                    return;
                default:
                    System.out.println("Tipo inválido.");
                    return;
            }
            usuario.agregarReserva(reserva);
            System.out.println("Reserva agregada: " + reserva);
        } catch (IllegalArgumentException e) {
            System.out.println("No se pudo agregar la reserva: " + e.getMessage());
        }
    }

    private static Reserva crearReservaAvion() {
        String codigo = leerTexto("Código de confirmación: ");
        String aerolinea = leerTexto("Aerolínea: ");
        String vuelo = leerTexto("Número de vuelo: ");
        String origen = leerTexto("Origen: ");
        String destino = leerTexto("Destino: ");
        LocalDate salida = leerFecha("Fecha de salida (dd/mm/aaaa): ");
        LocalDate llegada = leerFecha("Fecha de llegada (dd/mm/aaaa): ");
        int pasajeros = leerEntero("Cantidad de pasajeros: ", 1);
        double precio = leerMonto("Precio por pasajero: ");
        return new ReservaAvion(codigo, salida, llegada, aerolinea, vuelo, origen, destino, pasajeros, precio);
    }

    private static Reserva crearReservaHotel() {
        String codigo = leerTexto("Código de confirmación: ");
        String hotel = leerTexto("Nombre del hotel: ");
        String ciudad = leerTexto("Ciudad: ");
        LocalDate checkIn = leerFecha("Fecha de entrada / check-in (dd/mm/aaaa): ");
        LocalDate checkOut = leerFecha("Fecha de salida / check-out (dd/mm/aaaa): ");
        int habitaciones = leerEntero("Cantidad de habitaciones: ", 1);
        double precio = leerMonto("Precio por noche por habitación: ");
        return new ReservaHotel(codigo, checkIn, checkOut, hotel, ciudad, habitaciones, precio);
    }

    private static Reserva crearReservaTurismo() {
        String codigo = leerTexto("Código de confirmación: ");
        String actividad = leerTexto("Actividad (excursión, tour, etc.): ");
        String lugar = leerTexto("Lugar: ");
        LocalDate inicio = leerFecha("Fecha de inicio (dd/mm/aaaa): ");
        LocalDate fin = leerFecha("Fecha de fin (dd/mm/aaaa): ");
        int personas = leerEntero("Cantidad de personas: ", 1);
        double precio = leerMonto("Precio por persona: ");
        return new ReservaTurismo(codigo, inicio, fin, actividad, lugar, personas, precio);
    }

    /**
     * No necesita saber de qué tipo es la reserva: cada una informa sus campos modificables
     * y sabe cómo modificarlos. Un tipo nuevo de reserva no requiere cambiar este método.
     */
    private static void modificarReserva(Usuario usuario) {
        System.out.println("\n--- Modificar reserva ---");
        Reserva reserva = elegirReserva(usuario, "ID de la reserva a modificar (0 para cancelar): ");
        if (reserva == null) {
            return;
        }
        while (true) {
            // Se vuelven a pedir en cada vuelta para mostrar los valores actualizados.
            List<Campo> campos = reserva.getCamposModificables();
            System.out.println();
            for (int i = 0; i < campos.size(); i++) {
                System.out.println((i + 1) + ". " + campos.get(i));
            }
            System.out.println("0. Terminar");
            int opcion = leerEntero("Campo a modificar: ", 0);
            if (opcion == 0) {
                return;
            }
            if (opcion > campos.size()) {
                System.out.println("Opción inválida.");
                continue;
            }
            Campo campo = campos.get(opcion - 1);
            String ayuda = campo.getTipo() == Campo.Tipo.FECHA ? " (dd/mm/aaaa)" : "";
            String valor = leerTexto("Nuevo valor para \"" + campo.getNombre() + "\"" + ayuda + ": ");
            try {
                reserva.modificar(Collections.singletonMap(campo.getNombre(), valor));
                System.out.println("Reserva actualizada: " + reserva);
            } catch (IllegalArgumentException e) {
                System.out.println("No se pudo modificar: " + e.getMessage());
            }
        }
    }

    private static void eliminarReserva(Usuario usuario) {
        System.out.println("\n--- Eliminar reserva ---");
        Reserva reserva = elegirReserva(usuario, "ID de la reserva a eliminar (0 para cancelar): ");
        if (reserva == null) {
            return;
        }
        String confirmacion = leerTexto("¿Eliminar " + reserva + "? (s/n): ");
        if (confirmacion.equalsIgnoreCase("s")) {
            usuario.eliminarReserva(reserva.getId());
            System.out.println("Reserva eliminada.");
        } else {
            System.out.println("No se eliminó la reserva.");
        }
    }

    /** Lista las reservas del usuario y pide un id. Solo encuentra reservas de ese usuario. */
    private static Reserva elegirReserva(Usuario usuario, String mensaje) {
        if (!listarReservas(usuario)) {
            return null;
        }
        int id = leerEntero(mensaje, 0);
        if (id == 0) {
            return null;
        }
        Reserva reserva = usuario.buscarReserva(id);
        if (reserva == null) {
            System.out.println("No tiene ninguna reserva con el ID " + id + ".");
        }
        return reserva;
    }

    // ================================================================ lectura de datos

    private static String leerLinea(String mensaje) {
        System.out.print(mensaje);
        if (!entrada.hasNextLine()) {
            System.out.println("\nFin de la entrada. ¡Hasta luego!");
            System.exit(0);
        }
        return entrada.nextLine().trim();
    }

    private static String leerTexto(String mensaje) {
        while (true) {
            String texto = leerLinea(mensaje);
            if (!texto.isEmpty()) {
                return texto;
            }
            System.out.println("Este dato es obligatorio.");
        }
    }

    private static int leerEntero(String mensaje, int minimo) {
        while (true) {
            String texto = leerLinea(mensaje);
            try {
                int valor = Integer.parseInt(texto);
                if (valor >= minimo) {
                    return valor;
                }
                System.out.println("Ingrese un número mayor o igual a " + minimo + ".");
            } catch (NumberFormatException e) {
                System.out.println("Ingrese un número entero.");
            }
        }
    }

    private static double leerMonto(String mensaje) {
        while (true) {
            String texto = leerLinea(mensaje);
            try {
                double valor = Double.parseDouble(texto.replace(',', '.'));
                if (valor >= 0 && !Double.isInfinite(valor)) {
                    return valor;
                }
                System.out.println("Ingrese un monto mayor o igual a cero.");
            } catch (NumberFormatException e) {
                System.out.println("Ingrese un monto válido (por ejemplo 1500 o 1500,50).");
            }
        }
    }

    private static LocalDate leerFecha(String mensaje) {
        while (true) {
            try {
                return Reserva.parsearFecha(leerTexto(mensaje));
            } catch (IllegalArgumentException e) {
                System.out.println(e.getMessage());
            }
        }
    }
}
