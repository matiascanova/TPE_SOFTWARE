# ADR-0008: Resiliencia de la ingesta por proveedor

- **Estado:** Propuesto
- **Fecha:** 24/10/2026
- **Decisores:** Equipo de diseño (grupo TPE)
- **Drivers de entrada:** Análisis inicial y drivers e Iteración 1: CU-02 y CU-03. Otros drivers: CRN-N1, QA-1, QA-5, QA-8, R-3, R-4 y R-6.
- **ADR relacionados:** ADR-0001 (estilo C-lite), ADR-0002 (workers y outbox), ADR-0003 (broker), ADR-0005 (motor de la BD), ADR-0006 (modelo canónico y adaptador), ADR-0007 (contratos de mensajes y semántica de entrega), ADR-0009 (tecnología del broker, retención y relay)

---

## 1. Contexto y problema

Los ADR-0002 y 0003 prevén colas, pools y circuit breaker por proveedor (W-1, CRN-N1), un stream separado para los mensajes inválidos y los reintentos agotados, y una reconciliación con el proveedor cuando vuelve su API (BR-2). El ADR-0007 deja a este documento la cantidad de reintentos, las esperas y el destino de los mensajes apartados, y el ADR-0006 le deja el registro de los inválidos y la reconciliación con el proveedor, que puede sumar operaciones al adaptador. Va antes del ADR-0009 porque la retención del log sale de la ventana de reconciliación que se fija acá.

Este documento responde tres preguntas:

**Decisión A:** **¿Cómo se evita que la falla de un proveedor afecte a los demás, y qué se reintenta?**

**Decisión B:** **¿Adónde va y cómo se registra un mensaje que no se puede procesar?**

**Decisión C:** **¿Cómo se recupera lo ocurrido durante la interrupción de un proveedor cuando su API vuelve?**

Se deciden juntas porque el circuit breaker de A marca el inicio y el fin de la interrupción que dispara C, y la clasificación de fallas de A decide qué llega a B.

**Tensiones:**

- **Aislar sin multiplicar piezas.** Un desplegable por proveedor aísla mejor, pero se descartó por costo (ADR-0002, R-6). El aislamiento tiene que lograrse dentro del worker (CRN-N1).
- **Reintentar frente a avanzar.** Reintentar recupera fallas transitorias, pero consume los 5 minutos de QA-1, y el orden por clave impide saltear un mensaje (ADR-0002).
- **Calidad frente a disponibilidad.** QA-8 mide la calidad de los mensajes. Si una caída del proveedor se cuenta como inválido, la medida se falsea (ADR-0006).
- **Lo que el proveedor nunca entregó.** QA-5 exige recuperarlo dentro de los 5 minutos posteriores a la vuelta de la API, con proveedores ajenos y con límites de uso (R-3).

**Fuera de alcance:** tecnología del broker, retención y relay (ADR-0009); campos de los mensajes (ADR-0007); criterio de validez (ADR-0006); pipeline de correo; alta disponibilidad y monitoreo (iteración 3). Los valores con \* son propuestas para el prototipo y se ajustan con proveedores simulados.

---

## 2. Criterios de decisión

| Criterio | Qué mide en esta decisión | Se usa en |
| --- | --- | --- |
| CRN-N1 | Si un proveedor caído o lento deja de afectar a los demás. | A / B |
| QA-8 | Si cada inválido se registra con su motivo sin frenar al resto, sin mezclar calidad con disponibilidad. | A / B |
| QA-5 | Si al volver la API se recupera lo ocurrido durante la interrupción dentro de los 5 minutos. | A / C |
| QA-1 | Cuánto del presupuesto de 5 minutos consumen los reintentos. | A |
| R-3 | Si se respetan los límites de uso y se funciona con proveedores que no ofrecen consultas de cambios. | A / C |
| R-4 | Si los apartados retienen solo los datos personales imprescindibles. | B |
| R-6 | Esfuerzo y piezas nuevas para el grupo. | A / B / C |

---

## 3. Decisión A: aislamiento por proveedor y reintentos

### 3.1 Alternativas consideradas

| ID | Alternativa | Resumen |
| --- | --- | --- |
| A1 | Pool compartido con reintento en línea | Todos los proveedores comparten stream, hilos y conexiones. Cada mensaje se reintenta hasta lograrlo. |
| A2 | **Tabiques por proveedor con circuit breaker que pausa** | El stream de trabajo se divide por proveedor. Cada uno tiene recursos propios, reintentos acotados y un circuit breaker que pausa solo su consumo. |
| A3 | Circuit breaker sobre un stream común | Un solo stream. Los mensajes del proveedor caído se republican en un stream de reintentos diferidos. |
| A4 | Un desplegable por proveedor | Cada proveedor tiene su receptor y sus workers. |

### 3.2 Decisión

**Se elige A2: el stream de trabajo se divide por proveedor, cada proveedor tiene recursos propios y un circuit breaker que pausa solo su consumo, y los reintentos se acotan según el tipo de falla.**

**Descripción de la decisión.** Cada proveedor tiene un tabique (patrón *Bulkhead*), es decir, recursos reservados que solo él agota. Tiene además un circuit breaker que deja de llamarlo cuando falla y lo prueba de a poco. Como ninguno de los dos patrones está en el catálogo de la cátedra, se apoyan en sus tácticas *Exception Detection*, *Retry*, *Removal from Service*, *Introduce Concurrency* y *Limit Event Response*.

- **Particiones por proveedor.** Son las colas por proveedor que prevé la iteración 1 (CRN-N1). El stream se divide por el proveedor del campo `origen` (ADR-0007) y, dentro de cada uno, en particiones por hash de la `claveDeOrden`, con un solo consumidor activo por partición (ADR-0002).
- **Recursos propios.** Cada proveedor tiene su pool de conexiones, su límite de concurrencia y su limitador de tasa, ajustados a sus límites de uso (R-3).
- **Reintento según la falla.** Una falla del proveedor (tiempo de espera, error de red o 5xx) se reintenta hasta 3 veces con espera creciente\*. Un 429 respeta la espera que pide el proveedor. Un inválido nunca se reintenta. Un error propio causado por el mensaje se reintenta hasta 5 entregas\* y después se aparta como «agotado». Una falla de la BD o del broker pausa el consumo sin apartar nada (ADR-0005).
- **Circuito que pausa.** Si la mitad de las últimas 20 llamadas falla\*, el circuito se abre y las particiones del proveedor dejan de consumirse: sus mensajes esperan en el log. El circuito prueba con una llamada real cada vez más espaciada, hasta un máximo de 60 s\*. Ese tope deja al menos 4 de los 5 minutos de QA-5 para ponerse al día.

### 3.3 Por qué no las otras alternativas

- **A1.** Un proveedor lento retiene los recursos de todos y frena al resto (CRN-N1, QA-8). Reintentar sin límite consume QA-1 sin avanzar.
- **A3.** Sacar los mensajes a un stream de reintentos rompe el orden por clave (ADR-0002) y multiplica los mensajes durante una disrupción (QA-1).
- **A4.** Multiplica los desplegables, descartado por costo en los ADR-0001, 0002 y 0006 (R-6). Queda como evolución, porque cada proveedor ya tiene particiones y adaptador propios.

---

## 4. Decisión B: stream de apartados con registro en la BD

### 4.1 Alternativas consideradas

| ID | Alternativa | Resumen |
| --- | --- | --- |
| B1 | Descartar y dejar una línea en el log de la aplicación | El mensaje se confirma y se pierde. |
| B2 | **Stream de apartados y registro en la BD sin el cuerpo** | El mensaje completo va a un stream de apartados. Sus metadatos y su motivo van a una tabla de la BD. |
| B3 | BD como depósito | El mensaje completo se guarda en una tabla de la BD. |
| B4 | No apartar | El mensaje se reintenta indefinidamente. |

### 4.2 Decisión

**Se elige B2: un mensaje que no se puede procesar va completo a un stream de apartados, y sus metadatos y su motivo se registran en la BD sin el cuerpo.**

**Descripción de la decisión.** Un apartado es un mensaje inválido o con los reintentos agotados que se saca del camino para no frenar al resto. Es lo que en la iteración 1 se llamó cola de mensajes inválidos. Aplica las tácticas *Sanity Checking*, *Exception Handling* y *Monitor*.

- **Un stream de apartados por stream de origen** (trabajo y eventos), con el mensaje original, el motivo y los intentos. El original se confirma solo después de apartarlo y registrarlo.
- **Registro sin el cuerpo.** La tabla guarda `idMensaje` (único), `origen`, `claveDeOrden`, `correlacion`, motivo, intentos, hora y estado, pero no datos de la reserva (R-4). Es la fuente para medir QA-8.
- **Motivos cerrados:** los de contenido del ADR-0006, «contrato» (ADR-0007) y «agotado». Una caída del proveedor nunca es un motivo.
- **Reproceso explícito.** Después de corregir un adaptador, los apartados se republican con el mismo `idMensaje`. Si la reserva ya existe, el worker usa *Consultar* (ADR-0006) para no volver a un estado viejo.

### 4.3 Por qué no las otras alternativas

- **B1.** El mensaje no se puede reprocesar (ADR-0003), y contar el 99% de QA-8 sobre texto libre no es confiable.
- **B3.** Guarda datos personales sin plazo de borrado en la BD (R-4) y suma escrituras grandes a la base, que ya es el punto de presión (ADR-0005, CRN-N2).
- **B4.** Un mensaje que siempre falla bloquea su partición para siempre (QA-8, QA-1) y nunca queda registrado.

---

## 5. Decisión C: reconciliación al cerrarse el circuito

### 5.1 Alternativas consideradas

| ID | Alternativa | Resumen |
| --- | --- | --- |
| C1 | Solo el log | Al volver la API se procesa lo que esperaba en el log. |
| C2 | **Reconciliación incremental al cerrarse el circuito, con caída a estado actual** | Se procesa lo retenido y se pregunta al proveedor qué cambió. Si no se puede, se consulta el estado actual de las reservas activas. |
| C3 | Reconciliación periódica completa | Cada cierto tiempo se consultan todas las reservas activas de todos los proveedores. |
| C4 | Esperar a que el proveedor reenvíe | Se confía en los reintentos del proveedor. |

### 5.2 Decisión

**Se elige C2: cuando el circuito se cierra, se procesa lo retenido en el log y se pide al proveedor lo que cambió durante la interrupción. Si no lo permite, o la interrupción supera las 24 h\*, se consulta el estado actual de sus reservas activas.**

**Descripción de la decisión.** Aplica la táctica *State Resynchronization*. Todo entra por el camino idempotente del worker (ADR-0007), así que un cambio que llega por las dos vías no tiene efecto doble.

- **Una reconciliación por interrupción.** La interrupción se registra en la BD al abrirse el circuito y queda pendiente de reconciliar al cerrarse. Un worker la toma con bloqueo de filas, y si se cae la retoma otro.
- **Operación nueva del adaptador.** *Consultar cambios* recibe un intervalo (inicio menos 5 minutos, fin) y devuelve los identificadores con cambios. Las tres operaciones del ADR-0006 no cambian.
- **Trabajo por el camino normal.** Cada identificador se encola como trabajo con el sobre del ADR-0007, y el worker usa *Consultar*.
- **Caída a estado actual.** Se consultan las reservas activas del proveedor, primero las de inicio más próximo, dentro de su limitador de tasa. La retención del trabajo se fija a partir de esta ventana (ADR-0009).

### 5.3 Cómo funciona

La API de la aerolínea X cae a las 14:00. A los pocos segundos el circuito se abre y sus mensajes quedan esperando en el log, mientras los demás proveedores siguen. A las 14:20 una prueba responde bien y el circuito se cierra. Se procesan las notificaciones retenidas y, en paralelo, *Consultar cambios* entre las 13:55 y las 14:20 devuelve los identificadores. Los que ya llegaron por notificación no tienen efecto, y los demás actualizan sus reservas antes de las 14:25 (QA-5).

### 5.4 Por qué no las otras alternativas

- **C1.** El log no tiene lo que el proveedor nunca notificó (ADR-0003, BR-2), así que QA-5 no se cumple.
- **C3.** Consulta todo aunque nada haya fallado: agota los límites de uso (R-3) y carga la BD. Con un período largo, además, no cumple los 5 minutos de QA-5.
- **C4.** Depende de un comportamiento del proveedor que no se controla (R-3), así que QA-5 no se puede verificar.

---

## 6. Consecuencias

### Positivas

- Un proveedor caído o defectuoso queda acotado a sus particiones y a sus recursos, y el resto sigue (CRN-N1, QA-8).
- QA-8 se mide sobre un registro con motivo codificado, sin contar caídas de proveedores.
- Lo retenido y lo no entregado se recuperan al volver la API, con la detección acotada a 60 s (QA-5).
- No suma desplegables y existen bibliotecas para implementarlo, por ejemplo Resilience4j en Java (R-6).

### Negativas y riesgos aceptados

| ID | Riesgo | Efecto | Mitigación |
| --- | --- | --- | --- |
| RS-1 | La resiliencia depende de código y parámetros propios, incluida la clasificación de cada falla. | Más esfuerzo (R-6). Una falla mal clasificada distorsiona QA-8 o deja el circuito sin abrir (CRN-N1). | Biblioteca estándar. Pruebas de contrato con proveedores simulados que fallan. Alerta por salto de apartados. |
| RS-2 | Un mensaje con errores repetidos bloquea su partición mientras se reintenta. | Las otras claves de la partición se demoran hasta unos 50 s (QA-1). | Reintentos acotados. Más particiones si la demora se repite. |
| RS-3 | La reconciliación por estado actual puede tardar más de 5 minutos con muchas reservas activas. | Parte de QA-5 no se cumple para ese proveedor. | Primero los viajes próximos. Medir su duración. Se acepta para interrupciones de más de 24 h. |
