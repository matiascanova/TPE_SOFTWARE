# ADR-0009: Tecnología del broker, retención y relay

- **Estado:** Propuesto
- **Fecha:** 24/10/2026
- **Decisores:** Equipo de diseño (grupo TPE)
- **Drivers de entrada:** Análisis inicial y drivers e Iteración 1: CU-01, CU-02 y CU-03. Otros drivers: QA-1, QA-5, QA-6, R-4, R-6, CRN-2 y CRN-N3.
- **ADR relacionados:** ADR-0002 (workers y outbox), ADR-0003 (broker), ADR-0004 (read model), ADR-0007 (contratos de mensajes y semántica de entrega), ADR-0008 (resiliencia de la ingesta por proveedor)

---

## 1. Contexto y problema

El ADR-0003 eligió un broker con log persistente, pero dejó abiertas la tecnología (Redis Streams o RabbitMQ Streams) y la retención. También pidió revisar si el replay justifica un log frente a una cola (BR-5). El ADR-0002 fijó un relay que lee el outbox «cada pocos segundos», sin un valor. Este documento va último en la iteración porque implementa la estructura de streams del ADR-0008 y toma su retención de la ventana de reconciliación de ese ADR.

Este documento responde tres preguntas:

**Decisión A:** **¿Qué tecnología implementa el broker?**

**Decisión B:** **¿Cuánto tiempo retiene cada stream sus mensajes?**

**Decisión C:** **¿Con qué intervalo lee el relay el outbox?**

**Tensiones:**

- **Conocimiento frente a capacidad.** Redis ya está en el sistema (ADR-0004), pero guarda en memoria. RabbitMQ Streams guarda en disco, pero es nuevo para el grupo (CRN-2, R-6).
- **Retención frente a datos personales y costo.** Retener más permite absorber interrupciones largas (QA-5), pero expone más datos personales (R-4) y cuesta más memoria con 15 M de cuentas (QA-6).
- **Durabilidad frente a latencia.** El receptor responde al proveedor cuando el broker confirma (ADR-0007). Si el broker confirma algo que no persistió, una caída lo pierde. Persistir antes de confirmar suma demora (QA-1).

**Fuera de alcance:** estructura lógica de streams, reintentos y reconciliación (ADR-0008); campos de los mensajes (ADR-0007); alta disponibilidad y monitoreo en producción (iteración 3, CRN-N3); pipeline de correo. Kafka, descartado en el ADR-0003 (CO5), no se reabre. Los valores con \* son propuestas para el prototipo.

---

## 2. Criterios de decisión

| Criterio | Qué mide en esta decisión | Se usa en |
| --- | --- | --- |
| QA-1 | Cuánto de los 5 minutos consumen el broker y el relay. | A / C |
| QA-5 | Si un mensaje confirmado sobrevive a una caída y si la retención cubre la interrupción que se quiere absorber. | A / B |
| QA-6 | Si la retención sostiene el volumen de 15 M de cuentas. | A / B |
| R-4 | Si los datos personales se retienen solo el tiempo necesario. | B |
| R-6 y CRN-2 | Si el grupo conoce la tecnología y la puede demostrar en el plazo. | A / C |
| CRN-N3 | Tecnologías distintas para operar, y si la tecnología da lo que necesita el ADR-0008. | A |

---

## 3. Decisión A: tecnología del broker

### 3.1 Alternativas consideradas

| ID | Alternativa | Resumen |
| --- | --- | --- |
| T1 | **Redis Streams en una instancia dedicada** | Streams de Redis con grupos de consumo, en una instancia separada del read model. |
| T2 | Redis Streams en la instancia del read model | Los streams comparten instancia con el read model (ADR-0004). |
| T3 | RabbitMQ Streams | Streams con retención en disco, réplicas y particiones con un consumidor activo por partición. |
| T4 | Cola durable (RabbitMQ *quorum*) | Se abandona el log: cada mensaje se borra al confirmarse. |

### 3.2 Decisión

**Se elige T1: Redis Streams en una instancia dedicada, separada del read model, que no expulsa datos y que persiste cada mensaje antes de confirmarlo.**

**Descripción de la decisión.** Redis Streams es un log con grupos de consumo. Por cada mensaje entregado y no confirmado guarda quién lo tiene y cuántas veces se entregó, que es el contador que el ADR-0008 necesita para agotar un mensaje aunque el worker se caiga. Implementa el conector *Publish/Subscribe* del ADR-0003 con las tácticas *Redundant Spare* (réplica en producción), *Bound Queue Sizes*, *Heartbeat* y *Encapsulate*.

- **Instancia dedicada sin expulsión.** El read model borra entradas cuando se llena (ADR-0004). El broker usa `noeviction`: si se llena, la escritura falla y el proveedor reintenta, sin pérdida silenciosa.
- **Confirmar solo lo persistido.** El broker escribe en disco antes de responder (`appendfsync always`). En producción, además, espera la confirmación de una réplica (`WAIT`).
- **Mapeo del ADR-0008.** Cada partición es un stream (`trabajo:<proveedor>:<n>`), y se suman `eventos`, `apartados:trabajo` y `apartados:eventos`. Un arriendo con vencimiento asegura un consumidor por partición. Si el worker cae, otro toma la partición y reclama sus pendientes (`XAUTOCLAIM`).
- **Detrás del puerto.** Solo el adaptador de mensajería conoce Redis (ADR-0003). Se usa Redis 6.2 o posterior, y en el prototipo es un contenedor más en docker-compose.

### 3.3 Por qué no las otras alternativas

- **T2.** La expulsión del read model puede borrar streams con mensajes sin confirmar (QA-5), y un atraso del broker le quitaría memoria al panel (CU-01).
- **T3.** Retiene mejor en disco, pero es nueva para el grupo y suma una segunda tecnología para operar (R-6, CRN-2, CRN-N3). Además no cuenta entregas, así que el contador del ADR-0008 habría que llevarlo aparte. Queda como evolución detrás del puerto si la memoria no alcanza (BK-1).
- **T4.** Elimina el replay, que el diseño usa para reprocesar lotes (ADR-0007), releer eventos (ADR-0004) y retener el trabajo de un proveedor pausado (ADR-0008). Así se cierra BR-5: el log se mantiene.

---

## 4. Decisión B: retención por tiempo y por stream

### 4.1 Alternativas consideradas

| ID | Alternativa | Resumen |
| --- | --- | --- |
| R1 | Por cantidad, igual para todos | Cada stream conserva sus últimos N mensajes. |
| R2 | **Por tiempo, distinta por stream** | Cada stream retiene lo que necesita su uso. |
| R3 | Larga uniforme | Todos los streams retienen 7 días. |

### 4.2 Decisión

**Se elige R2: cada stream retiene por tiempo, con un plazo propio. El trabajo de proveedores retiene al menos la ventana de reconciliación del ADR-0008.**

**Descripción de la decisión.** El log no es la fuente de verdad (ADR-0003). Pasado el plazo, la recuperación es la reconciliación por estado actual (ADR-0008) o la reconstrucción del read model desde la BD (ADR-0004). Una tarea recorta cada minuto los mensajes vencidos (`XTRIM` con `MINID`).

| Stream | Retención | Por qué |
| --- | --- | --- |
| Trabajo de proveedores | 48 h\* | La ventana de 24 h del ADR-0008 más un margen para drenar y reprocesar. |
| Trabajo de correo | 24 h\* (provisional) | El correo permite recuperar cambios desde una marca de la casilla. Lo revisa el ADR del pipeline. |
| Eventos | 24 h\* | Después, el read model se reconstruye desde la BD. |
| Apartados | 7 días\* | Tiempo para corregir el adaptador y reprocesar. |

- **La retención sigue a la ventana.** Cambiar la ventana del ADR-0008 obliga a revisar la retención del trabajo.
- **Alertas.** Salta una alerta al 70% de la memoria\* y otra cuando un mensaje pendiente se acerca a su recorte.

### 4.3 Por qué no las otras alternativas

- **R1.** En un pico ×10 la misma cantidad cubre diez veces menos tiempo, justo durante las disrupciones (QA-5, QA-6).
- **R3.** Multiplica por 3,5 la memoria del trabajo (QA-6) y alarga la exposición de datos personales (R-4) sin beneficio para los eventos.

---

## 5. Decisión C: intervalo del relay

### 5.1 Alternativas consideradas

| ID | Alternativa | Resumen |
| --- | --- | --- |
| P1 | Cada 5 s | Lectura a intervalo fijo. |
| P2 | **Cada 1 s, con relectura inmediata si el lote vino lleno** | Espera solo si la última lectura trajo menos de un lote. |
| P3 | Aviso de PostgreSQL | `LISTEN/NOTIFY` en cada escritura al outbox, más una lectura de respaldo. |
| P4 | Captura de cambios (CDC) | Un conector lee el registro de transacciones. |

### 5.2 Decisión

**Se elige P2: el relay lee lotes de hasta 500 filas\*, espera 1 s\* si el lote no vino lleno y vuelve a leer en el acto si vino lleno.**

**Descripción de la decisión.** En operación normal suma como máximo 1 s, y ante un atraso lee lotes seguidos hasta ponerse al día (táctica *Manage Work Requests*). Usa `FOR UPDATE SKIP LOCKED` para que varias instancias no tomen las mismas filas (ADR-0002). El intervalo se elige contra este reparto de los 5 minutos de QA-1 (ADR-0003, BR-1):

| Etapa | Presupuesto\* |
| --- | --- |
| Receptor | 1 s |
| Espera y procesamiento en el worker, con reintentos | 60 s |
| Outbox y relay | 2 s |
| Panel API y read model | 5 s |
| Margen para picos | unos 230 s |

### 5.3 Por qué no las otras alternativas

- **P1.** Suma hasta 5 s sin ahorro real, y con lotes de 500 publica como máximo 100 eventos por segundo, menos que un pico ×10 (QA-6).
- **P3.** En PostgreSQL, `NOTIFY` toma un bloqueo global al confirmar. Sobre el outbox, la tabla más escrita (ADR-0002, W-3), serializa la ingesta.
- **P4.** Suma un conector y configuración de replicación sin mejorar QA-1 frente a 1 s (R-6). Queda como la evolución que prevé el ADR-0002.

---

## 6. Consecuencias

### Positivas

- Una sola tecnología para el read model y el broker, que el grupo ya usa (R-6, CRN-2).
- El contador de entregas y la reclamación de pendientes son nativos y sostienen el ADR-0008.
- Lo que se confirma al proveedor está persistido (QA-5), y cada stream retiene solo lo necesario (R-4).
- El relay suma como máximo 1 s y el presupuesto por etapa permite ubicar demoras (QA-1).

### Negativas y riesgos aceptados

| ID | Riesgo | Efecto | Mitigación |
| --- | --- | --- | --- |
| BK-1 | La retención vive en memoria y Redis recorta aunque haya mensajes pendientes. | El correo a escala puede no entrar en una instancia (QA-6), y una interrupción más larga que la retención pierde mensajes retenidos (QA-5). | Retención por stream y alertas. Reconciliación por estado actual (ADR-0008). Evolución a RabbitMQ Streams. |
| BK-2 | Persistir antes de confirmar reduce el rendimiento, y la réplica es asíncrona. | Más demora en el receptor (QA-1), y un failover puede perder unos pocos mensajes (QA-5). | Medir en la prueba de carga. Esperar la réplica. La reconciliación cubre lo perdido. |
| BK-3 | Los arriendos por partición son código propio, y hay dos instancias de Redis con configuraciones opuestas. | Dos consumidores en una partición rompen el orden (ADR-0002), y confundir las instancias provoca pérdidas (CRN-N3). | La idempotencia del ADR-0007 protege el resultado. Pruebas de caída de workers. Chequeo de la configuración al arrancar. |
