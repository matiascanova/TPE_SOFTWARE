'use strict';

/*
 * Interfaz web del sistema de reservas.
 * Toda la lógica (validaciones, cálculo de totales, permisos) está en el servidor Java;
 * esta página solo muestra los datos y envía lo que escribe el usuario a la API (/api/...).
 */

// ===================================================================== datos fijos

/* Íconos de Material Icons (licencia Apache 2.0). */
const ICONOS = {
  avion: '<svg viewBox="0 0 24 24"><path d="M21 16v-2l-8-5V3.5c0-.83-.67-1.5-1.5-1.5S10 2.67 10 3.5V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5l8 2.5z"/></svg>',
  hotel: '<svg viewBox="0 0 24 24"><path d="M7 13c1.66 0 3-1.34 3-3S8.66 7 7 7s-3 1.34-3 3 1.34 3 3 3zm12-6h-8v7H3V5H1v15h2v-3h18v3h2v-9c0-2.21-1.79-4-4-4z"/></svg>',
  turismo: '<svg viewBox="0 0 24 24"><path d="M14 6l-3.75 5 2.85 3.8-1.6 1.2C9.81 13.75 7 10 7 10l-6 8h22L14 6z"/></svg>',
  otro: '<svg viewBox="0 0 24 24"><path d="M22 10V6c0-1.11-.9-2-2-2H4c-1.1 0-1.99.89-1.99 2v4c1.1 0 1.99.9 1.99 2s-.89 2-2 2v4c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2v-4c-1.1 0-2-.9-2-2s.9-2 2-2z"/></svg>',
};

/*
 * Formularios de alta. Es lo único de la página que conoce los tipos de reserva,
 * igual que en el servidor: listar, editar y eliminar funcionan con cualquier tipo.
 * Los tipos de campo (TEXTO, FECHA, ENTERO, MONTO) son los mismos que usa la clase Campo en Java.
 */
const CODIGO = { nombre: 'codigo', etiqueta: 'Código de confirmación', tipo: 'TEXTO', completo: true };

const TIPOS_DE_ALTA = [
  {
    clave: 'avion',
    nombre: 'Avión',
    campos: [
      CODIGO,
      { nombre: 'aerolinea', etiqueta: 'Aerolínea', tipo: 'TEXTO' },
      { nombre: 'vuelo', etiqueta: 'Número de vuelo', tipo: 'TEXTO' },
      { nombre: 'origen', etiqueta: 'Origen', tipo: 'TEXTO' },
      { nombre: 'destino', etiqueta: 'Destino', tipo: 'TEXTO' },
      { nombre: 'inicio', etiqueta: 'Fecha de salida', tipo: 'FECHA' },
      { nombre: 'fin', etiqueta: 'Fecha de llegada', tipo: 'FECHA' },
      { nombre: 'cantidad', etiqueta: 'Pasajeros', tipo: 'ENTERO' },
      { nombre: 'precio', etiqueta: 'Precio por pasajero', tipo: 'MONTO' },
    ],
  },
  {
    clave: 'hotel',
    nombre: 'Hotel',
    campos: [
      CODIGO,
      { nombre: 'hotel', etiqueta: 'Hotel', tipo: 'TEXTO' },
      { nombre: 'ciudad', etiqueta: 'Ciudad', tipo: 'TEXTO' },
      { nombre: 'inicio', etiqueta: 'Check-in', tipo: 'FECHA' },
      { nombre: 'fin', etiqueta: 'Check-out', tipo: 'FECHA' },
      { nombre: 'cantidad', etiqueta: 'Habitaciones', tipo: 'ENTERO' },
      { nombre: 'precio', etiqueta: 'Precio por noche', tipo: 'MONTO', ayuda: 'Por cada habitación.' },
    ],
  },
  {
    clave: 'turismo',
    nombre: 'Turismo',
    campos: [
      CODIGO,
      { nombre: 'actividad', etiqueta: 'Actividad', tipo: 'TEXTO', completo: true, ayuda: 'Excursión, tour, entrada a un museo…' },
      { nombre: 'lugar', etiqueta: 'Lugar', tipo: 'TEXTO', completo: true },
      { nombre: 'inicio', etiqueta: 'Fecha de inicio', tipo: 'FECHA' },
      { nombre: 'fin', etiqueta: 'Fecha de fin', tipo: 'FECHA' },
      { nombre: 'cantidad', etiqueta: 'Personas', tipo: 'ENTERO' },
      { nombre: 'precio', etiqueta: 'Precio por persona', tipo: 'MONTO' },
    ],
  },
];

const formatoMoneda = new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS' });
const formatoFecha = new Intl.DateTimeFormat('es-AR', { day: 'numeric', month: 'short', year: 'numeric' });
const formatoDiaSemana = new Intl.DateTimeFormat('es-AR', { weekday: 'short' });
const formatoMes = new Intl.DateTimeFormat('es-AR', { month: 'long', year: 'numeric' });

// ===================================================================== estado y elementos

const estado = {
  usuario: null,
  reservas: [],
  modoAcceso: 'login',
  reservaSeleccionada: null,
};

const $ = (selector) => document.querySelector(selector);

const elementos = {
  pantallaAcceso: $('#pantalla-acceso'),
  pantallaApp: $('#pantalla-app'),
  formAcceso: $('#form-acceso'),
  botonAcceso: $('#boton-acceso'),
  campoRepetida: $('#campo-repetida'),
  ayudaContrasena: $('#ayuda-contrasena'),
  nombreUsuario: $('#nombre-usuario'),
  resumen: $('#resumen'),
  lista: $('#lista-reservas'),
  vacio: $('#vacio'),
  plantillaPasaje: $('#plantilla-pasaje'),
  dialogoNueva: $('#dialogo-nueva'),
  formNueva: $('#form-nueva'),
  opcionesTipo: $('#opciones-tipo'),
  camposNueva: $('#campos-nueva'),
  dialogoEditar: $('#dialogo-editar'),
  formEditar: $('#form-editar'),
  codigoEditar: $('#codigo-editar'),
  camposEditar: $('#campos-editar'),
  dialogoEliminar: $('#dialogo-eliminar'),
  formEliminar: $('#form-eliminar'),
  codigoEliminar: $('#codigo-eliminar'),
  textoEliminar: $('#texto-eliminar'),
  aviso: $('#aviso'),
};

// ===================================================================== comunicación con el servidor

/** Llama a la API. Los datos se envían como formulario y la respuesta es JSON. */
async function api(metodo, ruta, datos) {
  const opciones = { method: metodo, credentials: 'same-origin' };
  if (datos) {
    opciones.body = new URLSearchParams(datos);
  }

  let respuesta;
  try {
    respuesta = await fetch(ruta, opciones);
  } catch {
    throw crearError('No se pudo conectar con el servidor. Verifique que esté en ejecución.', 0);
  }

  let cuerpo = null;
  try {
    cuerpo = await respuesta.json();
  } catch {
    // Respuesta sin JSON: se informa con el código de estado.
  }
  if (!respuesta.ok) {
    throw crearError(cuerpo?.error ?? `El servidor respondió con un error (${respuesta.status}).`, respuesta.status);
  }
  return cuerpo;
}

function crearError(mensaje, estadoHttp) {
  const error = new Error(mensaje);
  error.estado = estadoHttp;
  return error;
}

/**
 * Muestra un error dentro del formulario donde ocurrió. Si la sesión expiró
 * (por ejemplo, porque se reinició el servidor), vuelve a la pantalla de acceso.
 */
function manejarError(error, formulario) {
  if (error.estado === 401 && estado.usuario) {
    for (const dialogo of document.querySelectorAll('dialog[open]')) {
      dialogo.close();
    }
    mostrarAcceso('La sesión expiró. Inicie sesión de nuevo.');
    return;
  }
  mostrarError(formulario, error.message);
}

function mostrarError(formulario, mensaje) {
  const caja = formulario.querySelector('.mensaje-error');
  caja.textContent = mensaje;
  caja.hidden = false;
}

function ocultarError(formulario) {
  formulario.querySelector('.mensaje-error').hidden = true;
}

// ===================================================================== acceso

function prepararAcceso() {
  for (const boton of document.querySelectorAll('.pestanas__pestana')) {
    boton.addEventListener('click', () => cambiarModoAcceso(boton.dataset.modo));
  }

  elementos.formAcceso.addEventListener('submit', async (evento) => {
    evento.preventDefault();
    const form = elementos.formAcceso;
    ocultarError(form);

    const datos = {
      nombre: form.elements.nombre.value.trim(),
      contrasena: form.elements.contrasena.value,
    };
    if (estado.modoAcceso === 'registro' && form.elements.repetida.value !== datos.contrasena) {
      mostrarError(form, 'Las contraseñas no coinciden.');
      return;
    }

    elementos.botonAcceso.disabled = true;
    try {
      const ruta = estado.modoAcceso === 'login' ? '/api/login' : '/api/registro';
      const { usuario } = await api('POST', ruta, datos);
      form.reset();
      await entrar(usuario);
    } catch (error) {
      mostrarError(form, error.message);
    } finally {
      elementos.botonAcceso.disabled = false;
    }
  });
}

function cambiarModoAcceso(modo) {
  estado.modoAcceso = modo;
  const registro = modo === 'registro';
  for (const boton of document.querySelectorAll('.pestanas__pestana')) {
    boton.setAttribute('aria-pressed', String(boton.dataset.modo === modo));
  }
  const form = elementos.formAcceso;
  elementos.campoRepetida.hidden = !registro;
  elementos.ayudaContrasena.hidden = !registro;
  form.elements.repetida.required = registro;
  form.elements.contrasena.autocomplete = registro ? 'new-password' : 'current-password';
  elementos.botonAcceso.textContent = registro ? 'Crear cuenta' : 'Iniciar sesión';
  ocultarError(form);
}

function mostrarAcceso(mensaje) {
  estado.usuario = null;
  estado.reservas = [];
  elementos.pantallaApp.hidden = true;
  elementos.pantallaAcceso.hidden = false;
  cambiarModoAcceso('login');
  if (mensaje) {
    mostrarError(elementos.formAcceso, mensaje);
  }
  elementos.formAcceso.elements.nombre.focus();
}

async function entrar(usuario) {
  estado.usuario = usuario;
  elementos.nombreUsuario.textContent = usuario;
  elementos.pantallaAcceso.hidden = true;
  elementos.pantallaApp.hidden = false;
  await cargarReservas();
}

async function cerrarSesion() {
  try {
    await api('POST', '/api/logout');
  } catch {
    // Aunque falle (servidor caído), se vuelve a la pantalla de acceso.
  }
  mostrarAcceso();
}

// ===================================================================== itinerario

async function cargarReservas(idResaltado) {
  try {
    const datos = await api('GET', '/api/reservas');
    estado.reservas = datos.reservas;
    mostrarReservas(datos, idResaltado);
  } catch (error) {
    if (error.estado === 401) {
      mostrarAcceso('La sesión expiró. Inicie sesión de nuevo.');
    } else {
      elementos.resumen.textContent = error.message;
    }
  }
}

function mostrarReservas({ reservas, total }, idResaltado) {
  const cantidad = reservas.length;
  elementos.resumen.textContent = cantidad === 0
    ? 'Sin reservas cargadas.'
    : `${cantidad} ${cantidad === 1 ? 'reserva' : 'reservas'}, ${formatoMoneda.format(total)} en total.`;
  elementos.vacio.hidden = cantidad > 0;

  // Las reservas vienen ordenadas por fecha desde el servidor; se agrupan por mes.
  const items = [];
  let mesAnterior = null;
  for (const reserva of reservas) {
    const inicio = fechaDesdeIso(reserva.fechaInicio);
    const mes = `${inicio.getFullYear()}-${inicio.getMonth()}`;
    if (mes !== mesAnterior) {
      items.push(crearSeparadorDeMes(inicio));
      mesAnterior = mes;
    }
    items.push(crearPasaje(reserva));
  }
  elementos.lista.replaceChildren(...items);

  if (idResaltado !== undefined) {
    const item = elementos.lista.querySelector(`[data-id="${idResaltado}"]`);
    if (item) {
      item.classList.add('pasaje--resaltado');
      item.scrollIntoView({ block: 'nearest', behavior: preferirMenosMovimiento() ? 'auto' : 'smooth' });
    }
  }
}

function crearSeparadorDeMes(fecha) {
  const item = document.createElement('li');
  item.className = 'lista__mes';
  const texto = document.createElement('span');
  texto.textContent = capitalizar(formatoMes.format(fecha));
  item.append(texto);
  return item;
}

function crearPasaje(reserva) {
  const item = elementos.plantillaPasaje.content.firstElementChild.cloneNode(true);
  const clave = claveDeTipo(reserva.tipo);
  const inicio = fechaDesdeIso(reserva.fechaInicio);
  const fin = fechaDesdeIso(reserva.fechaFin);

  item.dataset.id = reserva.id;
  item.dataset.tipo = clave;

  const fecha = item.querySelector('.pasaje__fecha');
  fecha.dateTime = reserva.fechaInicio;
  item.querySelector('.pasaje__dia').textContent = inicio.getDate();
  item.querySelector('.pasaje__semana').textContent = formatoDiaSemana.format(inicio).replace('.', '');

  item.querySelector('.pasaje__icono').innerHTML = ICONOS[clave] ?? ICONOS.otro;
  item.querySelector('.pasaje__nombre-tipo').textContent = reserva.tipo;
  item.querySelector('.pasaje__detalle').textContent = reserva.detalle.replace(' -> ', ' → ');
  item.querySelector('.pasaje__rango').textContent = textoDeRango(inicio, fin);
  item.querySelector('.pasaje__codigo').textContent = reserva.codigo;
  item.querySelector('.pasaje__total').textContent = formatoMoneda.format(reserva.total);

  item.querySelector('[data-accion="editar"]').setAttribute('aria-label', `Editar la reserva ${reserva.codigo}`);
  item.querySelector('[data-accion="eliminar"]').setAttribute('aria-label', `Eliminar la reserva ${reserva.codigo}`);
  return item;
}

function prepararItinerario() {
  $('#boton-salir').addEventListener('click', cerrarSesion);
  $('#boton-nueva').addEventListener('click', abrirNueva);
  elementos.vacio.querySelector('[data-accion="nueva"]').addEventListener('click', abrirNueva);

  // Un solo manejador para los botones de todas las reservas.
  elementos.lista.addEventListener('click', (evento) => {
    const boton = evento.target.closest('button[data-accion]');
    if (!boton) {
      return;
    }
    const id = Number(boton.closest('.pasaje').dataset.id);
    const reserva = estado.reservas.find((r) => r.id === id);
    if (boton.dataset.accion === 'editar') {
      abrirEditar(reserva);
    } else if (boton.dataset.accion === 'eliminar') {
      abrirEliminar(reserva);
    }
  });
}

// ===================================================================== campos de formulario

let contadorCampos = 0;

/** Crea un campo según su tipo de dato. `valor` está en el formato del servidor (fechas dd/mm/aaaa). */
function crearCampo({ nombre, etiqueta, tipo, completo, ayuda }, valor = '') {
  const id = `campo-${++contadorCampos}`;
  const contenedor = document.createElement('div');
  contenedor.className = completo ? 'campo campo--completo' : 'campo';

  const label = document.createElement('label');
  label.className = 'campo__etiqueta';
  label.htmlFor = id;
  label.textContent = etiqueta;

  const input = document.createElement('input');
  input.className = 'campo__control';
  input.id = id;
  input.name = nombre;
  input.required = true;
  input.dataset.tipo = tipo;
  input.dataset.original = valor;

  switch (tipo) {
    case 'FECHA':
      input.type = 'date';
      input.value = valor ? fechaDeServidorAIso(valor) : '';
      break;
    case 'ENTERO':
      input.type = 'number';
      input.min = '1';
      input.step = '1';
      input.inputMode = 'numeric';
      input.value = valor;
      break;
    case 'MONTO':
      input.type = 'number';
      input.min = '0';
      input.step = 'any';
      input.inputMode = 'decimal';
      input.value = valor;
      break;
    default:
      input.type = 'text';
      input.autocomplete = 'off';
      input.value = valor;
  }

  contenedor.append(label, input);
  if (ayuda) {
    const textoAyuda = document.createElement('p');
    textoAyuda.className = 'campo__ayuda';
    textoAyuda.id = `${id}-ayuda`;
    textoAyuda.textContent = ayuda;
    input.setAttribute('aria-describedby', textoAyuda.id);
    contenedor.append(textoAyuda);
  }
  return contenedor;
}

/** Valor de un campo en el formato que espera el servidor. */
function valorParaServidor(input) {
  if (input.dataset.tipo === 'FECHA') {
    return input.value ? fechaIsoAServidor(input.value) : '';
  }
  return input.value.trim();
}

// ===================================================================== nueva reserva

function prepararNueva() {
  for (const tipo of TIPOS_DE_ALTA) {
    const opcion = document.createElement('label');
    opcion.className = 'opcion-tipo';
    opcion.dataset.tipo = tipo.clave;

    const radio = document.createElement('input');
    radio.type = 'radio';
    radio.name = 'tipo';
    radio.value = tipo.clave;
    radio.addEventListener('change', () => mostrarCamposDeAlta(tipo.clave));

    const icono = document.createElement('span');
    icono.setAttribute('aria-hidden', 'true');
    icono.innerHTML = ICONOS[tipo.clave];

    const nombre = document.createElement('span');
    nombre.textContent = tipo.nombre;

    opcion.append(radio, icono, nombre);
    elementos.opcionesTipo.append(opcion);
  }

  elementos.formNueva.addEventListener('submit', async (evento) => {
    evento.preventDefault();
    const form = elementos.formNueva;
    ocultarError(form);

    const datos = { tipo: form.elements.tipo.value };
    for (const input of elementos.camposNueva.querySelectorAll('.campo__control')) {
      datos[input.name] = valorParaServidor(input);
    }

    await enviar(form, async () => {
      const reserva = await api('POST', '/api/reservas', datos);
      elementos.dialogoNueva.close();
      await cargarReservas(reserva.id);
      avisar(`Reserva ${reserva.codigo} agregada`);
    });
  });
}

function abrirNueva() {
  const form = elementos.formNueva;
  ocultarError(form);
  form.elements.tipo.value = 'avion';
  elementos.camposNueva.replaceChildren(); // empieza vacío, sin lo escrito la vez anterior
  mostrarCamposDeAlta('avion');
  elementos.dialogoNueva.showModal();
  elementos.camposNueva.querySelector('input').focus();
}

function mostrarCamposDeAlta(clave) {
  const tipo = TIPOS_DE_ALTA.find((t) => t.clave === clave);
  // Se conservan los valores ya escritos en campos que existen en los dos tipos (código, fechas...).
  const anteriores = {};
  for (const input of elementos.camposNueva.querySelectorAll('.campo__control')) {
    anteriores[input.name] = valorParaServidor(input);
  }
  elementos.camposNueva.replaceChildren(...tipo.campos.map((campo) => crearCampo(campo, anteriores[campo.nombre] ?? '')));
}

// ===================================================================== editar reserva

function prepararEditar() {
  elementos.formEditar.addEventListener('submit', async (evento) => {
    evento.preventDefault();
    const form = elementos.formEditar;
    ocultarError(form);

    // Solo se envían los campos que cambiaron; el servidor los aplica todos juntos o ninguno.
    const cambios = {};
    for (const input of elementos.camposEditar.querySelectorAll('.campo__control')) {
      const valor = valorParaServidor(input);
      if (valor !== input.dataset.original) {
        cambios[input.name] = valor;
      }
    }
    if (Object.keys(cambios).length === 0) {
      elementos.dialogoEditar.close();
      return;
    }

    const id = estado.reservaSeleccionada.id;
    await enviar(form, async () => {
      const reserva = await api('PUT', `/api/reservas/${id}`, cambios);
      elementos.dialogoEditar.close();
      await cargarReservas(reserva.id);
      avisar('Cambios guardados');
    });
  });
}

/** El formulario se arma con los campos que informa la propia reserva: no depende de su tipo. */
function abrirEditar(reserva) {
  estado.reservaSeleccionada = reserva;
  ocultarError(elementos.formEditar);
  elementos.codigoEditar.textContent = reserva.codigo;
  elementos.camposEditar.replaceChildren(
    ...reserva.campos.map((campo, indice) =>
      crearCampo({ nombre: campo.nombre, etiqueta: campo.nombre, tipo: campo.tipo, completo: indice === 0 }, campo.valor)),
  );
  elementos.dialogoEditar.showModal();
  elementos.camposEditar.querySelector('input').focus();
}

// ===================================================================== eliminar reserva

function prepararEliminar() {
  elementos.formEliminar.addEventListener('submit', async (evento) => {
    evento.preventDefault();
    const form = elementos.formEliminar;
    ocultarError(form);
    const reserva = estado.reservaSeleccionada;

    await enviar(form, async () => {
      await api('DELETE', `/api/reservas/${reserva.id}`);
      elementos.dialogoEliminar.close();
      await cargarReservas();
      avisar(`Reserva ${reserva.codigo} eliminada`);
    });
  });
}

function abrirEliminar(reserva) {
  estado.reservaSeleccionada = reserva;
  ocultarError(elementos.formEliminar);
  elementos.codigoEliminar.textContent = reserva.codigo;
  const rango = textoDeRango(fechaDesdeIso(reserva.fechaInicio), fechaDesdeIso(reserva.fechaFin));
  elementos.textoEliminar.textContent =
    `${reserva.tipo}: ${reserva.detalle.replace(' -> ', ' → ')}, ${rango}. Esta acción no se puede deshacer.`;
  elementos.dialogoEliminar.showModal();
  // El foco empieza en "Cancelar" para que Enter no elimine por accidente.
  elementos.formEliminar.querySelector('[data-cerrar]').focus();
}

// ===================================================================== diálogos y avisos

function prepararDialogos() {
  for (const dialogo of document.querySelectorAll('dialog')) {
    // Clic en el fondo oscuro: cierra el diálogo.
    dialogo.addEventListener('click', (evento) => {
      if (evento.target === dialogo) {
        dialogo.close();
      }
    });
    for (const boton of dialogo.querySelectorAll('[data-cerrar]')) {
      boton.addEventListener('click', () => dialogo.close());
    }
  }
}

/** Deshabilita el botón de envío mientras se espera al servidor y muestra el error si lo hay. */
async function enviar(form, accion) {
  const boton = form.querySelector('[type="submit"]');
  boton.disabled = true;
  try {
    await accion();
  } catch (error) {
    manejarError(error, form);
  } finally {
    boton.disabled = false;
  }
}

let temporizadorAviso;

function avisar(texto) {
  elementos.aviso.textContent = texto;
  elementos.aviso.classList.add('aviso--visible');
  clearTimeout(temporizadorAviso);
  temporizadorAviso = setTimeout(() => elementos.aviso.classList.remove('aviso--visible'), 3000);
}

// ===================================================================== utilidades

/** "Avión" -> "avion", para elegir el ícono y el color. */
function claveDeTipo(tipo) {
  return tipo.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
}

/** "2026-12-15" -> fecha local (sin corrimientos por zona horaria). */
function fechaDesdeIso(iso) {
  const [anio, mes, dia] = iso.split('-').map(Number);
  return new Date(anio, mes - 1, dia);
}

/** "15/12/2026" (formato del servidor) -> "2026-12-15" (formato de los campos de fecha). */
function fechaDeServidorAIso(texto) {
  const [dia, mes, anio] = texto.split('/');
  return `${anio}-${mes}-${dia}`;
}

/** "2026-12-15" -> "15/12/2026". */
function fechaIsoAServidor(iso) {
  const [anio, mes, dia] = iso.split('-');
  return `${dia}/${mes}/${anio}`;
}

function textoDeRango(inicio, fin) {
  if (inicio.getTime() === fin.getTime()) {
    return formatoFecha.format(inicio);
  }
  return `${formatoFecha.format(inicio)} al ${formatoFecha.format(fin)}`;
}

function capitalizar(texto) {
  return texto.charAt(0).toUpperCase() + texto.slice(1);
}

function preferirMenosMovimiento() {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

// ===================================================================== inicio

async function iniciar() {
  prepararAcceso();
  prepararItinerario();
  prepararNueva();
  prepararEditar();
  prepararEliminar();
  prepararDialogos();

  // Si ya había una sesión abierta (por ejemplo, al recargar la página), entra directo.
  try {
    const { usuario } = await api('GET', '/api/sesion');
    if (usuario) {
      await entrar(usuario);
      return;
    }
    mostrarAcceso();
  } catch (error) {
    mostrarAcceso(error.message);
  }
}

iniciar();
