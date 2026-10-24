# Iteración 2 de ADD: ingesta y eventos de Trotamundos

- **Estado:** Borrador para revisión
- **Fecha:** 24/10/2026
- **Equipo:** grupo TPE

---

## 1. Resumen ejecutivo

La Iteración 1 fijó la estructura global (C-lite: Panel API, ingesta y analítica, con broker, outbox y read model). Esta iteración refina la **ingesta y la base operacional**: define cómo se representa una reserva, cómo se traduce y valida cada mensaje de un proveedor, qué lleva cada mensaje, cómo se evita que un duplicado tenga efecto, cómo se aísla y recupera la falla de un proveedor, y con qué tecnología se implementan la base, el broker y el relay (ADR-0005 a 0009).

Las decisiones principales son:

- **Base operacional:** PostgreSQL, con un núcleo de reserva tipado y un campo `detalle` en JSONB validado por esquema para lo propio de cada tipo (vuelo, hotel, auto y, a futuro, tren).
- **Modelo canónico y adaptadores:** núcleo común más detalle por tipo con esquema versionado. Cada adaptador implementa la misma interfaz y devuelve un resultado explícito *Válido* o *Inválido*.
- **Mensajes:** un sobre común de seis campos para el stream de trabajo (notificación cruda) y el de eventos (aviso mínimo con referencias). Entrega al menos una vez, con consumidores idempotentes por clave de negocio y por versión.
- **Resiliencia:** tabiques por proveedor, circuit breaker que pausa el consumo, stream de apartados con registro en la BD y reconciliación al cerrarse el circuito.
- **Tecnología:** Redis Streams en una instancia dedicada (sin expulsión y persistiendo antes de confirmar), retención por tiempo y por stream, y un relay que lee cada 1 s.

Con esto, el camino de **CU-02** queda diseñado de punta a punta, y **QA-1, QA-5, QA-8 y QA-12** tienen un camino de cumplimiento verificable. La iteración **no cubre el pipeline de ingesta de correo** (CU-03, QA-7, QA-9, QA-10), que los ADR dejan explícitamente fuera. Los riesgos aceptados en los ADR-0005 a 0009 se consolidan en **dos concerns nuevas** (sección 5), y otros se asignan a concerns que ya existían.

## 2. Objetivo de la iteración y drivers seleccionados

**Objetivo.** Definir la ingesta y los eventos de punta a punta, desde la notificación del proveedor hasta el cambio visible en el panel, de modo que los drivers de calidad asociados (QA-1, QA-5, QA-8, QA-12) tengan un diseño verificable con proveedores simulados y el grupo pueda implementarlo en el plazo (R-6).

El elemento a refinar son tres elementos de la Iteración 1: la **ingesta** (receptor, workers y relay), el **broker** y la **BD**. El refinamiento es por descomposición de sus responsabilidades y por elección de tecnología.

| Driver | Motivo de la selección |
|---|---|
| CU-02 | Es el camino del requisito de 5 minutos y el más afectado por disrupciones. Es el caso de uso que esta iteración cierra a nivel de diseño. |
| CU-03, CU-04 | CU-03: el adaptador de correo cumple el mismo contrato y los mensajes de sondeo usan el mismo sobre, pero el pipeline queda fuera. CU-04: la reserva manual se guarda en la misma estructura y con la misma validación. Ambos se abordan de forma parcial. |
| QA-1, QA-5 | Presupuesto de tiempo por etapa, durabilidad de lo confirmado, reentrega, replay y reconciliación con el proveedor. |
| QA-8, QA-12 | Criterio objetivo de válido e inválido con motivo registrable, y tipos de reserva nuevos sin tocar el núcleo. |
| QA-6 | Condiciona el motor de la BD (un único primario de escritura) y la retención del log en memoria. |
| R-2, R-3 | Adaptadores a contratos ajenos, límites de uso por proveedor, proveedores sin consulta de cambios. |
| R-4 | Qué datos personales retienen el log y los apartados, y por cuánto tiempo. |
| R-6, CRN-2 | Viabilidad con el conocimiento del equipo (Java, PostgreSQL) y cantidad de tecnologías nuevas. |
| CRN-N1, CRN-N4 | Aislar la falla de un proveedor y mantener consistentes BD, log y read model. |
| CRN-N2, CRN-N3 | Se abordan de forma parcial: el motor de la BD (CRN-N2) y la tecnología del broker (CRN-N3). Partición, réplica y despliegue quedan para la Iteración 4. |

No se selecciona QA-7, aunque la Iteración 1 lo preveía para esta iteración, porque depende del pipeline de correo.

## 3. Conceptos de diseño

Se documentan los conceptos que se desprenden de los ADR-0005 a 0009. Las tácticas de detalle (por ejemplo *Retry*, *Validate Input*, *State Resynchronization*) están en cada ADR y no se repiten acá.

| Concepto | Drivers | ADR | Alternativas descartadas |
|---|---|---|---|
| PostgreSQL con núcleo relacional tipado y `detalle` JSONB validado por esquema | QA-1, QA-5, QA-6, QA-12, R-6, CRN-2 | 0005 | MySQL/MariaDB, MongoDB, DynamoDB/Cassandra |
| Modelo canónico: núcleo común obligatorio y detalle por tipo con esquema versionado | QA-8, QA-12, R-2, CU-02, CU-03 | 0006 (A) | Modelo plano único, sin modelo canónico, estándar externo como modelo interno |
| Adaptador como puerto de tres operaciones, con resultado Válido/Inválido y registro por configuración | QA-8, QA-12, CRN-N1, R-2, R-6 | 0006 (B) | Salida libre y normalización en el núcleo, plug-in dinámico, servicio independiente por proveedor |
| Sobre común versionado, trabajo con la notificación cruda y evento mínimo con referencias | CRN-N4, QA-1, QA-5, QA-8, QA-12, R-2, R-4 | 0007 (A) | Sin contrato común, evento con estado completo, registro de esquemas formales, notificación interpretada en el receptor |
| Entrega al menos una vez con idempotencia natural en cada consumidor | CRN-N4, QA-1, QA-5 | 0007 (B) | Tabla de mensajes procesados, como máximo una vez, exactamente una vez |
| Tabiques por proveedor, circuit breaker que pausa y reintentos según el tipo de falla | CRN-N1, QA-1, QA-8, R-3, R-6 | 0008 (A) | Pool compartido, breaker sobre stream común, desplegable por proveedor |
| Stream de apartados con registro en la BD sin el cuerpo del mensaje | QA-8, CRN-N1, R-4 | 0008 (B) | Descartar, BD como depósito, no apartar |
| Reconciliación incremental al cerrarse el circuito, con caída a estado actual | QA-5, R-3 | 0008 (C) | Solo el log, reconciliación periódica completa, esperar al proveedor |
| Redis Streams en instancia dedicada, sin expulsión y persistiendo antes de confirmar | QA-1, QA-5, QA-6, R-6, CRN-2, CRN-N3 | 0009 (A) | Redis compartido con el read model, RabbitMQ Streams, cola durable |
| Retención por tiempo, distinta por stream | QA-5, QA-6, R-4 | 0009 (B) | Por cantidad, larga uniforme |
| Relay con lotes de 500 filas cada 1 s y relectura inmediata si el lote vino lleno | QA-1, QA-6 | 0009 (C) | Cada 5 s, `LISTEN/NOTIFY`, captura de cambios (CDC) |

**Tecnologías.** PostgreSQL (BD operacional) y Redis (broker en una instancia dedicada, y read model en otra). Con esto quedan definidos los dos puntos que la Iteración 1 dejaba abiertos: el motor de la BD y la tecnología del broker. La tecnología del almacén analítico sigue sin definirse.

**Costo explícito.** El diseño depende de código y parámetros propios (reintentos, circuito, arriendos por partición, validación por esquema) y de dos instancias de Redis con configuraciones opuestas. Ese costo se refleja en las concerns de la sección 5.

## 4. Vista modular y elementos instanciados

### 4.1 Vista modular

La estructura de tres desplegables de la Iteración 1 no cambia. Cambia lo que hay dentro de la ingesta y el contenido de los almacenes. El flujo de punta a punta de una notificación de un proveedor de viaje es:

1. **Receptor.** Verifica la autenticidad, arma el mensaje de trabajo (sobre más la notificación cruda), lo encola en el stream de trabajo del proveedor y responde al proveedor solo cuando el broker confirma.
2. **Broker.** Entrega el trabajo a un único worker activo por partición.
3. **Worker.** Elige el adaptador por el campo `origen`. Llama a *Traducir* si la notificación trae la reserva completa o a *Consultar* si no. Si el resultado es *Válido*, compara con lo guardado.
4. **Guardado.** Si hay diferencia, en una sola transacción actualiza la reserva, sube la versión del viaje y escribe el evento en el outbox. Después del `COMMIT` confirma el trabajo.
5. **Relay.** Publica el evento en el stream de eventos (clave `viaje:<id>`) y lo marca como publicado.
6. **Panel API.** Relee el estado actual del viaje, escribe el read model si la versión es mayor o igual a la guardada y confirma el evento.
7. **Si algo no se puede procesar** (inválido, contrato roto o reintentos agotados) el mensaje va al stream de apartados y su registro, sin el cuerpo, a la BD.
8. **Si el proveedor falla**, el circuito se abre y el consumo de sus particiones se pausa. Los demás proveedores siguen. Al cerrarse el circuito se reconcilia lo ocurrido durante la interrupción.

El campo `correlacion` nace en el receptor y se copia hasta el evento. Junto con `horaDelHecho` permite medir cuánto pasó entre el hecho en el proveedor y el cambio visible en el panel, y en qué etapa se consumió.

### 4.2 Elementos y responsabilidades

| Elemento | Responsabilidades (refinadas en esta iteración) | Comunicación |
|---|---|---|
| **Receptor de notificaciones** | Verifica autenticidad, arma el mensaje de trabajo con el sobre y la notificación cruda, encola y responde cuando el broker confirma. Si no puede encolar, responde con error y el proveedor reintenta. No interpreta la notificación. | Recibe de los proveedores. Publica en el stream de trabajo del proveedor. |
| **Planificador de sondeo de correo** | Sin cambios: encola el sondeo de cada cuenta con el mismo sobre (`claveDeOrden` `correo:<id de la cuenta>`). Su diseño depende del pipeline de correo. | Publica en el stream de trabajo de correo. |
| **Workers de ingesta** | Un tabique por proveedor (pool de conexiones, límite de concurrencia y limitador de tasa) y un circuit breaker propio. Eligen el adaptador por `origen`, comparan con lo guardado, guardan el cambio y el evento en una transacción y confirman después del `COMMIT`. Apartan inválidos y agotados. Ejecutan la reconciliación al cerrarse el circuito. | Toman trabajo del broker. Consultan a los proveedores. Escriben en la BD. |
| **Adaptadores** | Un adaptador por proveedor y medio, cargado por configuración. Operaciones: *Describir*, *Traducir*, *Consultar* y *Consultar cambios* (esta última agregada por el ADR-0008). Devuelven datos: no escriben en la BD, no publican y no reintentan. Valen para proveedores de viaje y de correo. | Los invocan los workers. |
| **Relay del outbox** | Lee lotes de hasta 500 filas con `FOR UPDATE SKIP LOCKED`, espera 1 s si el lote no vino lleno y vuelve a leer en el acto si vino lleno. Publica en el stream de eventos. | Lee de la BD. Publica en el broker. |
| **Broker (Redis Streams)** | Instancia dedicada con `noeviction` y `appendfsync always`, que en producción espera además una réplica. Un stream por partición de trabajo, uno de eventos y dos de apartados. Arriendo con vencimiento para asegurar un consumidor por partición y reclamo de pendientes si un worker cae. | Producen: receptor, planificador, relay y workers (apartados). Consumen: workers y Panel API. |
| **BD (PostgreSQL)** | Núcleo de la reserva en columnas tipadas y `detalle` en JSONB validado. Versión por viaje y por reserva. Restricción única por proveedor e identificador externo. Fechas en `timestamptz` más la zona de origen del viaje. Outbox en la misma base. Registro de apartados e interrupciones (ADR-0008). | Panel API, workers, relay y copia periódica hacia el almacén analítico. |
| **Panel API** | Al recibir un evento relee el estado actual del viaje y escribe el read model solo si la versión es mayor o igual a la guardada. Usa la misma validación del modelo canónico para las reservas manuales (CU-04). | Consume el stream de eventos. Lee y escribe en la BD y en el read model. |
| **Read model (Redis)** | Sin cambios de responsabilidad. Corre en una instancia separada del broker, porque admite expulsión y el broker no. | Lo lee y lo actualiza el Panel API. |
| **Almacén analítico y analítica** | Sin cambios. Siguen aislados y se alimentan por copia periódica de la BD. | — |

### 4.3 Streams y retención

| Stream | Contenido | Retención |
|---|---|---|
| `trabajo:<proveedor>:<n>` | Notificación cruda con el sobre. Un consumidor activo por partición. | 48 h\* (la ventana de 24 h de reconciliación más un margen) |
| Trabajo de correo | Trabajo de sondeo con el sobre, sin contenido de correo ni tokens. | 24 h\* (provisional, lo revisa el pipeline de correo) |
| `eventos` | `tipoEvento`, `idViaje`, `idReserva` y `versionViaje`, sin datos de la reserva. | 24 h\* (después se reconstruye el read model desde la BD) |
| `apartados:trabajo` y `apartados:eventos` | Mensaje original, motivo e intentos. | 7 días\* |

### 4.4 Parámetros del prototipo

Los valores con \* son propuestas que se ajustan con proveedores simulados y prueba de carga.

| Parámetro | Valor |
|---|---|
| Falla del proveedor (tiempo de espera, red, 5xx) | Hasta 3 reintentos con espera creciente\*. Un 429 respeta la espera del proveedor. |
| Error propio causado por el mensaje | Hasta 5 entregas\*, después se aparta como «agotado». |
| Inválido | Nunca se reintenta. Se aparta con motivo codificado. |
| Falla de la BD o del broker | Se pausa el consumo, sin apartar nada. |
| Circuit breaker | Se abre si la mitad de las últimas 20 llamadas falla\*. Prueba con una llamada real cada vez más espaciada, hasta 60 s\*. |
| Reconciliación | Incremental por *Consultar cambios* (intervalo desde 5 min antes del inicio). Si el proveedor no la ofrece, o la interrupción supera 24 h\*, se consulta el estado actual de las reservas activas, primero las de inicio más próximo. |
| Presupuesto de QA-1 (5 min) | Receptor 1 s, worker con reintentos 60 s, outbox y relay 2 s, Panel API y read model 5 s. Margen para picos: unos 230 s. |

### 4.5 Qué pasa cuando algo falla

| Qué falla | Qué pasa | Resultado |
|---|---|---|
| El worker se cae antes o después del `COMMIT` | Se reentrega el trabajo. Si ya se guardó, la comparación con lo guardado no encuentra diferencia. | Sin cambio ni evento duplicado. |
| El proveedor reintenta una notificación | Llega con otro `idMensaje`. La clave única y la comparación la detectan. | Sin efecto. |
| El relay publica y se cae antes de marcar | El evento sale dos veces. El Panel API relee y la escritura condicional no tiene efecto. | El read model no retrocede. |
| El mensaje es inválido o tiene el sobre roto | Se aparta con motivo y se registra en la BD. | El resto de la partición sigue. |
| La API de un proveedor cae | El circuito se abre y sus mensajes esperan en el log. Al cerrarse se reconcilia. | Los demás proveedores no se afectan. |
| La BD entra en failover | Los workers pausan el consumo sin apartar mensajes. | Se demora la frescura. El panel sigue sirviendo el read model. |
| El broker se llena | La escritura falla y el receptor responde con error. | El proveedor reintenta, sin pérdida silenciosa. |

## 5. Consecuencias negativas y nuevos drivers

Los ADR-0005 a 0009 registran 22 riesgos aceptados: BD-1 a BD-4 (ADR-0005), MC-1 a MC-5 (ADR-0006), MS-1 a MS-6 (ADR-0007), RS-1 a RS-3 (ADR-0008) y BK-1 a BK-4 (ADR-0009). Para no cerrar la iteración con una lista inmanejable, 10 se consolidan en **dos concerns nuevas** y los 12 restantes se asignan a concerns existentes o a drivers, como indica la sección 5.2. Los IDs de las concerns nuevas son provisionales hasta incorporarlas al catálogo de drivers.

### 5.1 Concerns nuevas

| ID | Concern | Riesgos de origen | Mitigación ya prevista en los ADR |
|---|---|---|---|
| CRN-N5 | Mantener alineados contratos, esquemas, adaptadores y validación | BD-3, MC-1, MC-2, MC-3, MC-4, MC-5, MS-4 | Una sola fuente de esquemas compartida por adaptadores y Panel API. Pruebas de contrato por adaptador con mensajes válidos e inválidos. Bloque de extensión para datos sin equivalente. Lector tolerante. Soporte de la versión de contrato actual y la anterior. Campos consultados como columnas. Revisión periódica de los motivos de inválidos por proveedor. |
| CRN-N6 | Sostener la durabilidad, la capacidad y la exposición de datos personales de un log en memoria | BK-1, BK-2, MS-2 | Retención por stream y alertas de memoria. Reconciliación por estado actual. Espera de réplica y medición en la prueba de carga. Retención acotada, sin contenido de correo ni tokens en el log. Acceso restringido y revisión por región. Evolución a RabbitMQ Streams detrás del puerto. |

### 5.2 Riesgos asignados a concerns existentes o a drivers

| Riesgo | Dónde se resuelve |
|---|---|
| RS-1 (resiliencia con código y parámetros propios, falla mal clasificada) y RS-2 (un mensaje con errores repetidos bloquea su partición) | CRN-N1. Biblioteca estándar, pruebas con proveedores simulados que fallan, alerta por salto de apartados y más particiones si la demora se repite. |
| BD-1 (único primario de escritura), BD-4 (base compartida y presión sobre el outbox) y MS-1 (el evento mínimo obliga a leer la base) | CRN-N2, Iteración 4: dueño de tablas por módulo, partición del outbox, réplica de lectura, relay con lotes y prueba de carga. |
| BD-2 (failover de la base, contra 5 min mensuales) | CRN-N3 y QA-4, ADR de despliegue: standby sincrónico con failover automático. |
| MS-3 (la idempotencia depende de la disciplina de cada consumidor), MS-5 (dos notificaciones del mismo viaje compiten por la versión), MS-6 (notificación vieja que el proveedor envía después de una nueva) y BK-3 (arriendos por partición en código propio, dos instancias de Redis con configuración opuesta) | CRN-N4. Pruebas de reentrega y de caída de workers, reintento ante conflicto de versión, *Consultar* para proveedores que no garantizan orden y chequeo de la configuración al arrancar. |
| RS-3 (la reconciliación por estado actual puede superar 5 min) | QA-5. Primero los viajes próximos, medir su duración. Se acepta para interrupciones de más de 24 h. |
| BK-4 (sin experiencia con Redis, y T1 pierde ventaja si el read model pasa a PostgreSQL) | CRN-2 y R-6. Prueba temprana del broker con caídas y reentregas. El broker queda detrás del puerto, y cambiar a RabbitMQ Streams no afecta al resto. |

### 5.3 Concerns existentes: qué cambió

| Concern | Cambio |
|---|---|
| CRN-N1 | La mitigación prevista en la Iteración 1 queda diseñada (ADR-0008). Quedan los residuales RS-1 y RS-2, que se verifican con proveedores simulados. |
| CRN-N4 | La Iteración 1 preveía idempotencia por identificador de notificación o versión del proveedor. El ADR-0007 la resuelve en los workers con la restricción única por proveedor e identificador externo más la comparación con el estado guardado, porque no todos los proveedores informan esos datos y usarlos obligaría a agregar al núcleo un campo con significado variable. Se mantiene la versión del viaje en el Panel API. Se acepta el residual MS-6. |
| CRN-N2 | Se eligió el motor, pero siguen abiertos la partición del outbox, la réplica de lectura y el dueño de las tablas. |
| CRN-N3 | Se eligió la tecnología del broker. Siguen abiertos el despliegue, la alta disponibilidad y el monitoreo. |

## 6. Estado de los drivers al cierre

Estados: no abordado, parcialmente abordado y abordado. «Abordado a nivel de diseño» significa que el diseño está completo pero falta medirlo con proveedores simulados y prueba de carga. Se listan los drivers cuyo estado cambió. Siguen sin cambios respecto de la Iteración 1: CU-01, CU-06, QA-2, QA-3 y QA-11.

| Driver | Estado | Fundamento | Pendiente |
|---|---|---|---|
| CU-02 | Abordado a nivel de diseño | Receptor, adaptadores, modelo canónico, guardado transaccional, outbox, relay, resiliencia y reconciliación (ADR-0005 a 0009). | Mapeos concretos por proveedor, verificación de autenticidad en el receptor y prototipo con proveedores simulados. |
| CU-03 | Parcial | El adaptador de correo cumple el mismo contrato y el sondeo usa el mismo sobre. | Pipeline de ingesta de correo y planificador. |
| CU-04 | Parcial | La reserva manual se guarda en la misma estructura y con la misma validación (ADR-0006, MC-4). | API de gestión manual. |
| QA-1 | Parcial | Presupuesto por etapa, persistencia antes de confirmar, relay de 1 s y contrato con `horaDelHecho` y `correlacion` para medir. | Medición con prueba de carga. |
| QA-5 | Parcial | Outbox sin pérdida, retención de 48 h, persistencia en disco, circuito acotado a 60 s y reconciliación. | Medir la reconciliación (RS-3). Riesgos BK-1 y BK-2. |
| QA-6 | Parcial | Particiones por proveedor, relay con lotes, motor con particionado nativo. | Dimensionamiento y prueba de carga (BD-1, BK-1). |
| QA-4 | Parcial | Los workers pausan sin apartar si la BD o el broker fallan. El panel sirve del read model. | Despliegue y alta disponibilidad (BD-2). |
| QA-8 | Abordado a nivel de diseño | Criterio de válido e inválido con motivo codificado, apartados con registro y causas de disponibilidad excluidas. | Medir el 99% con proveedores simulados. |
| QA-12 | Abordado a nivel de diseño | Un tipo nuevo agrega un esquema de detalle y un adaptador, sin migración ni cambios en el núcleo. | Probar con trenes contra los 2 días-persona. |
| QA-7 | Parcial | Sin cambios sustantivos: workers escalables y buffer de entrada. | Pipeline de correo. |
| R-2 | Abordado | Modelo canónico y adaptadores por puerto, con estándares de la industria solo como fuente de mapeos. | Mapeos concretos. |
| R-3 | Parcial | Aislamiento, limitador de tasa por proveedor, 429 respetado y caída a estado actual si no hay consulta de cambios. | Límites reales de cada proveedor. |
| R-4 | Parcial | El log no retiene reservas completas ni contenido de correo, los apartados se registran sin cuerpo y cada stream retiene lo necesario. | Multi-región y protección por región (MS-2). |
| R-6 | Parcial | Sin desplegables nuevos. Redis y PostgreSQL ejecutables con docker-compose. Hay bibliotecas para la resiliencia. | Reverificar el esfuerzo del código propio (RS-1, BK-3). |
| CRN-2 | Parcial | PostgreSQL entra en las tecnologías del equipo. Redis sigue sin experiencia confirmada. | Prueba temprana del broker (BK-4). |
| CRN-N1 | Parcial | Tabiques, circuito y apartados por proveedor. | RS-1 y RS-2. |
| CRN-N2 | Parcial | Motor elegido. | Partición del outbox, réplica y dueño de tablas. |
| CRN-N3 | Parcial | Broker con persistencia, relay con intervalo fijado. | Despliegue, HA y monitoreo. |
| CRN-N4 | Parcial | Idempotencia natural, escritura condicional y reconstrucción del read model. | MS-3, MS-5 y MS-6 (sección 5.2). |
| CRN-N5, CRN-N6 | No abordado | Concerns nuevas de esta iteración. | Se incorporan al catálogo de drivers. |

### Drivers no abordados

- **Funcionalidad secundaria:** CU-05, CU-07, CU-08, CU-09.
- **Atributos de calidad:** QA-9 (extracción de reservas del correo) y QA-10 (revocación del acceso al correo).
- **Restricciones:** R-1 y R-5.
- **Concerns:** CRN-3 (aislamiento de la analítica y de los casos de uso secundarios), CRN-N5 y CRN-N6.

Si el grupo prefiere mantener el orden de la Iteración 1, el pipeline de correo puede sumarse a esta iteración como un ADR-0010 antes de cerrarla. <PENDIENTE: el grupo decide el orden.>
