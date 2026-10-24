# ADR-0012: Detección del correo nuevo, planificación del sondeo y capacidad

- **Estado:** Propuesto
- **Decisores:** Equipo de diseño (grupo TPE)
- **Drivers de entrada:** Análisis inicial y drivers, Iteración 1 e Iteración 2. CU-03. Otros drivers: QA-5, QA-6, QA-7, R-3, R-6, CRN-N3 y CRN-N6.
- **ADR relacionados:** ADR-0002 (workers y outbox), ADR-0003 (broker), ADR-0005 (motor de la BD), ADR-0007 (contratos de mensajes y semántica de entrega), ADR-0008 (resiliencia de la ingesta por proveedor), ADR-0009 (tecnología del broker, retención y relay), ADR-0010 (credenciales de correo y revocación de acceso), ADR-0011 (filtrado de remitentes y extracción de reservas).

---

## 1. Contexto y problema

La Iteración 1 incorporó un planificador de sondeo que programa la lectura periódica del correo y encola el trabajo, y dejó su diseño pendiente. Los ADR posteriores fijaron lo que lo rodea: el trabajo de sondeo identifica la cuenta de correo con la clave de orden `correo:<id de la cuenta>` y no lleva contenido ni tokens (ADR-0007), cada proveedor de correo tiene sus propias particiones, recursos y circuit breaker (ADR-0008), la lectura se corta al revocar el acceso (ADR-0010), y cada correo de la *whitelist* se procesa en menos de 15 s\* (ADR-0011). El ADR-0009 dejó provisional la retención del trabajo de correo (24 h\*) y anotó que el correo permite recuperar cambios desde una marca de la casilla. Falta decidir cómo se entera el sistema de que hay correo nuevo, a qué cuentas atiende primero y cuánta capacidad necesita.

QA-7 exige que, con una base de 15 M de cuentas, el sistema procese el correo de los usuarios con viajes próximos con una demora máxima de 15 minutos\* para el 95%\* de ellos, incorporando recursos de procesamiento.

Este documento responde dos preguntas:

1. **¿Cómo se detecta que hay correo nuevo en la casilla de una cuenta?**
2. **¿Cómo se decide qué cuentas se atienden primero, con qué frecuencia y con cuánta capacidad?**

Se deciden juntas porque la vía de detección fija cuántas llamadas al proveedor de correo hay que planificar, y esa planificación es la que sostiene QA-7 con 15 M de cuentas.

**Tensiones:**

- **Frescura frente a cuotas (QA-7, R-3).** Sondear más seguido acerca el correo al límite de 15 minutos\*, pero consume la cuota de uso que cada proveedor de correo impone y que el sistema no controla.
- **Tratar igual a todas las cuentas frente a atender a las que importan (QA-7, QA-6).** QA-7 se mide sobre usuarios con viajes próximos, pero el total de cuentas llega a 15 M. Sondear todas con el intervalo que exige QA-7 sería, como cota superior teórica, 25.000 sondeos por segundo (15 M de cuentas cada 10 minutos\*), y no un volumen estimado.
- **Memoria frente a margen de recuperación (CRN-N6, QA-5).** El log vive en memoria (ADR-0009). Retener el trabajo de correo mucho tiempo cuesta memoria y retiene datos de más. Retenerlo poco acorta el margen para absorber una caída del proveedor.
- **Código propio frente a plazo (R-6, CRN-N3).** Suscripciones de notificación, planificador y estado por cuenta son piezas que hay que construir, monitorear y mantener.

**Fuera de alcance:**

- Filtrado de remitentes y extracción de reservas (ADR-0011), y credenciales y revocación (ADR-0010).
- Despliegue, alta disponibilidad del planificador, autoescalado en la plataforma y tableros de monitoreo (ADR de capacidad y despliegue de la Iteración 5).
- Partición de la base y réplica de lectura (Iteración 5).
- Flujo de conexión de la cuenta y autorización del usuario (Panel API, Iteración 4).
- Importación de correos anteriores a la conexión. <PENDIENTE: definir si al conectar la cuenta se importan correos previos y con qué ventana.>

Los valores con \* son propuestas para el prototipo y se ajustan con proveedores simulados que apliquen cuotas.

---

## 2. Criterios de decisión

| Criterio | Qué mide en esta decisión | Se usa en |
|---|---|---|
| QA-7 | Que el correo de usuarios con viajes próximos se procese en menos de 15 minutos\* para el 95%\* de ellos. | A / B |
| QA-6 | Que el costo de planificación no crezca con el total de 15 M de cuentas sino con las que importan y con el correo que llega. | A / B |
| QA-5 | Que lo recibido durante una interrupción del proveedor de correo se recupere al restablecerse, sin perder correos. | A / B |
| R-3 | Que se respeten los límites de uso y que funcione con proveedores sin notificaciones o con suscripciones que vencen. | A / B |
| R-6 | Esfuerzo de implementación para el grupo y piezas nuevas que operar. | A / B |
| CRN-N3 | Que las piezas nuevas se puedan monitorear con alertas simples. | A / B |
| CRN-N6 | Que el trabajo de correo ocupe poca memoria del log y no retenga datos personales de más. | B |

---

## 3. Decisión A: detección del correo nuevo

### 3.1 Alternativas consideradas

| ID | Alternativa | Resumen |
|---|---|---|
| A1 | Solo sondeo periódico | El sistema consulta cada casilla a intervalos, sin notificaciones del proveedor. |
| A2 | Solo notificaciones push | El proveedor de correo avisa cuando hay correo nuevo y el sistema no consulta por su cuenta. |
| A3 | **Notificaciones push como vía principal, sondeo de respaldo y marca de sincronización incremental** | El aviso del proveedor adelanta el procesamiento. El sondeo por prioridad cubre lo que el aviso no entrega. Cada trabajo pide solo los cambios posteriores a una marca guardada por cuenta. |
| A4 | Push y sondeo con relectura de una ventana reciente | Igual que A3, pero cada trabajo relee los correos de una ventana reciente en lugar de usar una marca. |

### 3.2 Decisión

**Se elige A3: el aviso del proveedor adelanta el procesamiento, un sondeo de respaldo siempre activo cubre lo que el aviso no entrega, y cada trabajo pide solo los cambios posteriores a la marca de sincronización de la cuenta.**

**Descripción de la decisión.** La marca de sincronización es el punto de la casilla hasta donde el sistema ya procesó, que el proveedor de correo entrega y acepta como referencia para pedir solo lo nuevo. El aviso llega por el camino que ya prevé la Iteración 1 (proveedores de correo, receptor, broker, workers) y el sondeo lo origina el planificador. Los dos producen el mismo trabajo `correo:<id de la cuenta>` (ADR-0007), así que el worker no distingue de dónde vino. Aplica las tácticas de disponibilidad *Heartbeat* (el sondeo de respaldo detecta lo que el aviso no entregó) y *State Resynchronization* (la marca recupera lo ocurrido durante una caída).

- **Suscripción por cuenta.** Una tarea del planificador crea una suscripción de notificación para cada cuenta activa si el proveedor las ofrece, la renueva antes de que venza y la cancela al revocar el acceso (ADR-0010). La suscripción se crea con un identificador que le permite al receptor obtener el id de la cuenta sin interpretar el aviso.
- **El aviso no lleva contenido.** El receptor no interpreta el aviso: obtiene el id de la cuenta a partir del identificador de la suscripción, encola el trabajo correo:<id de la cuenta> con el sobre del ADR-0007 y responde cuando el broker confirma. No se agrega contenido de correo al log.
- **Marca incremental por cuenta.** Cada trabajo pide al proveedor los cambios posteriores a la marca. La marca avanza solo después de procesar y guardar (confirmación tardía, ADR-0007). Si el proveedor la invalida, se vuelve a listar la casilla filtrando por los remitentes de la *whitelist* desde la hora del último sondeo exitoso.
- **Respaldo siempre activo.** Toda cuenta activa se sondea según su clase (Decisión B) aunque tenga suscripción. El aviso adelanta el sondeo, no lo reemplaza.
- **Cuenta sin aviso.** Si el proveedor no ofrece notificaciones, o la suscripción falló o venció, la cuenta se atiende solo con el sondeo y se marca para el monitoreo.

### 3.4 Por qué no las otras alternativas

- **A1. Solo sondeo.** El costo crece con el total de cuentas y no con el correo que llega: con 15 M de cuentas y un intervalo de 10 minutos\* serían 25.000 sondeos por segundo como cota superior, contra cuotas que no se controlan (QA-6, R-3). Además, cada correo espera en promedio medio intervalo, lo que consume el presupuesto de QA-7 sin necesidad.
- **A2. Solo push.** Un aviso perdido, o una suscripción vencida sin renovar, retrasa el correo sin que nadie lo note, y el sistema no tiene cómo recuperarlo (QA-5, QA-7). Además depende de que el proveedor ofrezca notificaciones, y R-3 dice que su disponibilidad y sus capacidades no se controlan.
- **A4. Relectura de una ventana.** Repite lecturas de correos ya vistos, que gastan cuota y vuelven a pasar por la extracción del ADR-0011 (R-3, QA-6). Una interrupción más larga que la ventana pierde correos sin que nada lo detecte, a diferencia de la marca (QA-5).

---

## 4. Decisión B: planificación, prioridad y capacidad

### 4.1 Alternativas consideradas

| ID | Alternativa | Resumen |
|---|---|---|
| B1 | Sondeo uniforme | El planificador atiende todas las cuentas con el mismo intervalo y en el mismo orden. |
| B2 | **Planificador por clases, con un trabajo por cuenta y emisión limitada por el atraso** | Las cuentas se agrupan en clases según la cercanía de sus viajes. El planificador emite primero las más urgentes y se frena cuando el consumo se atrasa. |
| B3 | Streams separados por prioridad | Cada clase tiene sus propios streams y sus propios workers. |
| B4 | Escalar workers sin priorizar | Se agregan workers hasta cubrir el sondeo de todas las cuentas. |

### 4.2 Decisión

**Se elige B2: el planificador agrupa las cuentas en clases según la cercanía de los viajes del usuario, emite primero las más urgentes, mantiene un solo trabajo pendiente por cuenta y deja de emitir cuando el consumo de un proveedor se atrasa.**

**Descripción de la decisión.** Cada cuenta activa tiene una fila en una tabla propia de la BD operacional (ADR-0005) con su marca de sincronización, su clase, la hora del próximo sondeo y la hora en que se encoló su trabajo en curso. El planificador lee las cuentas vencidas ordenadas por clase, con el mismo mecanismo de lotes y bloqueo de filas que el relay (ADR-0009), y encola un trabajo por cuenta. Aplica la táctica de performance *Prioritize Events* y las de *Bound Queue Sizes* y *Manage Work Requests*, ya usadas en los ADR-0009.

- **Clases por cercanía del viaje.** La clase 1 reúne las cuentas de usuarios con un viaje dentro de la ventana de viaje próximo, o en curso. La clase 2 es el resto de las cuentas activas. La clase se recalcula al terminar cada trabajo, con una consulta indexada a los viajes del usuario. La ventana de viaje próximo es mayor que el intervalo de la clase 2, para que ningún viaje pase a próximo sin que su cuenta haya sido sondeada antes.
- **Un trabajo pendiente por cuenta.** El planificador no encola una cuenta que ya tiene un trabajo encolado, salvo que haya pasado el doble de la demora máxima de QA-7 (30 minutos\*), en cuyo caso lo considera perdido y lo reemplaza. Los avisos del proveedor entran por el receptor sin consultar este estado, así que pueden duplicar trabajos. El primero que se procesa lee todo lo nuevo y los demás no encuentran cambios.
- **Emisión limitada por el atraso.** El planificador emite por proveedor hasta la capacidad de su limitador de tasa (ADR-0008) y deja de emitir cuando el trabajo pendiente más antiguo de ese proveedor supera el umbral de atraso. Así las cuentas de la clase 1 no esperan detrás de una oleada de cuentas de la clase 2.
- **Cuotas y degradación.** Si un proveedor limita el uso o su circuito se abre (ADR-0008), el planificador deja de emitir para él. Al recuperarse, emite primero lo vencido de la clase 1. La clase 2 se demora antes, y ninguna cuenta se descarta: la marca conserva lo pendiente.
- **Retención y capacidad.** El trabajo de correo se retiene 1 hora\* y no 24 horas\*, porque un trabajo recortado se regenera desde el estado de la cuenta y la marca conserva lo no procesado. La cantidad de workers se dimensiona con la tasa de trabajos, el tiempo medio por trabajo y la concurrencia permitida por el proveedor, y se ajusta por el atraso de consumo. Esto reemplaza el valor provisional del ADR-0009.

| Parámetro | Valor propuesto |
|---|---|
| Ventana de viaje próximo | 7 días\* |
| Intervalo de respaldo, clase 1 | 10 minutos\* |
| Intervalo de respaldo, clase 2 | 6 horas\* |
| Reemplazo de un trabajo que se considera perdido | 30 minutos\* |
| Umbral de atraso para frenar la emisión | 2 minutos\* |
| Retención del trabajo de correo | 1 hora\* |

Con estos valores, el presupuesto de QA-7 (15 minutos\*) se reparte así: hasta 10 minutos\* de espera del próximo sondeo, si el aviso del proveedor no llegó, y hasta 5 minutos\* de espera en el log y procesamiento, con sus reintentos (ADR-0008).

### 4.3 Cómo funciona

El usuario de la cuenta 789 tiene un vuelo en 3 días, así que su cuenta es de la clase 1.

1. Llega un correo de confirmación a la casilla. El proveedor avisa al receptor, que encola `correo:789` sin interpretar el aviso.
2. Un worker toma el trabajo, verifica que la cuenta esté activa (ADR-0010) y pide al proveedor los cambios posteriores a la marca.
3. Los correos de remitentes de la *whitelist* pasan por la extracción (ADR-0011) y el worker guarda los cambios y los eventos (ADR-0007).
4. El worker avanza la marca, recalcula la clase, fija la hora del próximo sondeo y confirma el trabajo.
5. Si el aviso no hubiera llegado, el planificador habría encolado la cuenta como máximo 10 minutos\* después de su último sondeo.

| Qué falla | Qué pasa | Resultado |
|---|---|---|
| El aviso del proveedor se pierde o la suscripción venció. | El sondeo de respaldo encola la cuenta en su próximo vencimiento. | El correo se procesa dentro de los 15 minutos\*. La cuenta queda marcada para el monitoreo. |
| Llegan varios avisos seguidos de la misma casilla. | Se encolan varios trabajos. El primero procesa todo lo nuevo. | Los demás no encuentran cambios y terminan sin efecto. |
| El worker se cae después de guardar y antes de avanzar la marca. | Se reentrega el trabajo. La extracción es determinista (ADR-0011). | Mismo resultado, sin cambios ni eventos duplicados. |
| La API del proveedor de correo cae. | Se abre el circuito (ADR-0008) y el planificador deja de emitir para ese proveedor. | Al cerrarse, se emite primero lo vencido de la clase 1 y la marca recupera lo llegado durante la caída (QA-5). |
| El proveedor limita el uso. | Se respeta la espera que indica (ADR-0008) y baja el ritmo de emisión. | La clase 2 se demora antes que la clase 1. No se descartan cuentas. |
| El proveedor invalida la marca. | Se vuelve a listar la casilla filtrando por los remitentes de la lista desde el último sondeo exitoso. | No se pierden correos. El costo extra queda acotado a esa cuenta. |
| Un trabajo encolado no se consume (proveedor atrasado o trabajo perdido). | Pasados 30 minutos* desde que se encoló, el planificador lo considera perdido y lo reemplaza, antes de que el recorte por retención (1 hora*) lo elimine. | Solo se demora la cuenta. |
| La cuenta se revoca. | El planificador no la encola y los trabajos pendientes se descartan (ADR-0010). | Ninguna lectura posterior. |

### 4.4 Por qué no las otras alternativas

- **B1. Sondeo uniforme.** Trata igual a una cuenta con un viaje en curso y a una que nunca tuvo uno, así que el cumplimiento de QA-7 depende de que la cuota alcance para todas (R-3). Con 15 M de cuentas, esa cuota crece con el total y no con las que importan (QA-6).
- **B3. Streams separados por prioridad.** Multiplica los streams, los consumidores y los lugares donde se retiene memoria (R-6, CRN-N3, CRN-N6). Con la emisión limitada por el atraso, la clase 1 no espera detrás de la clase 2, así que no hace falta. Queda como evolución si el atraso persiste, porque los streams ya se dividen por proveedor y partición (ADR-0008).
- **B4. Solo escalar workers.** QA-7 permite agregar recursos de procesamiento, pero el límite del sondeo lo pone la cuota del proveedor y no los workers (R-3), y el costo seguiría creciendo con el total de cuentas (QA-6). Agregar workers sigue siendo parte de B2, para el atraso que sí es de capacidad.

---

## 5. Consecuencias

### Positivas

- La carga de detección sigue al correo que llega y no al total de cuentas, porque el aviso adelanta el procesamiento y el sondeo de respaldo es frecuente solo para las cuentas con viajes próximos (QA-6, QA-7).
- El presupuesto de QA-7 queda repartido por etapa, así que una demora se puede ubicar y medir con la fecha de recepción del correo y la hora de procesamiento (QA-7).
- Tras una caída del proveedor de correo no hace falta una reconciliación aparte: la marca recupera lo que llegó y se atiende primero lo urgente (QA-5).
- El log guarda a lo sumo un trabajo por cuenta salvo duplicados de avisos, durante una hora\* y sin contenido de correo, lo que reduce la memoria y los datos retenidos respecto de la retención provisional (CRN-N6).
- La degradación ante cuotas es gradual y previsible: se demora la clase 2 y no se descartan cuentas (R-3).
- Usa PostgreSQL, el broker y los workers existentes. Las piezas nuevas son el planificador y la tarea de suscripciones (R-6).

### Negativas y riesgos aceptados

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| SC-1 | Las suscripciones de aviso dependen de la renovación y de lo que cada proveedor ofrezca. | Una renovación fallida degrada la cuenta al sondeo de respaldo, y la clase 2 tarda hasta 6 horas\* (QA-7, R-3). | El respaldo siempre activo cubre la clase 1. Alerta por renovaciones fallidas y por cuentas sin aviso (CRN-N3). |
| SC-2 | No se conocen las cuotas reales de los proveedores de correo. | Los intervalos propuestos pueden no caber en la cuota y la clase 1 podría no cumplir QA-7 (R-3, QA-6). | Limitador por proveedor (ADR-0008). Prueba con un proveedor simulado que aplique cuotas. Intervalos ajustables por configuración. |
| SC-3 | El estado de cada cuenta vive en la base compartida y se actualiza en cada sondeo. | Presión de escritura sobre la base con 15 M de filas (QA-6). | Tabla propia con un índice mínimo. Actualizaciones por lote al encolar. Medición en la prueba de carga. |
| SC-4 | La clase se recalcula al terminar cada trabajo. | Un viaje cargado a mano, o por otra vía, entra en la clase 1 hasta 6 horas\* tarde (QA-7). | La ventana de viaje próximo supera el intervalo de la clase 2. Se acepta para viajes cargados con poca anticipación. |
| SC-5 | La retención corta recorta trabajos pendientes en una oleada larga, y los avisos pueden duplicar trabajos. | Se demora una cuenta y se ocupa memoria con trabajos que no cambian nada (CRN-N6, QA-5). | El planificador reemplaza el trabajo perdido a los 30 minutos\*. La marca evita pérdida de correo. Alerta de memoria al 70% (ADR-0009). |
