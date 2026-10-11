# Análisis inicial y drivers arquitectónicos

> Etapa previa a las iteraciones de ADD. Define el alcance del sistema **Trotamundos** y el conjunto priorizado de drivers (funcionalidad primaria, atributos de calidad y restricciones) que se mantendrán **inmutables** durante el proceso de diseño.

**Estado:** Completo
**Última actualización:** 11/10/2026

---

## 1. Objetivo

Identificar el alcance del sistema, sus límites e interacciones con actores externos, y priorizar los requerimientos funcionales y de atributos de calidad que guiarán las iteraciones de ADD.

---

## 2. Diagrama de contexto

```mermaid
flowchart LR
    V([Viajero<br/>web / móvil])
    D([Persona designada])
    E([Equipo de datos])

    T[[Trotamundos]]

    M[(Proveedores de correo<br/>Gmail, Outlook)]
    P[(Proveedores de viaje<br/>aerolíneas, hoteles, rentadoras)]
    A[(Agencia de viajes<br/>preferida del usuario)]
    R[(Redes sociales)]

    V -- consulta y gestiona reservas --> T
    D -- ve itinerarios compartidos --> T
    E -- consultas analíticas --> T

    T -- escanea correos de viaje --> M
    P -- actualizaciones y cancelaciones --> T
    T -- obtiene detalles de reservas --> P
    T -- deriva problemas --> A
    T -- comparte itinerario --> R
```

---

## 3. Requerimientos funcionales

### 3.1 Funcionalidad primaria (drivers de ADD)

| ID | Caso de uso | Justificación |
|---|---|---|
| CU-01 | Consultar el panel de viajes (web y móvil) | Es la propuesta de valor del producto ("único punto de verdad") y concentra la mayor parte del tráfico. Sobre él recaen los requisitos de tiempo de respuesta, disponibilidad y picos de carga. |
| CU-02 | Recibir actualizaciones y cancelaciones de proveedores | Es el camino del requisito de 5 minutos y el más afectado por disrupciones masivas. Obliga a una arquitectura orientada a eventos. |
| CU-03 | Incorporar reservas desde el correo | Es el diferencial del producto. Involucra seguridad (acceso al correo), interoperabilidad con proveedores de correo y el mayor desafío de escala. |
| CU-04 | Compartir un itinerario con personas designadas | Es la forma concreta que toma el requisito de seguridad y privacidad en el enunciado. |

### 3.2 Funcionalidad secundaria (se implementa, no guía el diseño)

| ID | Caso de uso | Tratamiento |
|---|---|---|
| CU-05 | Analítica de datos | Se diseña su aislamiento del sistema operativo; en el prototipo se limita a publicar eventos. |
| CU-06 | Gestión manual de reservas (alta, modificación, baja) | Se implementa completo. Es la forma más simple de cargar datos en el prototipo. |
| CU-07 | Agrupar reservas por viaje y archivado automático | Se resuelve con el modelo de dominio y una tarea programada. |
| CU-08 | Filtros y *whitelists* de remitentes | Paso de filtrado dentro del pipeline de CU-03. |
| CU-09 | Resolución de problemas con la agencia de viajes | Adaptador contra una agencia simulada. |

---

## 4. Atributos de calidad

Las medidas marcadas con \* fueron propuestas por el equipo para que el escenario sea verificable; el enunciado no las define.

| ID | Atributo | Escenario | Caso de uso | Prioridad | Dificultad |
|---|---|---|---|---|---|
| QA-1 | Performance | El sistema deberá mostrar en el panel las actualizaciones generadas por un proveedor externo en menos de 5 minutos. | CU-01, 02 | Alta | Alta |
| QA-2 | Performance | El sistema deberá responder las solicitudes del panel web en menos de 800 ms con carga normal. | CU-01 | Media | Baja |
| QA-3 | Performance | Cuando un usuario abre la aplicación móvil en un dispositivo de gama media con conexión 4G\*, el primer despliegue de contenido (*First Contentful Paint*) ocurre en menos de 1,4 segundos. | CU-01 | Baja | Media |
| QA-4 | Performance | Las consultas analíticas pesadas no deberán aumentar el tiempo de respuesta del panel en más de un 10%\*. | CU-05, 01 | Baja | Media |
| QA-5 | Disponibilidad | El sistema deberá tolerar la caída de una instancia o de una zona sin superar 5 minutos mensuales de inactividad no planificada (≈ 99,99%). | Todos | Alta | Alta |
| QA-6 | Disponibilidad | Cuando la API de un proveedor externo deja de responder durante la operación normal, el sistema sigue mostrando la última versión conocida del viaje y, dentro de los 5 minutos\* posteriores al restablecimiento de la API, recupera y procesa el 100% de los eventos generados durante la interrupción. | CU-01, 02 | Media | Media |
| QA-7 | Escalabilidad | El sistema deberá soportar 15 M de cuentas y 2 M de usuarios activos semanales y, ante picos de hasta ×10\*, seguir cumpliendo QA-1 y QA-2. | CU-01, 02 | Alta | Alta |
| QA-8 | Escalabilidad | Cuando la base de cuentas crece hasta 15 M durante la operación normal, el sistema, incorporando recursos de procesamiento, procesa el correo de los usuarios con viajes próximos con una demora máxima de 15 minutos\* para el 95%\* de ellos. | CU-03 | Media | Media |
| QA-9 | Interoperabilidad | Cuando los sistemas de proveedores externos envían notificaciones mediante sus APIs estándar durante la operación normal, el sistema procesa correctamente el 100% de los mensajes válidos e identifica y registra el 100% de los inválidos, sin interrumpir la integración con el resto de los proveedores. | CU-02 | Alta | Media |
| QA-10 | Interoperabilidad | El sistema deberá extraer correctamente la reserva del 95%\* de los emails de remitentes en la *whitelist*, dentro de los formatos soportados. | CU-03 | Media | Media |
| QA-11 | Seguridad | Cuando un usuario revoca el acceso a su correo durante la operación normal, el sistema deja de leer ese correo en menos de 1 minuto\* y no realiza ninguna lectura posterior con el token revocado (0 accesos). | CU-03 | Media | Media |
| QA-12 | Seguridad | Solo las personas designadas deberán poder ver un itinerario compartido; el sistema rechazará y registrará el 100% de los accesos no autorizados. | CU-04 | Baja | Baja |
| QA-13 | Evolucionabilidad | Cuando el negocio decide incorporar un nuevo medio de transporte (por ejemplo, trenes), el sistema lo soporta sin modificar los componentes existentes del núcleo, con un esfuerzo máximo de 2 días-persona\*. | CU-02, 03 | Media | Baja |
| QA-14 | Usabilidad / internacionalizacionn | Cuando un usuario de cualquiera de las regiones soportadas abre su itinerario durante la operación normal, el sistema muestra el 100% de las fechas, horas y monedas en el formato y la zona horaria de su región. | CU-01 | Baja | Baja |
| QA-15 | Interoperabilidad | Cuando un usuario comparte un itinerario en una red social durante la operación normal, el sistema genera un enlace de solo lectura en menos de 2 segundos\*, y el 100% de los accesos realizados con ese enlace es de solo lectura (ninguna modificación). | CU-04 | Baja | Baja |
| QA-16 | Usabilidad | Cuando un viajero con un viaje en curso abre el mapa del viaje en la aplicación móvil con el GPS activo, el sistema muestra su ubicación actual junto con los puntos de su itinerario (hotel, terminal, estación, agencia de alquiler) y le permite iniciar la ruta hacia el próximo punto en no más de 2 interacciones\* y menos de 3 segundos\*. Si el GPS está desactivado o no hay conexión, el sistema sigue mostrando los puntos del itinerario guardados, sin la ubicación del usuario. | CU-01 | Baja | Media |

### 4.1 Criterio de priorización

- **Prioridad:** importancia para el objetivo central del producto, ofrecer un único punto de verdad, actualizado, para los arreglos de viaje.
- **Dificultad:** incertidumbre técnica sobre la posibilidad de cumplir el escenario (riesgo técnico, en terminología del SEI), no cantidad de trabajo.

## 5. Restricciones del sistema

Decisiones que el diseño debe respetar y que no se discuten durante ADD.

| ID | Restricción | Tipo |
|---|---|---|
| R-1 | El sistema debe ser multiplataforma: accesible desde navegadores web (multibrowser) y desde dispositivos móviles. | Técnica |
| R-2 | La integración con aerolíneas, hoteles y rentadoras debe realizarse a través de las APIs estándar que esos proveedores ya exponen. El sistema se adapta a sus contratos; no puede modificarlos. | Técnica |
| R-3 | El acceso al correo de los usuarios debe hacerse mediante las APIs de los proveedores de correo y con autorización delegada del usuario, sin solicitar ni almacenar sus contraseñas. | Técnica |
| R-4 | El almacenamiento operativo debe utilizar una base de datos relacional (SQL). | Técnica (autoimpuesta) |
| R-5 | El sistema depende de sistemas externos que no controla (proveedores de viaje, correo y agencias): su disponibilidad, formatos y límites de uso son condiciones dadas. | Técnica |
| R-6 | Al procesar información personal y operar en el mercado internacional, el sistema debe cumplir las normativas de protección de datos de cada región donde opere. | Legal |

---

## 6. Preocupaciones arquitectónicas

Aspectos que el diseño debe resolver aunque no aparezcan como requerimiento explícito.

| ID | Preocupación | Relación |
|---|---|---|
| PA-1 | Mecanismo de Autenticación y autorización de usuarios: solo el titular puede acceder y/o compartir a sus reservas y a la información extraída de su correo. | QA-11, QA-12 |
| PA-2 | Manejo de fechas y horas: todos los horarios se almacenan en UTC junto con la zona horaria de origen, ya que los viajes cruzan husos horarios. | QA-14 |
| PA-3 | Registro y auditoría de accesos y de errores de integración con sistemas externos. | QA-9, QA-12 |
| PA-4 | Versionado de las APIs propias, para poder evolucionar el sistema sin romper los clientes web y móvil. | QA-13 |

---

## 7. Supuestos a validar con la cátedra

- Los tiempos de QA-1 y QA-2 se toman tal como los da el enunciado. Al medirlos en el prototipo, se considerará cumplido el escenario si la gran mayoría de las solicitudes (por ejemplo, el 95%) queda por debajo del umbral, ya que garantizarlo para el 100% no es posible.
- El factor ×10 de pico es un orden de magnitud para traducir los picos "dramáticos" del enunciado.
- El plazo de 5 minutos se interpreta como aplicable a las actualizaciones de proveedores; para el correo se propone un máximo de 15 minutos. Aunque cada consulta sea incremental (solo mensajes nuevos), sondear 15 M de casillas cada 5 minutos implicaría unas 50.000 consultas por segundo, la mayoría sin novedades. Por eso el diseño prioriza las notificaciones push de los proveedores de correo, que permiten consultar solo las casillas que recibieron mensajes.
- Las prioridades fueron estimadas por el equipo en ausencia de stakeholders.
