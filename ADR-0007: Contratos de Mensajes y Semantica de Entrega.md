# ADR-0007: Contratos de Mensajes y Semantica de Entrega

- **Estado:** Propuesto
- **Fecha:** 23/10/2026
- **Decisores:** Equipo de diseño (grupo TPE)
- **Drivers de entrada:** Análisis inicial y drivers e Iteración 1. CU-01, CU-02, CU-03. Otros drivers: QA-1, QA-5, QA-8, QA-12, R-2, R-4, R-6 y CRN-N4.
- **ADR relacionados:** ADR-0002 (workers y outbox), ADR-0003 (broker con log persistente), ADR-0004 (read model), ADR-0005 (motor de la base), ADR-0006 (modelo canónico y adaptador), ADR-0008 (resiliencia de la ingesta y cola de mensajes inválidos), ADR-0009 (tecnología del broker, retención y relay).

---

## 1. Contexto y problema

Los ADR-0002 y 0003 fijaron la forma general de la comunicación: un stream de trabajo (receptor a workers) y un stream de eventos (relay a Panel API), con entrega al menos una vez y consumidores idempotentes. Dejaron sin definir qué lleva cada mensaje. El ADR-0003 lo remite explícitamente al contrato (por ejemplo, la clave de orden del stream de trabajo), y la Iteración 1 lo registra como decisión pendiente. Este documento define esos contratos y cómo cada consumidor evita efectos duplicados. Debe resolverse antes de la resiliencia de la ingesta (ADR-0008), que usa estos mensajes para reintentos y para derivar los inválidos, y antes de la tecnología del broker (ADR-0009), que fija la retención de lo que aquí se define.

Este documento responde dos preguntas:

1. **¿Qué campos y qué estructura tienen los mensajes del stream de trabajo y del stream de eventos?** (Decisión A)
2. **¿Cómo logra cada consumidor que la entrega al menos una vez no produzca efectos duplicados?** (Decisión B)

**Tensiones:**

- **Reentrega frente a efecto único.** La entrega al menos una vez no pierde mensajes (QA-1, QA-5), pero los duplica: por una caída después de procesar, por una publicación repetida del relay o por un reintento del proveedor. Cada duplicado debe ser inofensivo (CRN-N4).
- **Orden parcial.** El log ordena solo por clave (ADR-0003) y dos transacciones concurrentes pueden confirmarse en distinto orden que su secuencia de creación (ADR-0002, W-5). Ningún consumidor puede suponer orden global.
- **Mensaje mínimo frente a mensaje suficiente.** Cuanto más lleva un evento, menos lee el consumidor de la base (ADR-0004, RM-1), pero más datos se retienen en el log (R-4) y más se acopla el consumidor al modelo canónico.
- **Evolución con mensajes retenidos.** El log conserva mensajes durante una ventana. Un cambio de contrato convive con mensajes viejos que se pueden reprocesar (replay del ADR-0003) y debe permitir sumar proveedores y tipos de reserva sin cambiar el contrato (QA-12, R-2).
- **Presupuesto de tiempo.** Los 5 minutos de QA-1 se reparten por etapa (ADR-0003, BR-1). Sin datos de tiempo y de trazabilidad en los mensajes no se puede medir en qué etapa se consume.

**Fuera de alcance:**

- Tecnología del broker, valor de retención e intervalo del relay (ADR-0009).
- Reintentos y backoff concretos, circuit breaker, stream y registro de mensajes inválidos, y reconciliación con el proveedor (ADR-0008).
- Modelo canónico de la reserva y contrato del adaptador (ADR-0006).
- Mecanismo del outbox y del relay (ADR-0002).
- Cambios manuales del viajero, que el Panel API guarda sin pasar por el broker (ADR-0004).
- Consumidores futuros del log, como la analitíca, que hoy no consume del log (iteraciones futuras).
- Esquemas campo por campo de cada mensaje.

---

## 2. Criterios de decisión

| Criterio | Qué mide en esta decisión | Se usa en |
| --- | --- | --- |
| CRN-N4 (consistencia) | Si duplicados, reentregas y mensajes fuera de orden no tienen efecto, y si los datos se pueden reconstruir. | A, B |
| QA-1 (actualización en 5 minutos) | Si cada mensaje permite medir la demora por etapa y si el contrato no agrega etapas ni escrituras. | A, B |
| QA-5 (recuperación) | Si la reentrega y el replay no pierden mensajes ni duplican efectos. | A, B |
| QA-8 (interoperabilidad) | Si un mensaje mal formado se identifica y se aparta sin frenar el consumo del resto. | A, B |
| QA-12 y R-2 (evolución e integración) | Si un proveedor, un medio de transporte o un tipo de reserva nuevo se suma sin cambiar el contrato. | A |
| R-4 (protección de datos) | Si el log retiene solo los datos personales imprescindibles. | A |
| R-6 (implementación en plazo) | Esfuerzo para el grupo y piezas o escrituras nuevas respecto de la iteración 1. | A, B |

---

## 3. Decisión A: estructura de los mensajes

### 3.1 Alternativas consideradas

| ID | Alternativa | Resumen |
| --- | --- | --- |
| A1 | Sin contrato común | Cada productor define los campos de sus mensajes libremente y cada consumidor los interpreta como puede. |
| A2 | **Sobre común versionado, trabajo con la notificación cruda y evento mínimo con referencias** | Ambos streams comparten un sobre de seis campos. El mensaje de trabajo lleva la notificación tal como llegó. El evento es un aviso con referencias. |
| A3 | Evento con el estado completo | El evento lleva la reserva canónica completa, y el consumidor no necesita leer la base. |
| A4 | Esquemas formales con registro de esquemas | Los mensajes se definen en un formato binario con esquema (Avro o Protobuf) y un registro centralizado que valida y versiona. |
| A5 | Notificación interpretada en el receptor | El receptor traduce la notificación al modelo canónico y encola la reserva ya interpretada. |

### 3.2 Decisión

**Se elige A2: los dos streams comparten un sobre de seis campos con versión de contrato. El mensaje de trabajo lleva la notificación tal como llegó y el evento es un aviso mínimo con referencias.**

**Descripción de la decisión.** Todo mensaje, sea de trabajo o de evento, viaja con el mismo sobre que permite identificarlo, ordenarlo por clave, medir su demora y seguirlo de punta a punta. El cuerpo depende del stream. Aplica las tácticas de la cátedra *Timestamp* (detección de fallas, para ubicar un mensaje en el tiempo), *Adhere to Standards* (formatos estándar) y *Use an Intermediary* (el broker desacopla productores y consumidores), dentro del patrón *Publish/Subscribe* ya adoptado en el ADR-0003.

| Campo del sobre | Qué contiene |
| --- | --- |
| `idMensaje` | Identificador único (UUID) asignado por quien produce el mensaje: el receptor en el trabajo, y la fila del outbox en el evento. Identifica al mensaje y no al hecho de negocio. Una reentrega conserva el mismo id. |
| `versionContrato` | Número entero con la versión del esquema del mensaje. No es la versión del viaje. |
| `claveDeOrden` | Clave que fija el orden entre mensajes. En el trabajo: `<proveedor>:<identificador externo de la reserva>` para notificaciones de proveedores de viaje, y `correo:<id de la cuenta>` para las de correo. En el evento: `viaje:<id del viaje>` (ADR-0003). |
| `horaDelHecho` | Instante en UTC en que ocurrió el hecho en el origen según el proveedor. Si el proveedor no lo informa, es la hora de recepción en el receptor. En el evento es la que devuelve el adaptador junto a la reserva (ADR-0006). |
| `correlacion` | Identificador (UUID) que nace en el receptor y se copia a todo mensaje derivado, hasta el evento. Sirve para seguir un hecho de punta a punta. |
| `origen` | `<proveedor>/<medio de transporte>` de donde viene el hecho, por ejemplo `aerolinea-x/vuelo` o `gmail/correo`. Coincide con lo que declara el adaptador (ADR-0006), así que el worker elige el adaptador por este campo. |

- **Mensaje de trabajo con la notificación cruda.** El cuerpo contiene la notificación tal como la recibió el receptor, sin interpretarla. No contiene el contenido de correos, tokens de acceso ni la reserva traducida. Así el receptor sigue solo encolando y respondiendo (ADR-0003), y un lote mal interpretado se puede reprocesar desde el log una vez corregido el adaptador.
- **Evento mínimo con referencias.** El cuerpo contiene `tipoEvento` (por ejemplo `ReservaCreada`, `ReservaModificada` o `ReservaCancelada`), `idViaje`, `idReserva` y `versionViaje`, que es la versión del viaje tras el cambio. Dice que el viaje X llegó a la versión N, sin datos de la reserva (ADR-0002, ADR-0004).
- **Evolución compatible.** Dentro de una versión de contrato solo se agregan campos opcionales. Los consumidores ignoran los campos desconocidos y tratan un `tipoEvento` desconocido como «el viaje cambió», porque el Panel API siempre relee el estado actual. Un cambio incompatible crea una versión nueva, y los consumidores soportan la actual y la anterior mientras haya mensajes retenidos con esta última.
- **Formato estándar y esquemas versionados.** Los mensajes son JSON en UTF-8, con fechas en UTC según ISO 8601. Un esquema JSON por mensaje y versión vive en el repositorio compartido por productores y consumidores.
- **Validación del sobre.** Un mensaje al que le falta un campo del sobre, o con una `versionContrato` desconocida, se aparta como inválido con el motivo «contrato», sin frenar el consumo del resto. El destino y el registro los define el ADR-0008. Es distinto del inválido del ADR-0006, que juzga el contenido que envía el proveedor.

### 3.4 Por qué no las otras alternativas

- **A1. Sin contrato común.** Sin identificador, versión y correlación no se puede trazar un hecho ni medir las etapas de QA-1, y cada consumidor tendría que adivinar el formato, lo que no permite verificar la consistencia (CRN-N4). Tampoco hay forma de detectar un mensaje mal formado (QA-8).
- **A3. Evento con el estado completo.** Haría innecesaria la lectura de la base (RM-1), pero contradice el diseño del ADR-0004, donde el Panel API relee el estado actual para que un evento duplicado o viejo no pise a uno nuevo. Además retiene la reserva completa, con datos personales, durante toda la ventana del log (R-4), y acopla a los consumidores al modelo canónico. Queda como evolución compatible: agregar un campo opcional `estado` al evento no exige cambiar el contrato ni a los consumidores existentes.
- **A4. Esquemas formales con registro de esquemas.** Da validación y compatibilidad automatizadas, pero suma una pieza para operar y una tecnología nueva para el grupo, lo que va contra R-6. Los esquemas JSON versionados en el repositorio cubren la validación con el volumen del prototipo. Como el sobre ya lleva `versionContrato`, pasar a esquemas formales no cambia el contrato.
- **A5. Notificación interpretada en el receptor.** El receptor tendría que cargar los adaptadores y procesar antes de responder, que es justo lo que el ADR-0003 evita para que la respuesta al proveedor sea rápida (QA-1). Además se perdería la notificación original, y un adaptador mal corregido ya no se podría reprocesar desde el log (QA-5).

---

## 4. Decisión B: entrega al menos una vez con idempotencia natural

### 4.1 Alternativas consideradas

| ID | Alternativa | Resumen |
| --- | --- | --- |
| B1 | **Al menos una vez con idempotencia natural en cada consumidor** | Cada consumidor logra que un duplicado no tenga efecto con las claves y versiones que ya existen en el modelo, y confirma el mensaje solo después de completar su efecto. |
| B2 | Al menos una vez con tabla de mensajes procesados | Cada consumidor guarda los `idMensaje` ya procesados y descarta los repetidos. |
| B3 | Como máximo una vez | El consumidor confirma el mensaje antes de procesarlo. |
| B4 | Exactamente una vez de extremo a extremo | Transacciones entre broker, base y consumidor para que cada mensaje tenga un efecto único garantizado. |

### 4.2 Decisión

**Se elige B1: la entrega es al menos una vez (ADR-0003), cada consumidor es idempotente por las claves y versiones del modelo, y todo mensaje se confirma solo después de completar su efecto.**

**Descripción de la decisión.** La garantía de entrega al menos una vez ya está decidida en el ADR-0003. Este documento concreta cómo cada etapa la sostiene sin que un duplicado cambie el resultado. Aplica las tácticas de disponibilidad *Transactions* (el cambio y el evento se guardan juntos, ADR-0002) y *Retry* (la reentrega es el mecanismo de recuperación).

- **Confirmación tardía.** El worker confirma el trabajo después del `COMMIT`. El Panel API confirma el evento después de escribir el read model. El relay marca un evento como publicado después de que el broker lo confirma. El receptor responde correctamente al proveedor solo después de que el broker confirma el encolado. Si algo falla antes, el mensaje se reentrega.
- **Workers idempotentes por reserva.** La base tiene una restricción única por proveedor e identificador externo (ADR-0005). El worker compara el resultado de la traducción con el estado guardado: si no difiere, no sube la versión y no escribe evento. Reprocesar un trabajo, o recibir el reintento del mismo hecho con otro `idMensaje`, no duplica la reserva ni emite eventos de más.
- **Panel API idempotente por versión.** Relee el estado actual del viaje y escribe en el read model solo si la versión es mayor o igual a la guardada (ADR-0004). El `idMensaje` se usa para trazabilidad y no para deduplicar.
- **Orden solo por clave.** Ningún consumidor supone orden entre claves ni orden global. Dentro de una misma clave, la versión del viaje es la autoridad, no la posición en el log.
- **Reintentos y agotamiento.** Un mensaje que falla se reentrega con espera creciente. Al agotarse los reintentos se aparta con su motivo, sin frenar el consumo del resto. Cantidad, esperas y destino los define el ADR-0008.

### 4.3 Cómo funciona

Una aerolínea notifica un cambio de horario de un vuelo del viaje 123 (versión 3).

1. El receptor verifica la autenticidad, arma el mensaje de trabajo (`claveDeOrden` `aerolinea-x:ABC123`, `origen` `aerolinea-x/vuelo`, `correlacion` nueva, la notificación cruda en el cuerpo), lo encola y responde al proveedor cuando el broker confirma.
2. Un worker toma el trabajo y elige el adaptador por `origen`. Consulta los detalles con el adaptador, que valida la respuesta y devuelve la reserva canónica (ADR-0006), y compara con lo guardado. Hay diferencia: en una sola transacción actualiza la reserva, sube el viaje a la versión 4 y escribe el evento `ReservaModificada` en el outbox, con la misma `correlacion`. Después del `COMMIT` confirma el trabajo.
3. El relay publica el evento (`claveDeOrden` `viaje:123`, `versionViaje` 4) y lo marca como publicado.
4. El Panel API lee el viaje 123 de la base y escribe el documento si la versión es mayor o igual a la guardada. Después confirma el evento.

Con `horaDelHecho` y `correlacion` se mide cuánto pasó entre el hecho en el proveedor y el cambio visible en el panel, y en qué etapa se consumió.

| Qué falla | Qué pasa | Resultado |
| --- | --- | --- |
| El worker se cae antes del `COMMIT`. | No hay cambio ni evento y el trabajo se reentrega. | El trabajo se procesa de nuevo con el mismo resultado final. |
| El worker se cae después del `COMMIT` y antes de confirmar. | El trabajo se reentrega. El worker compara y no hay diferencia. | No se sube la versión ni se emite un segundo evento. |
| El proveedor reintenta la notificación (nuevo `idMensaje`). | El worker compara con el estado guardado. | Sin efecto. Una tabla de `idMensaje` no lo habría detectado. |
| El relay publica y se cae antes de marcar el evento. | El evento sale dos veces. | El Panel API relee el estado actual y la escritura condicional no tiene efecto. |
| Llega el evento de la versión 3 después del de la versión 4. | El Panel API relee el viaje en su versión 4. | El read model no retrocede. |
| El mensaje no trae un campo del sobre o su `versionContrato` es desconocida. | Se aparta con el motivo «contrato». | El consumo del resto sigue (QA-8). |
| El receptor no puede encolar. | Responde con error al proveedor. | El proveedor reintenta y el worker es idempotente. |

### 4.4 Por qué no las otras alternativas

- **B2. Tabla de mensajes procesados.** Agrega una escritura por mensaje en la base compartida, que ya es el punto de presión (ADR-0005, BD-1; ADR-0002, W-2), y un estado a purgar, contra R-6 y QA-1. No cubre el caso principal: un reintento del proveedor llega con otro `idMensaje`, así que solo las claves de negocio y la versión lo detectan. Queda como evolución para un consumidor futuro con efectos no idempotentes (por ejemplo, enviar avisos al viajero): ese consumidor guarda los `idMensaje` que ya viajan en el sobre, sin cambiar el contrato.
- **B3. Como máximo una vez.** Si el consumidor se cae después de confirmar y antes de procesar, el mensaje se pierde sin reentrega, lo que incumple QA-1 y QA-5 y deja sin sentido la garantía del outbox de «nunca cero» (ADR-0002).
- **B4. Exactamente una vez de extremo a extremo.** Requiere transacciones que abarquen broker, base y read model. El ADR-0002 descartó la transacción distribuida porque los brokers candidatos no participan en ellas, y el ADR-0003 descartó la plataforma de streaming completa por viabilidad (R-6). Además, la consulta al proveedor es un efecto externo que ninguna transacción cubre, así que la idempotencia seguiría siendo necesaria.

---

## 5. Consecuencias

### Positivas

- Cada hecho se puede seguir de punta a punta y medir contra los 5 minutos de QA-1, con `horaDelHecho` y `correlacion`.
- Duplicados, reentregas y eventos fuera de orden no tienen efecto, y el Panel API no retrocede (CRN-N4, QA-5).
- Un proveedor, un medio de transporte o un tipo de reserva nuevo no cambia el contrato: el trabajo lleva la notificación cruda y el evento solo referencias (QA-12, R-2).
- El log retiene eventos sin datos de la reserva y trabajos sin contenido de correo ni tokens, lo que reduce la exposición de datos personales (R-4).
- No suma piezas ni escrituras: usa las claves y versiones que ya existen en los ADR-0002, 0004 y 0005 (R-6).

### Negativas y riesgos aceptados (máximo 5)

| ID | Riesgo | Efecto | Mitigación |
| --- | --- | --- | --- |
| MS-1 | El evento mínimo obliga a cada consumidor a leer la base. | Los consumidores futuros quedan acoplados a la base, y la presión de lectura crece con cada uno (QA-6). | Evolución compatible con un campo opcional `estado` en el evento (alternativa A3). Agrupar eventos por viaje (ADR-0004). |
| MS-2 | La notificación cruda del trabajo puede contener datos personales y queda retenida en el log. | Exposición de datos personales durante la ventana de retención (R-4). | Retención acotada (ADR-0009). No incluir contenido de correo ni tokens. Acceso restringido al broker y revisión por región. |
| MS-3 | La idempotencia depende de la disciplina de cada consumidor. | Un consumidor nuevo no idempotente duplicaría efectos y rompería CRN-N4. | Pruebas de reentrega para cada consumidor (ADR-0003, BR-2). Todo consumidor declara cómo es idempotente. Los consumidores con efectos externos usan una tabla de `idMensaje`. |
| MS-4 | Mensajes viejos retenidos conviven con versiones nuevas del contrato. | Un consumidor que no entienda una versión retenida aparta mensajes válidos al reprocesar (QA-8). | Soportar la versión actual y la anterior. Retención acotada (ADR-0009). Pruebas de contrato con mensajes de la versión anterior. |
| MS-5 | La clave del trabajo es la reserva del proveedor y no el viaje. | Dos notificaciones del mismo viaje con claves distintas se procesan en paralelo y compiten por la versión del viaje, con reintentos por conflicto (QA-1). | El guardado compara la versión y reintenta ante conflicto. La versión del viaje define el orden final (ADR-0002, ADR-0004). |
