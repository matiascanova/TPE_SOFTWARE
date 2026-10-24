# ADR-0006: Almacén analítico separado de la base operacional

- **Estado:** Propuesto
- **Fecha:** 19/10/2026
- **Decisores:** Equipo de diseño (grupo TPE)
- **Drivers de entrada:** Análisis inicial y drivers (CU-05; QA-1, QA-2, QA-4, QA-6 y QA-12 como atributos que la analítica podría afectar; R-4), ADR-0001, ADR-0002 y ADR-0003.

*El número de ADR es provisional; ajustarlo al siguiente libre.*

---

## 1. Contexto y problema

El CU-05 (analítica de datos) es funcionalidad secundaria: se implementa pero no guía el diseño. No tiene ningún QA propio, no lo usan los viajeros y lo consume solo el equipo de datos, que hace consultas analíticas sobre el sistema (ver diagrama de contexto). El ADR-0001 dejó una preocupación de diseño: las consultas pesadas de la analítica no deben degradar el panel.

Este ADR responde dos preguntas: **dónde corren las consultas analíticas** y **cómo llegan los datos hasta ahí**. Hay cinco tensiones:

1. **Cargas de trabajo distintas.** La base operacional hace muchas operaciones pequeñas (guardar una reserva, leer los viajes de un usuario). La analítica hace agregaciones sobre millones de filas (destinos frecuentes, volumen de reservas por proveedor, tasa de interpretación de correos).
2. **Aislamiento de carga.** Una consulta analítica pesada sobre la base operacional compite con el panel (QA-2), con la ingesta (QA-1) y con el crecimiento a 15 M de cuentas (QA-6). La base ya es un punto de presión (ADR-0001, riesgo de base compartida).
3. **Aislamiento de fallas.** Una consulta descontrolada o una carga fallida de la analítica no debería afectar a la base operacional (QA-4).
4. **Datos personales.** El equipo de datos necesita patrones, no identidades. Dejarle acceso al modelo operacional completo contradice la minimización que exige R-4.
5. **Simplicidad.** Es funcionalidad secundaria y el prototipo debe ser implementable en el plazo del TPE. La solución no debe agregar piezas que la analítica no necesite.

**Alcance.** Este ADR decide la separación del almacén y el mecanismo de alimentación. No decide el motor concreto (sección 6) ni el esquema analítico.

**Qué significa OLAP acá.** El diagrama llama "Almacén OLAP" al almacén analítico. OLAP se refiere al tipo de uso (consultas analíticas sobre grandes volúmenes), no a un producto: el motor queda abierto.

## 2. Criterios de decisión

| Criterio | Peso | Qué mide en esta decisión |
|---|---|---|
| Aislamiento de carga (QA-1, QA-2, QA-6) | 3 | Que las consultas analíticas pesadas no degraden el panel ni la ingesta. |
| Aislamiento de fallas (QA-4) | 3 | Que un problema de la analítica no afecte a la base operacional. |
| QA-12 (evolucionabilidad) | 2 | Cambiar el motor, sumar fuentes o agregar historia sin tocar los componentes operativos. |

Los pesos siguen la columna "Prioridad" del análisis inicial (Alta = 3, Media = 2, Baja = 1), igual que en el ADR-0001. Cuando un criterio agrupa varios QA se toma la prioridad más alta.

**No puntuado.** R-4 es una restricción y no un QA, así que no se puntúa: se verifica en la sección 3.1 para cada alternativa. QA-3 y QA-9 no se ven afectados por esta decisión.

**Escala de puntaje (0 a 3):** 0 no cubre el criterio, 1 lo cubre de forma parcial o frágil, 2 lo cubre bien con salvedades, 3 lo cubre de forma natural.

**Método en dos pasos:**

1. **Filtro de viabilidad**, con una dificultad subjetiva de implementación y operación para el equipo.
2. **Puntaje ponderado de QA** entre las alternativas que pasan el filtro.

## 3. Alternativas consideradas

| ID | Alternativa | Resumen |
|---|---|---|
| 1 | Consultas directas sobre la base operacional | El equipo de datos usa un usuario de solo lectura sobre la misma base. |
| 2 | Réplica de lectura de la base operacional | Una réplica con el mismo motor y el mismo esquema. Las consultas analíticas van a la réplica. |
| 3 | **Almacén analítico propio, alimentado por copia periódica** | Una base separada con esquema analítico. Una carga programada copia datos de forma incremental desde la base operacional. |
| 4 | Almacén analítico propio, alimentado por el log | Una base separada. Un consumidor lee el stream de eventos del broker y la carga. |

## 4. Decisión

**Se elige un almacén analítico (OLAP) propio, separado de la base operacional y alimentado por una carga programada que copia datos de forma incremental desde ella (alternativa 3). La analítica no consume el broker.**

### Reglas de la decisión

- **Separación física.** El almacén es una base distinta, con credenciales distintas. El equipo de datos accede solo al almacén analítico, nunca a la base operacional.
- **Carga programada.** Una tarea (tercer desplegable del ADR-0001, "carga de analítica") copia los datos de forma incremental, usando la marca de versión o de modificación de cada registro. Es idempotente: repetirla no duplica datos (upsert por identificador).
- **Frecuencia.** A definir con el equipo de datos, porque CU-05 no fija un requisito de frescura. Punto de partida sugerido: una vez por día fuera de la hora pico.
- **Fuente.** La base operacional, que contiene tanto los cambios de la ingesta como las ediciones manuales de los viajeros (CU-06). No usa el broker ni el outbox, y por eso el Panel API no necesita publicar eventos para la analítica.
- **Acceso a la fuente.** La carga lee desde vistas de solo lectura documentadas, no desde las tablas internas de otros módulos (ADR-0001, riesgo de base compartida). En producción lee desde una réplica de lectura, si existe, para no cargar a la primaria.
- **Minimización de datos (R-4).** La carga transforma los datos: seudonimiza los identificadores de usuario y excluye los campos personales que el análisis no necesita (nombre, correo, detalle libre).
- **Esquema propio.** El almacén tiene un esquema analítico simple, no un espejo del operacional, y está versionado.
- **No es fuente de verdad.** El almacén puede reconstruirse completo desde la base operacional.
- **Motor abierto.** Para el prototipo basta una segunda instancia o base de PostgreSQL, con las mismas herramientas que el resto del sistema. Un motor columnar (por ejemplo ClickHouse o BigQuery) es una evolución para cuando el volumen lo justifique (sección 6).

### 4.1 Por qué no consultas directas sobre la base operacional (alternativa 1)

Es la más simple, pero no resuelve ninguna de las tensiones: las consultas pesadas compiten con el panel y la ingesta por la misma base, una consulta descontrolada puede afectar a todo el sistema (QA-1, QA-2, QA-4, QA-6) y el equipo de datos accede a datos personales completos (R-4).

### 4.2 Por qué no una réplica de lectura (alternativa 2)

La réplica aísla bien la carga: las consultas pesadas no tocan a la primaria. Es la opción intermedia más razonable y por eso queda como **complemento** de la decisión (la carga puede leer desde ella en producción). Como solución única tiene tres límites:

- Tiene el mismo esquema y el mismo motor, optimizados para transacciones y no para agregaciones.
- Contiene todos los datos personales, y restringirlos depende de mantener vistas y permisos (R-4).
- La analítica queda atada al esquema operacional: cualquier cambio del esquema la afecta, y no se puede cambiar el motor ni agregar historia sin tocar el lado operativo (QA-12).

### 4.3 Por qué no alimentarse del log (alternativa 4)

Es la mejor en QA por un margen pequeño (2 de 24 puntos), gracias a la historia de cambios y a una frescura casi inmediata. Se descarta para esta etapa porque su costo no se justifica para una funcionalidad secundaria y sin requisitos de tiempo:

- Exige un consumidor, un contrato de eventos analíticos y que el Panel API publique también las ediciones manuales como eventos, lo que le agrega un segundo rol de productor.
- Ata la analítica a la ventana de retención del log: si el consumidor se retrasa más que la ventana, pierde datos (ADR-0003, B-5).
- Es más piezas y más contratos para un trabajo con plazo acotado.

## 5. Consecuencias

### Positivas

- Las consultas analíticas no tocan la base operacional: el panel y la ingesta quedan protegidos (QA-1, QA-2, QA-6).
- Una falla o una consulta descontrolada de la analítica no afecta a la operación (QA-4).
- El broker queda enfocado en la ingesta y no depende de la analítica (ADR-0003).
- El equipo de datos trabaja con datos seudonimizados y mínimos, lo que ayuda a cumplir R-4.
- El motor del almacén y su esquema pueden cambiar sin tocar los componentes operativos (QA-12).
- Es simple de construir y de demostrar: una tarea programada y una segunda base, ejecutables con docker-compose.

### Negativas y riesgos aceptados

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| A-1 | Datos con retraso | Las consultas reflejan el estado de la última carga y no sirven para tiempo real. | CU-05 no tiene requisito de frescura. Frecuencia configurable. Evolución a la alternativa 4 si hace falta. |
| A-2 | Solo estado actual, sin historia fina | No se pueden analizar secuencias de cambios de una misma reserva. | Usar la tabla de auditoría de la base como fuente adicional. Evolución a la alternativa 4 para granularidad de evento. |
| A-3 | La carga lee la base operacional | Carga adicional sobre la base compartida. | Carga incremental, fuera de hora pico y con ritmo limitado. Réplica de lectura en producción. Índice por marca de modificación. |
| A-4 | Acoplamiento al esquema operacional | Un cambio de esquema puede romper la carga. | Lectura por vistas de solo lectura documentadas. Migraciones versionadas y prueba de la carga en CI. |
| A-5 | Datos duplicados en dos almacenes | Costo de almacenamiento y riesgo de inconsistencias. | El almacén no es fuente de verdad y se puede reconstruir. Verificación de conteos en cada carga. |
| A-6 | Datos personales en el almacén (R-4) | Residencia por región y pedidos de borrado. | Seudonimización y minimización. Carga y almacén por región. La carga propaga los borrados de usuarios. |
| A-7 | Otro almacén para operar | Complejidad operativa. | Una sola instancia en el prototipo, con un motor conocido por el equipo. |

## 6. Decisión abierta: motor del almacén

La Iteración 1 deja pendiente la elección del almacén OLAP. Este ADR fija la separación y el mecanismo de carga, y deja abiertas estas dos opciones:

| Opción | Cuándo conviene |
|---|---|
| Segunda instancia o base de PostgreSQL | Prototipo y volúmenes moderados. Mismas herramientas y conocimiento que el resto del sistema, y se levanta con docker-compose. Las agregaciones sobre muchos millones de filas se vuelven lentas. |
| Motor columnar (ClickHouse, BigQuery o similar) | Cuando el volumen o la cantidad de consultas del equipo de datos hagan lentas las agregaciones. Un componente más para operar y, en el caso de BigQuery, cuenta cloud. |

**Inclinación preliminar.** Empezar con PostgreSQL en el prototipo y documentar el motor columnar como evolución. Como la analítica solo se conecta al almacén por la carga y por las consultas del equipo de datos, el cambio de motor no toca el resto del sistema.

**Datos que faltan para cerrarla:** volumen esperado de datos analíticos, cantidad y tipo de consultas del equipo de datos y herramientas de análisis que ya use.

## 7. Relación con otros ADR y próximos pasos

- **ADR-0001:** define el tercer desplegable ("carga de analítica") y la base compartida, cuyos riesgos este ADR respeta.
- **ADR-0002:** define la base operacional. Debe prever las vistas de solo lectura para la carga, el campo de versión o modificación en cada tabla y la tabla de auditoría.
- **ADR-0003:** deja a la analítica fuera del log. Su evolución (consumir el stream de eventos) se apoya en ese ADR.
- **Análisis inicial:** el tratamiento de CU-05 dice "en el prototipo se limita a publicar eventos". Con este ADR pasa a ser "se limita a copiar periódicamente los datos a un almacén analítico propio".
- **ADR de motor del almacén:** cerrar la sección 6.
- **ADR de multi-región:** residencia de datos y borrado en el almacén analítico (A-6).
