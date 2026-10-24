# ADR-0008: Resiliencia de la ingesta por proveedor

- **Estado:** Propuesto
- **Fecha:** 24/10/2026
- **Decisores:** Equipo de diseño (grupo TPE)
- **Drivers de entrada:** Análisis inicial y drivers e Iteración 1: CU-02 y CU-03. Otros drivers: CRN-N1, QA-1, QA-5, QA-8, R-3, R-4, R-6 y CRN-N4.
- **ADR relacionados:** ADR-0002 (workers y outbox), ADR-0003 (broker), ADR-0005 (motor de la BD), ADR-0006 (modelo canónico y adaptador), ADR-0007 (contratos de mensajes y semántica de entrega), ADR-0009 (tecnología del broker, retención y relay)

---

## 1. Contexto y problema

Los ADR anteriores nombran tres mecanismos de resiliencia y los dejan para este documento. El ADR-0002 (W-1) y el ADR-0001 (ES-1) prevén colas, pools y circuit breaker por proveedor para que un adaptador defectuoso no frene a los demás, y la Iteración 1 lo registra como concern CRN-N1. El ADR-0003 deriva los mensajes inválidos y el trabajo con reintentos agotados a un stream separado, y el ADR-0007 fija que la cantidad de reintentos, las esperas y ese destino se definen acá. El ADR-0003 (BR-3) aclara que el log no recupera lo que el proveedor nunca entregó, y el ADR-0006 deja a este documento la reconciliación con el proveedor cuando vuelve su API. Este documento va después del modelo canónico (ADR-0006) y de los contratos (ADR-0007), porque usa su criterio de validez y sus mensajes, y antes de la tecnología del broker (ADR-0009), porque la retención del log se calcula a partir de la ventana de reconciliación que se fija acá.

Este documento responde tres preguntas:

**Decisión A:** **¿Cómo se evita que la falla o la lentitud de un proveedor afecte a la ingesta de los demás, y qué se reintenta y cuántas veces?**

**Decisión B:** **¿Adónde va y cómo se registra un mensaje que no se puede procesar, sea inválido o con los reintentos agotados?**

**Decisión C:** **¿Cómo se recupera lo ocurrido durante la interrupción de un proveedor cuando su API vuelve a responder?**

Las tres se deciden juntas porque dependen entre sí: el circuit breaker de A decide cuándo empieza y termina una interrupción, que es lo que dispara la reconciliación de C, y la clasificación de fallas de A decide qué llega al stream de B.

**Tensiones:**

- **Aislar sin multiplicar piezas.** El aislamiento más fuerte es un desplegable por proveedor, que el ADR-0002 (A3) y el ADR-0006 (B4) descartaron por costo (R-6). El aislamiento tiene que lograrse dentro del worker y alcanzar para que un proveedor caído no interrumpa a los demás (CRN-N1, QA-8).
- **Reintentar frente a avanzar.** Reintentar recupera fallas transitorias, pero retiene el trabajo y consume los 5 minutos de QA-1. El orden por clave (ADR-0002, ADR-0003) impide saltear un mensaje y seguir con el siguiente de la misma clave.
- **Calidad frente a disponibilidad.** QA-8 mide la calidad de los mensajes: procesar el 99% de los válidos e identificar y registrar el 99% de los inválidos. Si una caída del proveedor se cuenta como inválido, la medida se falsea y se descartan mensajes que eran válidos (ADR-0006).
- **Lo que el proveedor nunca entregó.** Durante una interrupción el proveedor puede no notificar cambios. QA-5 exige recuperar y procesar el 99% de esos eventos dentro de los 5 minutos posteriores a la vuelta de la API. El proveedor es ajeno, con límites de uso y formatos que no se controlan (R-3).
- **Datos personales retenidos.** Guardar los mensajes apartados para analizarlos y reprocesarlos retiene datos personales (R-4).

**Fuera de alcance:**

- Tecnología del broker, nombres concretos de los streams, valores de retención e intervalo del relay (ADR-0009).
- Campos del sobre y semántica de entrega al menos una vez (ADR-0007).
- Criterio de válido e inválido y motivos de contenido (ADR-0006).
- Pipeline de ingesta de correo, incluido el detalle de su reconciliación. El adaptador de correo cumple este mismo esquema, pero su pipeline se decide en otra iteración.
- Resiliencia del Panel API ante fallas de la BD o del read model, alta disponibilidad del broker y de la BD, y monitoreo en detalle (iteración 3, CRN-N3).
- Valores definitivos de los parámetros. Los valores marcados con \* son propuestas del equipo para el prototipo y se ajustan con pruebas contra proveedores simulados.

---

## 2. Criterios de decisión

| Criterio | Qué mide en esta decisión | Se usa en |
| --- | --- | --- |
| CRN-N1 | Si un proveedor caído, lento o con mensajes defectuosos deja de afectar a la ingesta de los demás. | A / B |
| QA-8 | Si cada inválido se identifica y se registra con su motivo sin frenar al resto, y si la medida del 99% no mezcla calidad con disponibilidad. | A / B |
| QA-5 | Si al volver la API se recupera lo ocurrido durante la interrupción, incluido lo que el proveedor no entregó, dentro de los 5 minutos. | A / C |
| QA-1 | Cuánto del presupuesto de 5 minutos consumen los reintentos y las esperas. | A / B |
| R-3 | Si el diseño respeta los límites de uso de cada proveedor y funciona con proveedores que no ofrecen consultas de cambios. | A / C |
| R-4 | Si los mensajes apartados retienen solo los datos personales imprescindibles y por un plazo acotado. | B |
| CRN-N4 | Si reintentos, reprocesos y reconciliación no duplican efectos ni hacen retroceder el estado. | B / C |
| R-6 | Esfuerzo para el grupo y piezas nuevas que operar respecto de las iteraciones 1 y 2. | A / B / C |

---

## 3. Decisión A: aislamiento por proveedor y política de reintentos

### 3.1 Alternativas consideradas

| ID | Alternativa | Resumen |
| --- | --- | --- |
| A1 | Pool compartido con reintento en línea | Todos los proveedores comparten el stream de trabajo, los hilos y las conexiones. El worker reintenta cada mensaje hasta lograrlo. |
| A2 | **Tabiques por proveedor, reintento acotado y circuit breaker que pausa al proveedor** | El stream de trabajo se divide por proveedor. Cada proveedor tiene recursos propios, reintentos acotados y un circuit breaker que, al abrirse, pausa solo su consumo. |
| A3 | Circuit breaker por proveedor sobre un stream común | El stream de trabajo es uno solo. Cuando el circuito de un proveedor se abre, sus mensajes se republican en un stream de reintentos diferidos. |
| A4 | Un desplegable de ingesta por proveedor | Cada proveedor tiene su propio receptor y sus propios workers, desplegados y escalados por separado. |

### 3.2 Decisión

**Se elige A2: el stream de trabajo se divide por proveedor, cada proveedor tiene un tabique de recursos propio y un circuit breaker que pausa solo su consumo, y los reintentos se acotan según el tipo de falla.**

**Descripción de la decisión.** Un tabique (patrón *Bulkhead*) es un conjunto de recursos reservado para un proveedor: si ese proveedor se cae o se pone lento, agota solo su tabique. El circuit breaker (patrón *Circuit Breaker*) deja de llamar a un proveedor que falla y lo prueba de a poco hasta que responde. Ninguno de los dos patrones figura en el catálogo de la cátedra, así que se apoyan en sus tácticas: *Exception Detection* (tiempos de espera), *Retry*, *Removal from Service* (el proveedor se saca temporalmente del consumo), *Introduce Concurrency* (particiones que se procesan en paralelo) y *Limit Event Response* (limitador de tasa por proveedor). El worker clasifica cada intento en uno de cinco resultados y actúa según la tabla de 3.3.

- **Particiones por proveedor.** El stream de trabajo se divide por el proveedor del campo `origen` (ADR-0007) y, dentro de cada proveedor, en N particiones por un hash de la `claveDeOrden`. Cada partición tiene un solo consumidor activo, lo que mantiene la regla de un solo worker por clave del ADR-0002. Agregar un proveedor es configuración: se declaran sus particiones junto con su adaptador (ADR-0006).
- **Tabique por proveedor.** Cada proveedor tiene su pool de conexiones, su límite de llamadas concurrentes y su limitador de tasa, ajustados a los límites de uso que publica (R-3). Una llamada tiene un tiempo de espera de 2 s para conectar y de 10 s en total\*.
- **Reintento acotado de las fallas del proveedor.** Una falla del proveedor (tiempo de espera, error de red o respuesta 5xx, ADR-0006) se reintenta dentro del mismo intento: hasta 3 llamadas con esperas de 1 s y 2 s más un desvío aleatorio de hasta el 50%\*. Una respuesta 429 respeta la espera que indica el proveedor y no cuenta para el circuito, porque el proveedor está pidiendo bajar el ritmo y no está caído. Un mensaje inválido nunca se reintenta.
- **Circuit breaker por proveedor que pausa.** El circuito se abre si en las últimas 20 llamadas al proveedor, con un mínimo de 10, la mitad o más son fallas del proveedor\*. Con el circuito abierto, las particiones de ese proveedor dejan de consumirse y sus mensajes esperan en el log sin confirmar. El circuito queda abierto 15 s y el plazo se duplica con cada prueba fallida hasta un máximo de 60 s\*. Al vencer el plazo pasa a semiabierto y prueba con una llamada real, la del mensaje pendiente más viejo. El tope de 60 s acota la demora en detectar que la API volvió y deja al menos 4 de los 5 minutos de QA-5 para ponerse al día.
- **Solo se agota lo atribuible al mensaje.** Un error interno causado por el mensaje (por ejemplo, una excepción no prevista del adaptador) se reintenta hasta 5 entregas con esperas de 1, 4, 15 y 30 s\*, y después el mensaje se aparta con el motivo «agotado» (Decisión B). Un conflicto de versión al guardar se reintenta en el acto, porque es esperable (ADR-0007, MS-5). Las fallas del proveedor nunca agotan un mensaje: esperan con el circuito. Las fallas de la BD o del broker pausan todo el consumo del worker y tampoco apartan mensajes. El contador de entregas tiene que sobrevivir a la caída del worker (ADR-0009).

### 3.3 Cómo funciona

Cada intento de procesar un mensaje de trabajo termina en uno de estos resultados:

| Resultado | Ejemplo | Qué hace el worker | ¿Confirma el mensaje? |
| --- | --- | --- | --- |
| Válido | El adaptador devuelve la reserva canónica. | Guarda el cambio y el evento en una transacción (ADR-0002). | Sí, después del `COMMIT`. |
| Inválido | Falta un campo obligatorio o la `versionContrato` es desconocida (ADR-0006, ADR-0007). | Lo aparta con su motivo (Decisión B). | Sí, después de apartarlo y registrarlo. |
| Falla del proveedor | Tiempo de espera o respuesta 5xx al llamar a *Consultar*. | Reintenta hasta 3 llamadas. Si sigue fallando, suma al circuito y deja el mensaje pendiente. | No. Espera con su partición. |
| Error atribuible al mensaje | Excepción no prevista del adaptador con ese mensaje. | Reentrega con espera creciente hasta 5 entregas y después lo aparta con el motivo «agotado». | Solo al apartarlo. |
| Falla de infraestructura | La BD está en failover (ADR-0005). | Pausa todo su consumo hasta que la BD responda. | No. Se reentrega al volver. |

Caso práctico: la API de la aerolínea X deja de responder a las 14:00 durante una tormenta.

1. Las llamadas a *Consultar* de la aerolínea X agotan sus 3 intentos. A las 14:00:20 la mitad de las últimas llamadas son fallas y el circuito se abre.
2. Las particiones de la aerolínea X dejan de consumirse. Sus notificaciones siguen llegando al receptor, que las encola, y esperan en el log sin confirmar.
3. Los workers siguen procesando a los hoteles y a las otras aerolíneas con sus propios tabiques. El panel muestra la última versión conocida desde el read model (ADR-0004).
4. El circuito prueba con una llamada real a los 15, 30 y 60 s, y después cada 60 s.
5. A las 14:20 la prueba responde bien. El circuito se cierra, las particiones se reanudan y empieza la reconciliación (Decisión C).

| Qué falla | Qué pasa | Resultado |
| --- | --- | --- |
| Un proveedor responde lento y agota su pool. | Sus llamadas esperan en su tabique. | Los demás proveedores no se ven afectados (CRN-N1). |
| Un proveedor responde 429. | El worker espera lo que indica el proveedor y el circuito no se abre. | Se respetan los límites de uso (R-3). |
| Un mensaje hace fallar al adaptador cada vez. | Se reentrega 5 veces y se aparta como «agotado». | Su partición queda demorada como máximo unos 50 s. El resto sigue. |
| El worker se cae con mensajes pendientes. | Otro worker toma la partición y los reprocesa. El contador de entregas se conserva. | Un mensaje que tumba al worker no se reintenta sin fin. |
| La BD está en failover. | Los workers pausan el consumo. Nada se aparta. | Se demora la frescura sin perder mensajes. |

### 3.4 Por qué no las otras alternativas

- **A1. Pool compartido con reintento en línea.** Un proveedor lento retiene hilos y conexiones de todos, así que una caída de la aerolínea X frena a los hoteles. Es justo el riesgo W-1 y ES-1, y contradice CRN-N1 y la condición de QA-8 de no interrumpir la integración con el resto. Reintentar sin límite además consume los 5 minutos de QA-1 sin avanzar.
- **A3. Circuit breaker sobre un stream común.** Para no frenar a los demás, los mensajes del proveedor caído tienen que salir del stream común hacia uno de reintentos. Eso rompe el orden por clave (ADR-0002) y multiplica los mensajes durante una disrupción, cuando más carga hay (QA-1). Si en cambio se frenara el stream común, un proveedor caído frenaría a todos (CRN-N1).
- **A4. Un desplegable por proveedor.** Da el aislamiento más fuerte, pero multiplica los desplegables, lo que el ADR-0001, el ADR-0002 (A3) y el ADR-0006 (B4) descartaron por costo (R-6). Los tabiques dentro del worker cubren CRN-N1 con el volumen del prototipo. Como cada proveedor ya tiene particiones y adaptador propios, extraerlo a un desplegable queda como evolución sin rediseñar los mensajes.

---

## 4. Decisión B: stream de apartados con registro en la BD

### 4.1 Alternativas consideradas

| ID | Alternativa | Resumen |
| --- | --- | --- |
| B1 | Descartar y dejar constancia en el log de la aplicación | El mensaje que no se puede procesar se confirma y se escribe una línea en el log de la aplicación. |
| B2 | **Stream de apartados por stream de origen y registro en la BD sin el cuerpo** | El mensaje completo va a un stream de apartados del broker. Sus metadatos y su motivo van a una tabla de la BD operacional. |
| B3 | BD como depósito de apartados | El mensaje completo, con su cuerpo, se guarda en una tabla de la BD operacional. |
| B4 | No apartar: reintentar hasta lograrlo | El mensaje que falla se reintenta indefinidamente en su partición. |

### 4.2 Decisión

**Se elige B2: un mensaje que no se puede procesar va completo a un stream de apartados según su stream de origen, y sus metadatos y su motivo se registran en una tabla de la BD operacional, sin el cuerpo.**

**Descripción de la decisión.** Un mensaje apartado es uno que se saca del camino normal para no frenar a los demás: un inválido de contenido (ADR-0006), un inválido de contrato (ADR-0007) o un mensaje con los reintentos agotados (Decisión A). Es lo que en la iteración 1 se llamó cola de mensajes inválidos. El stream conserva el mensaje para reprocesarlo, y el registro permite contar y consultar. Aplica las tácticas *Sanity Checking* (validación antes de procesar), *Exception Handling* (el mensaje se aparta en lugar de frenar el consumo) y *Monitor* (alerta de apartados por proveedor).

- **Un stream de apartados por stream de origen.** Hay uno para el stream de trabajo y otro para el de eventos. Cada apartado lleva el mensaje original (sobre y cuerpo) más el motivo, un detalle técnico acotado, la cantidad de intentos y la hora en que se apartó. El mensaje original se confirma solo después de que el apartado y el registro quedaron guardados.
- **Registro en la BD sin el cuerpo.** Una tabla de mensajes apartados guarda `idMensaje` (único, así registrar dos veces no duplica), stream de origen, `origen`, `claveDeOrden`, `correlacion`, `horaDelHecho`, motivo, detalle técnico, intentos, hora y estado (pendiente, reprocesado o descartado). No guarda el cuerpo ni datos de la reserva (R-4). Las filas se conservan 90 días\*. Esta tabla es la fuente para medir QA-8.
- **Motivos cerrados.** Los motivos son «estructura», «campo obligatorio», «invariante» y «tipo no soportado» (ADR-0006), «contrato» (ADR-0007) y «agotado» (Decisión A). Una falla del proveedor nunca es un motivo, así que los apartados miden la calidad de los mensajes y no la disponibilidad de los proveedores.
- **Reproceso explícito por estado actual.** Después de corregir un adaptador, un comando de operación republica los apartados elegidos (por proveedor y motivo) en su partición, con el mismo `idMensaje`, y marca su registro como reprocesado. Si la reserva ya existe, el worker usa *Consultar* en lugar del contenido viejo del mensaje, así un reproceso tardío no hace retroceder el estado (CRN-N4).
- **Alerta por proveedor.** Se mide la cantidad de apartados por proveedor y motivo. Un salto brusco suele indicar que el proveedor cambió su contrato (ADR-0006, MC-5) y se corrige en su adaptador.

### 4.3 Por qué no las otras alternativas

- **B1. Descartar y dejar constancia en el log de la aplicación.** El mensaje se pierde: no se puede reprocesar después de corregir el adaptador, lo que contradice el replay del ADR-0003. Las líneas de log no tienen motivo codificado ni identificador único, así que contar el 99% de QA-8 depende de procesar texto libre y un mismo inválido reentregado se cuenta dos veces.
- **B3. BD como depósito de apartados.** Guarda en la BD operacional notificaciones crudas con datos personales sin un plazo natural de borrado (R-4) y suma escrituras grandes a la base que ya es el punto de presión (ADR-0005, BD-1 y BD-4). B2 escribe en la BD solo una fila chica de metadatos y deja el cuerpo en el log, con retención acotada (ADR-0009).
- **B4. No apartar.** Un mensaje que falla siempre bloquea su partición para siempre y frena a todas las claves que comparten esa partición, lo que incumple la condición de QA-8 de no interrumpir la integración y consume el presupuesto de QA-1. Además, el inválido nunca queda registrado.

---

## 5. Decisión C: reconciliación disparada por el cierre del circuito

### 5.1 Alternativas consideradas

| ID | Alternativa | Resumen |
| --- | --- | --- |
| C1 | Solo el log | Al volver la API se procesan los mensajes que esperaban en el log. No se consulta nada al proveedor. |
| C2 | **Reconciliación al cerrarse el circuito: primero incremental y, si no alcanza, por estado actual** | Al volver la API se procesa lo retenido y se pregunta al proveedor qué cambió durante la interrupción. Si el proveedor no lo permite, o la interrupción es muy larga, se consulta el estado actual de las reservas activas. |
| C3 | Reconciliación periódica completa | Cada cierto tiempo se consulta el estado actual de todas las reservas activas de todos los proveedores, haya habido interrupción o no. |
| C4 | Esperar a que el proveedor reenvíe | Se confía en que el proveedor reintente las notificaciones que no pudo entregar. |

### 5.2 Decisión

**Se elige C2: cuando el circuito de un proveedor se cierra después de una interrupción, se procesa lo retenido en el log y se pide al proveedor lo que cambió durante la interrupción. Si el proveedor no permite esa consulta, o la interrupción supera la ventana de reconciliación, se consulta el estado actual de sus reservas activas.**

**Descripción de la decisión.** La reconciliación vuelve a alinear el estado guardado con el del proveedor después de una interrupción (táctica *State Resynchronization* de la cátedra). Lo que llegó durante la interrupción ya está en el log y se procesa al reanudar las particiones. Lo que el proveedor nunca notificó se obtiene preguntándole. Todo entra por el camino normal del worker, que es idempotente (ADR-0007), así que un cambio que aparece por las dos vías no tiene efecto doble.

- **Una reconciliación por interrupción.** Cuando un circuito se abre, se registra una interrupción del proveedor en la BD (una sola abierta por proveedor, con su hora de inicio). La instancia que cierra el circuito marca el fin y deja la reconciliación pendiente. Un worker la toma con bloqueo de filas, como el relay (ADR-0002). Si se cae, otro la retoma.
- **Primero lo retenido, en paralelo lo no entregado.** Las particiones se reanudan y procesan los mensajes que esperaban. Al mismo tiempo, la reconciliación pregunta al proveedor qué reservas cambiaron entre el inicio menos 5 minutos y el fin de la interrupción. El margen de 5 minutos cubre lo que estaba en curso cuando el proveedor cayó.
- **Operación nueva del adaptador.** La interfaz del ADR-0006 suma la operación *Consultar cambios*, que recibe un intervalo y devuelve los identificadores externos con cambios, o indica que el proveedor no la soporta. *Describir* declara si el adaptador la soporta. Las tres operaciones existentes no cambian, como prevé el ADR-0006.
- **Trabajo sintético por el camino normal.** Cada identificador se encola como un mensaje de trabajo con el sobre del ADR-0007: `claveDeOrden` `<proveedor>:<identificador externo>`, `correlacion` nueva y un cuerpo con el identificador y la marca de reconciliación. El worker usa *Consultar*. Si ese cambio ya llegó por una notificación retenida, la comparación con el estado guardado lo deja sin efecto (ADR-0007, decisión B).
- **Ventana de reconciliación y caída a estado actual.** La reconciliación incremental cubre interrupciones de hasta 24 h\*. Si la interrupción es más larga, o el proveedor no soporta *Consultar cambios*, se consulta el estado actual de las reservas activas de ese proveedor guardadas en la BD (no canceladas y con fin futuro), primero las de inicio más próximo, dentro del limitador de tasa del proveedor. La retención del stream de trabajo se fija a partir de esta ventana (ADR-0009), porque durante la interrupción el log es la única fuente exacta de lo que llegó.

### 5.3 Cómo funciona

Siguiendo el caso de 3.3, la API de la aerolínea X volvió a las 14:20 después de 20 minutos.

1. La instancia que cerró el circuito marca el fin de la interrupción (inicio 14:00:20, fin 14:20) y deja la reconciliación pendiente.
2. Las particiones de la aerolínea X se reanudan y procesan, en orden por clave, las notificaciones que llegaron entre las 14:00 y las 14:20.
3. Un worker toma la reconciliación y llama a *Consultar cambios* entre las 13:55:20 y las 14:20. El proveedor devuelve 140 identificadores.
4. Cada identificador se encola como trabajo sintético. Los que coinciden con notificaciones ya procesadas no tienen efecto. Los demás actualizan la reserva, suben la versión del viaje y emiten su evento.
5. Con `horaDelHecho` y `correlacion` se mide cuántos eventos de la interrupción quedaron procesados antes de las 14:25 (QA-5).

| Qué falla | Qué pasa | Resultado |
| --- | --- | --- |
| El proveedor no ofrece *Consultar cambios*. | Se consulta el estado actual de sus reservas activas, primero las más próximas. | Se recupera lo no entregado, más lento si hay muchas reservas activas. |
| La interrupción dura 30 h. | Supera la ventana de 24 h: se pasa a estado actual. Los mensajes retenidos que se recortaron del log quedan cubiertos por esa consulta. | No se pierden cambios de reservas activas. |
| El worker se cae a mitad de la reconciliación. | La interrupción sigue pendiente y otro worker la retoma. | Los trabajos ya encolados se reprocesan sin efecto doble. |
| La reconciliación choca con el límite de uso del proveedor. | El limitador de tasa espacia las consultas y las respuestas 429 se respetan. | La recuperación se alarga, pero el proveedor no bloquea a Trotamundos (R-3). |
| El circuito se abre otra vez durante la reconciliación. | Los trabajos sintéticos esperan en sus particiones como cualquier otro. | La reconciliación sigue cuando el circuito vuelve a cerrarse. |

### 5.4 Por qué no las otras alternativas

- **C1. Solo el log.** El log solo tiene lo que el proveedor entregó. Lo que no notificó durante la interrupción no se recupera, que es justo el riesgo BR-3 del ADR-0003, así que QA-5 no se cumple.
- **C3. Reconciliación periódica completa.** Consulta todas las reservas activas de todos los proveedores aunque nada haya fallado. Con 15 M de cuentas eso consume los límites de uso de los proveedores (R-3) y suma carga de lectura y escritura a la BD compartida (ADR-0005, BD-1) para un caso que solo ocurre tras una interrupción. Además, con un período largo no cumple los 5 minutos de QA-5, y con uno corto el costo se multiplica.
- **C4. Esperar a que el proveedor reenvíe.** Cada proveedor decide si reintenta y por cuánto tiempo, y ese comportamiento es una condición dada que no se controla (R-3). No hay forma de verificar QA-5 con un supuesto que depende de terceros.

---

## 6. Consecuencias

### Positivas

- Un proveedor caído, lento o con mensajes defectuosos queda acotado a su tabique y a sus particiones, y la ingesta de los demás sigue (CRN-N1, QA-8).
- Los apartados distinguen calidad de disponibilidad: el 99% de QA-8 se mide sobre el registro, con motivo codificado y sin contar caídas de proveedores.
- Lo que llegó durante una interrupción se procesa al reanudar, y lo que el proveedor no entregó se recupera con la reconciliación, con la detección de la vuelta acotada a 60 s (QA-5).
- Los datos personales de los apartados quedan solo en el stream, con retención acotada (ADR-0009), y el registro en la BD no guarda el cuerpo. La exposición no supera la del stream de trabajo (ADR-0007, MS-2) (R-4).
- Reintentos, reprocesos y reconciliación pasan por el mismo camino idempotente del ADR-0007, sin efectos dobles ni retrocesos (CRN-N4).
- No suma desplegables: los tabiques, el circuito y la reconciliación viven en el worker, y existen bibliotecas maduras para implementarlos (por ejemplo Resilience4j en Java) (R-6).

### Negativas y riesgos aceptados

| ID | Riesgo | Efecto | Mitigación |
| --- | --- | --- | --- |
| RS-1 | La resiliencia depende de código y parámetros propios: streams por proveedor, circuit breaker y clasificación de cada falla. | Más esfuerzo de implementación (R-6). Una falla mal clasificada (un proveedor que responde éxito con un cuerpo de error, o un error propio tomado como falla del proveedor) infla los apartados o deja el circuito sin abrir, lo que distorsiona QA-8 y CRN-N1. | Biblioteca estándar para tabiques, reintentos y circuito. Particiones y parámetros por configuración. La clasificación vive en el adaptador, con pruebas de contrato (ADR-0006, MC-2) y con proveedores simulados que fallan. Alerta por salto de apartados o de fallas por proveedor. |
| RS-2 | Un mensaje con errores repetidos bloquea su partición mientras se reintenta. | Las otras claves de esa partición se demoran hasta unos 50 s (QA-1). | Reintentos acotados a 5 entregas. Más particiones por proveedor si la demora se repite. |
| RS-3 | La reconciliación por estado actual puede no terminar en 5 minutos si el proveedor tiene muchas reservas activas o límites de uso bajos. | Parte de QA-5 no se cumple para ese proveedor. | Primero las reservas de inicio más próximo. Pedir a los proveedores una consulta de cambios. Medir la duración de la reconciliación por proveedor. Se acepta para interrupciones de más de 24 h. |
