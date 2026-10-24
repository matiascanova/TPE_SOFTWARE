# ADR-0002: Workers asíncronos de ingesta y outbox transaccional

- **Estado:** Propuesto
- **Fecha:** 19/10/2026
- **Decisores:** Equipo de diseño (grupo TPE)
- **Drivers de entrada:** [Análisis inicial y drivers](Analisis-Inicial-y-Drivers.md) (CU-02, CU-03; QA-1, QA-5, QA-6, QA-7, QA-8, QA-12; R-2, R-6)
- **ADR relacionados:** ADR-0001 (estilo C-lite), ADR-0003 (broker), ADR-0004 (read model)

---

## 1. Contexto y problema

Trotamundos recibe notificaciones de proveedores de viaje y de correo (el receptor y el planificador de sondeo las encolan, ADR-0003). Pero una notificación casi nunca trae la reserva completa: hay que consultar al proveedor o leer el correo, interpretar el contenido, traducirlo al modelo canónico y guardarlo. Después hay que avisar al Panel API para que actualice el read model (copia de los datos optimizada para las lecturas del panel, ADR-0004).

El ADR-0001 incorpora los workers y el outbox como parte de C-lite y remite a este documento para justificarlos. Este documento responde dos preguntas:

1. **¿Por qué existe un rol de workers**, separado del receptor y del Panel API?
2. **¿Cómo se guarda un cambio y se avisa al panel** sin perder ni inventar eventos? La respuesta es un outbox: una tabla de eventos pendientes que se guarda junto con el cambio, y un relay, el proceso que los publica en el broker.

**Tensiones de la pregunta 1 (workers):**

- **Procesar es lento y variable.** Implica llamadas a APIs externas, lectura de correo y extracción de la reserva (CU-02, CU-03). No cabe en la respuesta rápida que el proveedor espera.
- **Otra curva de escala.** El correo de 15 M de cuentas (QA-7) escala con un patrón distinto al de las lecturas del panel (QA-6).
- **Un contrato por proveedor.** La integración se adapta a las APIs de cada uno (R-2), y un proveedor defectuoso no debe afectar a los demás (QA-8).

**Tensión de la pregunta 2 (outbox): el problema de la doble escritura.** Cuando llega la confirmación de un hotel, el worker debe guardar la reserva en la base y publicar el evento "reserva creada" en el broker. Esos dos sistemas no comparten transacción, así que una operación puede fallar sin la otra:

- Si se guarda y falla la publicación, la reserva existe pero el panel nunca se entera (QA-1, QA-5).
- Si se publica y falla el guardado, el panel muestra una reserva que no existe.

Como en los otros ADR, la viabilidad de implementación es un criterio de primer orden con plazo acotado (R-6).

**Fuera de alcance:** el broker y sus streams (ADR-0003), el read model y cómo el Panel API lo mantiene (ADR-0004), el motor y la partición de la base (decisión de almacenamiento posterior), el pipeline de ingesta de correo y el diseño de la analítica.

---

## 2. Criterios de decisión

| Criterio | Qué mide en esta decisión | Se usa en |
|---|---|---|
| QA-1 | Que el cambio llegue al panel en menos de 5 minutos, sin perderse. | A y B |
| QA-5 | Absorber ráfagas y fallas de proveedores sin perder cambios y poder recuperar lo generado durante una interrupción. | A y B |
| QA-6 / QA-7 | Que el procesamiento escale por separado de las lecturas del panel y alcance 15 M de cuentas. | A y B |
| QA-8 | Que un proveedor defectuoso no interrumpa la integración con los demás. | A |
| QA-12 | Que sumar un medio de transporte (por ejemplo trenes) no modifique el núcleo. | A |
| R-6 | Que se pueda implementar y demostrar en el plazo acotado. | A y B |

---

## 3. Decisión A: workers de ingesta

### 3.1 Alternativas consideradas

| ID | Alternativa | Resumen |
|---|---|---|
| A1 | Procesar en el receptor | El receptor consulta al proveedor, interpreta y guarda antes de responder. No hay workers. |
| **A2** | **Workers asíncronos dentro del desplegable de ingesta** | El receptor solo encola. Los workers toman el trabajo del stream y lo procesan con un adaptador por proveedor. |
| A3 | Un servicio por proveedor | Cada adaptador es un desplegable propio, con su propio contrato y su propia infraestructura. |
| A4 | Funciones serverless por evento | Una función por notificación, disparada por el stream o por webhooks. |

### 3.2 Decisión

**Se elige A2: el procesamiento de las notificaciones lo hacen workers asíncronos, que toman el trabajo del stream y viven en el mismo artefacto desplegable que el receptor, con un adaptador por proveedor y por medio de transporte.**

**Descripción de la decisión.** Los workers absorben la diferencia entre la respuesta rápida que espera el proveedor y un procesamiento lento y variable. Corren como procesos separados del receptor, trabajan a un ritmo propio, escalan agregando instancias al mismo grupo de consumo y concentran los reintentos, el aislamiento por proveedor y los adaptadores. Comparten el código del dominio con el resto del sistema (ADR-0001).

- **Adaptadores por puertos.** Cada proveedor y cada medio de transporte entra como un adaptador que traduce al modelo canónico. Sumar trenes es un adaptador nuevo (QA-12).
- **Aislamiento por proveedor.** Colas, pools de conexiones y circuit breaker por proveedor dentro del worker (mitigación de ES-1, ADR-0001).
- **Orden por clave.** Las notificaciones con la misma clave del stream de trabajo (ADR-0003) las procesa un solo worker a la vez. No hay orden global: dos notificaciones de un mismo viaje con claves distintas pueden procesarse en paralelo y el orden final lo resuelve la versión del viaje (ADR-0004).
- **Reconciliación.** Al restablecerse la API de un proveedor, el adaptador consulta lo pendiente o el estado actual para cubrir lo que el proveedor no entregó (ADR-0003, BR-3).

### 3.3 Cómo funciona

Qué hace un worker con cada trabajo:

1. Toma un trabajo del stream de trabajo (ADR-0003).
2. Usa el adaptador del proveedor para consultar los detalles de la reserva o leer el correo (R-2).
3. Traduce al modelo canónico.
4. **En una sola transacción**, guarda el cambio en la base y escribe el evento en el outbox (decisión B).

| Qué falla | Qué pasa | Resultado |
|---|---|---|
| Un proveedor responde lento o falla. | Su circuit breaker se abre. Las colas y pools de los demás proveedores siguen operando. | Se degrada ese proveedor, no la ingesta completa. |
| Llega una ráfaga de notificaciones. | El receptor encola y responde. Los workers consumen a su ritmo y se agregan instancias si hace falta. | El proveedor recibe respuesta rápida y no hay pérdida. |
| La API de un proveedor se restablece tras una caída. | El adaptador reconcilia: consulta lo pendiente o el estado actual. | Se recupera lo que el proveedor no entregó. |

### 3.4 Por qué no las otras alternativas

- **A1 (procesar en el receptor).** Es la más simple, pero incumple QA-1, QA-6 y QA-8. El proveedor espera mientras se consulta a su propio sistema y se interpreta el correo. Ante una ráfaga el receptor se satura y el proveedor reintenta o descarta. Un proveedor lento bloquea la recepción de todos los demás, y la recepción y el procesamiento escalan juntos aunque tengan necesidades distintas.
- **A3 (un servicio por proveedor).** Da el mejor aislamiento (QA-8), pero incumple R-6: exige varios desplegables con contratos propios. El ADR-0001 (3.4) ya difirió esa separación porque agrega costo de integración sin mejorar un QA que condicione la decisión. Queda como evolución: como los adaptadores ya son módulos con puertos, separarlos después no es una reescritura.
- **A4 (serverless por evento).** Incumple R-6 por las razones del ADR-0001 (3.4): acoplamiento a un proveedor cloud, riesgo de costos y dificultad para ejecutar y demostrar el prototipo localmente.

---

## 4. Decisión B: outbox transaccional

### 4.1 Alternativas consideradas

| ID | Alternativa | Resumen |
|---|---|---|
| B1 | Doble escritura directa | El worker guarda en la base y después publica en el broker, sin garantía conjunta. |
| B2 | Publicar primero, guardar después | El worker publica el evento y después guarda el cambio. |
| **B3** | **Outbox transaccional con relay por polling** | El cambio y el evento se guardan en la misma transacción. Un relay lee los eventos pendientes y los publica. |
| B4 | Outbox con captura de cambios (CDC) | Un conector lee el log de transacciones de la base y publica los eventos del outbox. |
| B5 | Transacción distribuida (2PC / XA) | La base y el broker participan de una misma transacción. |

### 4.2 Decisión

**Se elige B3: los workers escriben el cambio y el evento en la misma transacción de la base (tabla outbox) y un relay publica los eventos pendientes en el stream de eventos del broker (ADR-0003).**

**Descripción de la decisión.** El outbox convierte "guardar y avisar" en una sola operación atómica: se guarda el dato y una nota de aviso juntos, y un proceso aparte entrega la nota al broker.

- **Contenido de la fila.** Identificador de evento, tipo, clave de orden (el viaje), versión del viaje tras el cambio, secuencia de creación (el orden en que el relay las lee) y un payload mínimo con referencias.
- **Relay por polling.** Corre dentro del artefacto de ingesta, lee cada pocos segundos las filas pendientes en orden de creación, las publica y las marca como publicadas cuando el broker confirma. Con varias instancias usa un bloqueo de filas para no tomar las mismas.
- **Entrega al menos una vez.** El outbox garantiza entrega al menos una vez, nunca cero. Evitar el efecto de un duplicado es trabajo de los consumidores.
- **Quién escribe el outbox.** Solo los workers. Los cambios manuales del viajero los guarda el Panel API sin pasar por el outbox (ADR-0004).

### 4.3 Cómo funciona

El worker guarda el cambio y la fila de evento dentro de la misma transacción: se guardan las dos o ninguna. Después del `COMMIT`, confirma el trabajo en el stream. El relay publica el evento y el Panel API lo consume y mantiene el read model de modo que los duplicados y los eventos viejos no tengan efecto: relee el estado actual y escribe solo si la versión es mayor o igual a la guardada (ADR-0004).

| Qué falla | Qué pasa | Resultado |
|---|---|---|
| El worker cae antes del `COMMIT`. | No hay cambio ni evento. Se reentrega el trabajo. | Sin efecto. |
| El worker cae después del `COMMIT`. | Al reentregarse, el guardado detecta que la notificación ya se aplicó y no duplica. | Sin duplicados. |
| El broker o el relay se caen. | Las filas quedan pendientes y se publican en orden al volver. | El panel se actualiza con demora. En el peor caso un evento sale dos veces y el consumidor lo ignora. |

**Operación.** Las filas publicadas se conservan una ventana corta y la tabla se particiona por tiempo para descartar particiones viejas sin borrados masivos. Se monitorea la antigüedad de la fila pendiente más vieja.

### 4.4 Por qué no las otras alternativas

- **B1 (doble escritura).** Produce el problema del contexto: si falla a mitad de camino, la base y el panel quedan inconsistentes y nadie lo detecta. Incumple QA-1 y QA-5.
- **B2 (publicar primero).** Es peor que B1 frente a QA-5: el panel puede mostrar una reserva que nunca llegó a guardarse y, si la base falla después de publicar, el evento ya salió y no se puede retirar.
- **B4 (CDC).** Evita el polling y las escrituras extra, pero incumple R-6 hoy: suma un componente y depende de una configuración específica del motor de base, que todavía no está decidido. Queda como evolución natural: el relay ya publica por un puerto, así que cambiar de polling a CDC no toca a los workers.
- **B5 (transacción distribuida).** Daría atomicidad real, pero los brokers candidatos no participan en transacciones distribuidas, y además acoplaría la disponibilidad de la base con la del broker (QA-5).

---

## 5. Consecuencias

### Positivas

- La ingesta absorbe ráfagas y límites de los proveedores sin que el receptor se bloquee (QA-1, QA-6, QA-7).
- Ningún cambio se guarda sin su aviso ni se avisa sin guardarse: el panel recibe cada evento al menos una vez (QA-1, QA-5).
- Si el broker se cae, lo ya guardado en la base no se pierde y el relay se pone al día al volver. Los workers dejan de recibir trabajo nuevo y la recepción de notificaciones nuevas se interrumpe (ADR-0003, BR-4).
- Los adaptadores por proveedor son módulos con puertos: sumar un transporte no toca el núcleo (R-2, QA-12).
- Se implementa con una tabla y un proceso, sin depender de un motor de base ni de un broker concretos (R-6).

### Negativas y riesgos aceptados

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| W-1 | Un adaptador defectuoso afecta a los workers. | Puede interrumpir la integración con otros proveedores (QA-8). | Aislamiento por proveedor dentro del worker: colas, pools y circuit breaker. |
| W-2 | Workers y Panel API comparten la base. | Cuello de botella de escritura (QA-6) y acoplamiento por esquema. | Cada módulo es dueño de sus tablas. Read model separado para las lecturas (ADR-0004). Medir con prueba de carga. |
| W-3 | El relay agrega latencia y puede detenerse. | El polling suma hasta un intervalo de demora y consume parte de los 5 minutos de QA-1. Si el relay se detiene, los eventos se acumulan y el panel deja de actualizarse sin que falle otro componente. | Intervalo corto y presupuesto de tiempo por etapa (ADR-0003, BR-1). Alerta por antigüedad de la fila pendiente más vieja. Reinicio automático. |
