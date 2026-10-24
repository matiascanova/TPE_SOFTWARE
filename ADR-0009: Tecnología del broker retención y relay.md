# ADR-0009: Tecnología del broker, retención y relay

- **Estado:** Propuesto
- **Fecha:** 24/10/2026
- **Decisores:** Equipo de diseño (grupo TPE)
- **Drivers de entrada:** Análisis inicial y drivers e Iteración 1: CU-01, CU-02 y CU-03. Otros drivers: QA-1, QA-5, QA-6, QA-7, R-4, R-6, CRN-2 y CRN-N3.
- **ADR relacionados:** ADR-0002 (workers y outbox), ADR-0003 (broker), ADR-0004 (read model), ADR-0005 (motor de la BD), ADR-0007 (contratos de mensajes y semántica de entrega), ADR-0008 (resiliencia de la ingesta por proveedor)

---

## 1. Contexto y problema

El ADR-0003 eligió un broker con log persistente (los mensajes quedan ordenados y retenidos, y cada consumidor lleva su posición de lectura) y dejó abierta la tecnología entre Redis Streams y RabbitMQ Streams. También dejó sin valor la retención del log y pidió revisar en esta iteración si el replay justifica un log frente a una cola (BR-5). El ADR-0002 fijó un relay que lee el outbox «cada pocos segundos», y la Iteración 1 registra el intervalo del relay y el presupuesto de tiempo por etapa como pendientes de QA-1. Este documento va último en la iteración 2 porque depende de los anteriores: el ADR-0007 fija qué se retiene, y el ADR-0008 fija la estructura de streams, el contador de entregas que necesita y la ventana de reconciliación de la que sale la retención.

Este documento responde tres preguntas:

**Decisión A:** **¿Qué tecnología implementa el broker con log persistente?**

**Decisión B:** **¿Cuánto tiempo retiene cada stream sus mensajes?**

**Decisión C:** **¿Con qué intervalo lee el relay el outbox?**

Se deciden juntas porque la tecnología condiciona cómo se implementa la retención (en memoria o en disco), y el intervalo del relay se elige contra el mismo presupuesto de tiempo que justifica la tecnología.

**Tensiones:**

- **Conocimiento del equipo frente a capacidad de retención.** Redis ya está en el sistema por el read model (ADR-0004), pero guarda los datos en memoria. RabbitMQ Streams guarda en disco, pero es una tecnología nueva para el grupo (CRN-2, R-6).
- **Retención larga frente a datos personales y costo.** Retener más tiempo permite absorber interrupciones largas y reprocesar (QA-5), pero retiene notificaciones crudas con datos personales (R-4, ADR-0007 MS-2) y, en memoria, cuesta más con el volumen de 15 M de cuentas (QA-6, QA-7).
- **Durabilidad frente a latencia.** El receptor responde al proveedor solo cuando el broker confirmó el encolado (ADR-0007). Si el broker confirma algo que no persistió, una caída lo pierde y el proveedor no reintenta (QA-5). Persistir antes de confirmar agrega demora (QA-1).
- **Frescura frente a carga sobre la BD.** Un relay que lee más seguido consume menos de los 5 minutos de QA-1, pero suma consultas a la BD compartida (ADR-0005, BD-1).
- **Una pieza más que operar.** El broker queda en el camino de entrada (ADR-0003, BR-4) y debe poder operarse en el prototipo y en producción (CRN-N3).

**Fuera de alcance:**

- Estructura lógica de los streams, particiones por proveedor, reintentos, apartados y ventana de reconciliación (ADR-0008). Este documento las implementa.
- Campos de los mensajes y semántica de entrega (ADR-0007).
- Despliegue en alta disponibilidad, multi-región y monitoreo del broker en producción (iteración 3, CRN-N3).
- Pipeline de ingesta de correo. La retención del stream de trabajo de correo queda provisional hasta ese ADR.
- La plataforma de streaming completa (Kafka o equivalente), descartada en el ADR-0003 (CO5), no se reabre.
- Los valores marcados con \* son propuestas del equipo para el prototipo y se ajustan con pruebas de carga.

---

## 2. Criterios de decisión

| Criterio | Qué mide en esta decisión | Se usa en |
| --- | --- | --- |
| QA-1 | Cuánto del presupuesto de 5 minutos consumen el broker y el relay, y si la demora por etapa se puede medir. | A / C |
| QA-5 | Si un mensaje confirmado sobrevive a una caída del broker, y si la retención cubre la interrupción que se quiere absorber. | A / B |
| QA-6 y QA-7 | Si la retención y el broker sostienen el volumen de 15 M de cuentas y los picos ×10. | A / B |
| R-4 | Si los datos personales se retienen solo el tiempo necesario. | B |
| R-6 y CRN-2 | Si el grupo conoce la tecnología y puede levantarla y demostrarla en el plazo. | A / C |
| CRN-N3 | Piezas y tecnologías distintas que hay que operar, y si la tecnología da de forma nativa lo que el ADR-0008 necesita. | A / C |

---

## 3. Decisión A: tecnología del broker

### 3.1 Alternativas consideradas

| ID | Alternativa | Resumen |
| --- | --- | --- |
| T1 | **Redis Streams en una instancia dedicada** | Streams de Redis con grupos de consumo, en una instancia de Redis separada de la del read model y configurada para no expulsar datos. |
| T2 | Redis Streams en la instancia del read model | Los streams viven en la misma instancia de Redis que el read model del ADR-0004. |
| T3 | RabbitMQ Streams | Streams de RabbitMQ con retención en disco, réplicas y streams particionados (*super streams*) con un consumidor activo por partición. |
| T4 | Cola durable (colas *quorum* de RabbitMQ) | Se abandona el log: cada mensaje se elimina al confirmarse. Es la vuelta a CO3 que el ADR-0003 dejó abierta en BR-5. |

### 3.2 Decisión

**Se elige T1: el broker se implementa con Redis Streams en una instancia de Redis dedicada, separada del read model, que no expulsa datos y que persiste cada mensaje antes de confirmarlo.**

**Descripción de la decisión.** Redis Streams es un log en el que cada mensaje tiene un identificador que empieza con su hora de llegada en milisegundos. Un grupo de consumo reparte los mensajes entre consumidores y guarda, por cada mensaje entregado y no confirmado, a quién se entregó y cuántas veces (la lista de pendientes). Esa lista da de forma nativa el contador de entregas que el ADR-0008 necesita para agotar un mensaje sin que se pierda la cuenta si el worker se cae. Es la implementación del conector *Publish/Subscribe* del ADR-0003. Aplica el patrón de redundancia pasiva de la cátedra con la táctica *Redundant Spare* (réplica en producción), y las tácticas *Bound Queue Sizes* (memoria y retención acotadas), *Heartbeat* (el arriendo de cada partición se renueva y, si deja de renovarse, el worker se da por caído) y *Encapsulate* (solo el adaptador del puerto de mensajería conoce Redis).

- **Instancia dedicada sin expulsión.** El broker no comparte instancia con el read model, que expulsa entradas por uso (LRU, ADR-0004). El broker usa la política `noeviction`: si se llena la memoria, la escritura falla, el receptor responde con error y el proveedor reintenta (ADR-0007). La falla es visible y no hay pérdida silenciosa.
- **Confirmar solo lo persistido.** El broker escribe cada mensaje en su registro en disco antes de responder (`appendfsync always`). En producción suma una réplica, y el receptor espera que la réplica tenga el mensaje (`WAIT`) antes de responder al proveedor.
- **Mapeo de la estructura del ADR-0008.** Cada partición del stream de trabajo es un stream propio (`trabajo:<proveedor>:<n>`). Hay un stream `eventos` y dos de apartados (`apartados:trabajo` y `apartados:eventos`). Los grupos de consumo son `workers` y `panel-api`. Cada partición tiene un solo consumidor activo mediante un arriendo: una clave con vencimiento que el worker renueva. Si el worker se cae, el arriendo vence, otro worker toma la partición y reclama sus pendientes (`XAUTOCLAIM`).
- **Contador de entregas del broker.** El agotamiento del ADR-0008 usa el contador de entregas de la lista de pendientes (`XPENDING`), que vive en el broker y sobrevive a la caída del worker.
- **Versión y puerto.** Se usa Redis 6.2 o posterior, que incluye la reclamación automática de pendientes y el recorte por identificador mínimo. Solo el adaptador de infraestructura del puerto de mensajería conoce Redis (ADR-0003). En el prototipo es un contenedor más en docker-compose.

### 3.3 Cómo funciona

Un worker toma la partición `trabajo:aerolinea-x:2` y se cae con un mensaje entregado y sin confirmar.

1. El worker tenía el arriendo de la partición y lo renovaba cada pocos segundos. Al caerse deja de renovarlo.
2. Al vencer el arriendo, otro worker lo toma y reclama los pendientes de la partición con `XAUTOCLAIM`. El contador de entregas del mensaje sube de 1 a 2.
3. El nuevo worker procesa el mensaje. Si el mensaje es el que tumbó al primero y vuelve a fallar, el contador sigue subiendo y en la quinta entrega se aparta como «agotado» (ADR-0008).

| Qué falla | Qué pasa | Resultado |
| --- | --- | --- |
| Se cae el broker. | El receptor no puede encolar y responde con error. Los workers y el Panel API no reciben mensajes. El relay deja los eventos pendientes en el outbox. | El panel sigue sirviendo el read model (ADR-0003, BR-4). Al volver, todo sigue desde lo persistido. |
| El broker reinicia. | Recupera los mensajes desde su registro en disco, incluidos los confirmados un instante antes. | No se pierde lo que el receptor ya confirmó al proveedor. |
| Se llena la memoria del broker. | Las escrituras fallan y el receptor responde con error. Salta la alerta de memoria. | El proveedor reintenta. No se expulsan mensajes. |
| Se cae un worker. | Su arriendo vence y otro worker reclama sus pendientes. | El orden por partición se mantiene y no se pierde la cuenta de entregas. |

### 3.4 Por qué no las otras alternativas

- **T2. Redis Streams en la instancia del read model.** El read model expulsa entradas por uso (ADR-0004), y esa política puede expulsar un stream completo con trabajo o eventos sin confirmar, que es una pérdida silenciosa (QA-5). Además, un atraso del broker ocuparía la memoria del read model y haría más lento al panel (CU-01), y una sola caída dejaría sin ingesta y con el panel degradado a la vez (CRN-N3).
- **T3. RabbitMQ Streams.** Es la mejor en retención, porque guarda en disco con réplicas, y sus streams particionados dan el orden por clave sin código propio. Pero es una tecnología nueva para el grupo y una segunda tecnología de infraestructura para operar, cuando Redis ya está en el sistema (R-6, CRN-2, CRN-N3). Tampoco confirma por mensaje ni cuenta entregas: el consumidor solo guarda su posición en el log, así que el contador de entregas del ADR-0008 habría que llevarlo aparte, y llevado en memoria, un mensaje que tumba al worker se reintentaría sin fin. Queda como evolución prevista: usa el mismo modelo de log con posiciones por consumidor y el puerto de mensajería aísla el cambio. Se pasa a T3 si la memoria proyectada del broker supera la capacidad de una instancia (BK-1) o si se necesita retener más de unos días.
- **T4. Cola durable.** Cierra BR-5 eliminando el replay, pero el diseño ya lo usa en tres lugares: reprocesar un lote mal interpretado después de corregir el adaptador (ADR-0007, A2), releer eventos si el Panel API estuvo caído más de lo esperado (ADR-0004) y retener el trabajo de un proveedor pausado mientras dura la interrupción (ADR-0008). También suma una tecnología nueva (R-6).

---

## 4. Decisión B: retención por tiempo y por stream

### 4.1 Alternativas consideradas

| ID | Alternativa | Resumen |
| --- | --- | --- |
| R1 | Retención por cantidad, igual para todos | Cada stream conserva sus últimos N mensajes. |
| R2 | **Retención por tiempo, distinta por stream, derivada de la ventana de reconciliación** | Cada stream conserva sus mensajes durante un plazo que sale de para qué se usa ese stream. |
| R3 | Retención larga uniforme | Todos los streams conservan sus mensajes 7 días. |
| R4 | Sin retención | Cada mensaje se borra al confirmarse. |

### 4.2 Decisión

**Se elige R2: cada stream retiene sus mensajes por tiempo, con un plazo propio. El stream de trabajo de proveedores de viaje retiene al menos la ventana de reconciliación del ADR-0008 más un margen.**

**Descripción de la decisión.** El log no es la fuente de verdad (lo es la BD, ADR-0003), así que cada stream retiene solo lo que necesita su uso: absorber una interrupción, reprocesar o releer. Fuera de ese plazo, la recuperación es la reconciliación por estado actual (ADR-0008) y la reconstrucción del read model desde la BD (ADR-0004).

| Stream | Retención | Por qué |
| --- | --- | --- |
| Trabajo de proveedores de viaje | 48 h\* | La ventana de reconciliación de 24 h (ADR-0008) más 24 h para drenar lo retenido y para reprocesar un lote mal interpretado que se detecta al día siguiente (ADR-0007, A2). |
| Trabajo de correo | 24 h\* (provisional) | Los proveedores de correo exponen consultas de cambios desde una marca de la casilla, así que el log no es la única fuente. Su volumen es mucho mayor. Lo revisa el ADR del pipeline de correo. |
| Eventos | 24 h\* | Fuera de esa ventana el read model se reconstruye desde la BD (ADR-0004). El evento no lleva datos que no estén en la BD (ADR-0007). |
| Apartados (de trabajo y de eventos) | 7 días\* | Una semana para corregir un adaptador y reprocesar (ADR-0008). Su volumen es bajo. El registro en la BD conserva los metadatos más tiempo. |

- **Recorte por tiempo.** Como el identificador de cada mensaje empieza con su hora de llegada, una tarea recorta cada minuto los mensajes anteriores a «ahora menos la retención» (`XTRIM` con `MINID` aproximado).
- **La retención sigue a la ventana.** La retención del trabajo de proveedores nunca es menor que la ventana de reconciliación del ADR-0008. Cambiar una obliga a revisar la otra.
- **Alerta antes del recorte.** Si el mensaje pendiente más viejo de una partición pausada se acerca a la retención, salta una alerta. Si igual se recorta, ese proveedor pasa a reconciliación por estado actual (ADR-0008).
- **Memoria dimensionada y vigilada.** La memoria del broker se dimensiona como tasa de mensajes por retención por tamaño medio, por stream. Una alerta salta al 70% de la memoria máxima\*.
- **Versiones de contrato acotadas.** Como ningún stream retiene más de 7 días, una versión vieja del contrato se puede retirar 7 días después de que el último productor deja de usarla (ADR-0007, MS-4).

### 4.3 Cómo funciona

Orden de magnitud con supuestos del equipo: si los proveedores de viaje generan en promedio 20 notificaciones por segundo de unos 2 KB, 48 h de trabajo son unos 3,5 M de mensajes, del orden de 7 GB de memoria. Los eventos, de unos 300 bytes, ocupan menos de 1 GB en 24 h. En el prototipo, con proveedores simulados, el volumen es varios órdenes menor. El stream de correo es el que puede no entrar en memoria a escala de 15 M de cuentas, y por eso queda como disparador de la evolución a T3 (BK-1).

### 4.4 Por qué no las otras alternativas

- **R1. Retención por cantidad.** Una cantidad fija no equivale a un tiempo fijo: en un pico ×10 cubre diez veces menos tiempo, y los picos coinciden con las disrupciones que provocan interrupciones (QA-6). Justo cuando un proveedor está pausado, sus mensajes se recortarían antes de tiempo (QA-5).
- **R3. Retención larga uniforme.** Multiplica por 3,5 la memoria del stream de trabajo, que es el más grande (QA-6, QA-7), y extiende la exposición de notificaciones crudas con datos personales (R-4). Para los eventos no aporta: después de 24 h es más simple reconstruir el read model desde la BD.
- **R4. Sin retención.** Equivale a una cola y elimina el replay que usan el ADR-0004, el ADR-0007 y el ADR-0008 (ver T4). Además, un proveedor pausado no tendría dónde esperar.

---

## 5. Decisión C: intervalo del relay

### 5.1 Alternativas consideradas

| ID | Alternativa | Resumen |
| --- | --- | --- |
| P1 | Lectura cada 5 s | El relay lee las filas pendientes del outbox a intervalo fijo de 5 s. |
| P2 | **Lectura cada 1 s, con relectura inmediata si el lote vino lleno** | El relay espera 1 s solo cuando la última lectura trajo menos de un lote. Si trajo un lote completo, vuelve a leer sin esperar. |
| P3 | Aviso de PostgreSQL con lectura de respaldo | Cada transacción que escribe en el outbox avisa al relay (`LISTEN/NOTIFY`), y el relay además lee a intervalo largo por si pierde un aviso. |
| P4 | Captura de cambios (CDC) | Un conector lee el registro de transacciones de PostgreSQL y publica los eventos del outbox. |

### 5.2 Decisión

**Se elige P2: el relay lee el outbox en lotes de hasta 500 filas\*, espera 1 s\* cuando la última lectura trajo menos de un lote y vuelve a leer en el acto cuando trajo un lote completo.**

**Descripción de la decisión.** En operación normal el relay suma como máximo 1 s de demora. Ante un atraso, por ejemplo después de una caída del broker, lee lotes completos seguidos hasta ponerse al día, sin que el intervalo limite cuánto publica. Aplica la táctica *Manage Work Requests*: el intervalo es la frecuencia con la que se revisa el outbox.

- **Lectura sin choques entre instancias.** El relay lee las filas pendientes en orden de creación con bloqueo de filas que saltea las ya tomadas (`FOR UPDATE SKIP LOCKED`), así varias instancias no toman las mismas (ADR-0002). Un índice parcial sobre las filas pendientes mantiene la lectura barata aunque la tabla crezca.
- **Publicación en lote y marca tardía.** El relay publica el lote en una sola ida y vuelta al broker y marca las filas como publicadas después de que el broker confirma (ADR-0007).
- **Presupuesto por etapa.** El intervalo se elige contra el reparto de los 5 minutos de QA-1 que pide el ADR-0003 (BR-1). Cada etapa tiene una alerta cuando su demora supera su parte.

| Etapa | Presupuesto en operación normal\* |
| --- | --- |
| Receptor: verificar, encolar y responder | 1 s |
| Espera en el stream de trabajo y procesamiento del worker, con los reintentos del ADR-0008 | 60 s |
| Outbox, relay y publicación en el stream de eventos | 2 s |
| Panel API: consumir, releer la BD y escribir el read model (ADR-0004) | 5 s |
| Margen para picos ×10 e interrupciones cortas | el resto, unos 230 s |

### 5.3 Por qué no las otras alternativas

- **P1. Lectura cada 5 s.** Suma hasta 5 s por evento sin un ahorro relevante: una consulta por segundo sobre un índice parcial es una carga mínima para la BD. Además, con un intervalo fijo y lotes de 500 filas el relay no publica más de 100 eventos por segundo, menos que un pico ×10 (QA-6), y el atraso crece.
- **P3. Aviso de PostgreSQL.** En PostgreSQL, una transacción que emite un aviso toma un bloqueo global al confirmarse, lo que serializa las confirmaciones de todas las transacciones que avisan. Aplicado al outbox, que es la tabla más escrita (ADR-0002, W-3; ADR-0005, BD-1), pone contención en el camino crítico de la ingesta. Los avisos se pierden si el relay está desconectado, así que igual hace falta la lectura de respaldo.
- **P4. Captura de cambios.** Es la evolución que el ADR-0002 (B4) dejó prevista, pero suma un conector y la configuración de replicación lógica de PostgreSQL, y no mejora QA-1 frente a una demora de 1 s (R-6). Como el relay publica por un puerto, el cambio no toca a los workers.

---

## 6. Consecuencias

### Positivas

- Una sola tecnología de infraestructura para el read model y el broker, que el grupo ya usa, con un contenedor más en docker-compose (R-6, CRN-2, CRN-N3).
- El contador de entregas y la reclamación de pendientes son nativos, y sostienen el agotamiento y la toma de particiones del ADR-0008 sin estado propio.
- Lo que el receptor confirma al proveedor está persistido, y una caída del broker no lo pierde (QA-5).
- Cada stream retiene lo que su uso necesita y nada más, lo que acota la exposición de datos personales (R-4) y la memoria (QA-6).
- El relay suma como máximo 1 s en operación normal y el presupuesto por etapa permite ubicar dónde se consumen los 5 minutos (QA-1).
- Se cierra la revisión de BR-5 del ADR-0003: el replay se usa, así que el log se mantiene.

### Negativas y riesgos aceptados

| ID | Riesgo | Efecto | Mitigación |
| --- | --- | --- | --- |
| BK-1 | La retención vive en memoria y Redis recorta los mensajes vencidos aunque estén pendientes de confirmar. | El stream de correo a escala de 15 M de cuentas puede no entrar en una instancia (QA-6, QA-7). Si una interrupción supera la retención, se pierden mensajes retenidos de ese proveedor (QA-5). | Retención por stream y mayor que la ventana de reconciliación. Alerta de memoria al 70% y alerta antes del recorte. Caída a reconciliación por estado actual (ADR-0008). Evolución a RabbitMQ Streams detrás del puerto si la memoria proyectada supera la capacidad de una instancia. |
| BK-2 | Persistir cada mensaje antes de confirmar reduce el rendimiento, y la réplica de Redis es asíncrona salvo por la espera explícita. | Más demora en el receptor (QA-1). Un failover en producción todavía puede perder una ventana corta de mensajes (QA-5). | Medir el rendimiento con fsync en la prueba de carga. Espera de la réplica antes de responder. La reconciliación del ADR-0008 cubre lo que se pierda. |
| BK-3 | Lo que Redis no da hay que construirlo y operarlo: arriendos para un consumidor por partición, y dos instancias con configuraciones opuestas (el read model expulsa, el broker no). | Un error en los arriendos puede dejar dos consumidores en una partición y romper el orden por clave (ADR-0002). Confundir las instancias provoca pérdidas o caídas (CRN-N3). | La idempotencia por versión del ADR-0007 protege el resultado aunque el orden falle. Pruebas de caída de workers. Configuraciones separadas y versionadas, y un chequeo al arrancar que verifica la política de memoria del broker. |
