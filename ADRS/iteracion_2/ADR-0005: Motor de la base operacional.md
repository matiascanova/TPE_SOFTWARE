# ADR-0005: Motor de la base operacional

- **Estado:** Aceptado
- **Decisores:** Equipo de diseño (grupo TPE)
- **Drivers de entrada:** Análisis inicial y drivers (CU-02, CU-03, CU-04; QA-1, QA-5, QA-6, QA-12; R-6; CRN-2).
- **ADR relacionados:** ADR-0001 (estilo C-lite), ADR-0002 (workers y outbox), ADR-0003 (broker), ADR-0004 (read model), ADR-0006 (modelo canónico y adaptador), ADR-0007 (contratos de mensajes y semántica de entrega), ADR-0008 (resiliencia de la ingesta por proveedor), ADR-0009 (tecnología del broker, retención y relay)

---

## 1. Contexto y problema

C-lite (ADR-0001) tiene una base compartida que guarda el **modelo de escritura**: usuarios, viajes, reservas, permisos de compartir, auditoría y la tabla de outbox. El ADR-0002 exige que el cambio de negocio y su evento se guarden en la misma transacción (outbox), y el ADR-0004 saca las lecturas del panel de esta base: el panel lee del read model. Ningún ADR define todavía qué motor implementa la base. Este documento responde una pregunta:

**¿Qué motor implementa la base operacional, y cómo guarda reservas de tipos distintos (vuelo, hotel, auto y, a futuro, tren)?**

**Tensiones:**

- **Entrega confiable de eventos.** El outbox del ADR-0002 necesita una transacción que cubra la tabla de negocio y la de outbox. Sin eso se pierden o se duplican eventos (QA-1, QA-5).
- **Variabilidad por tipo.** Cada tipo de reserva tiene atributos propios, y sumar un tipo nuevo (trenes) no debería exigir modificar el núcleo (QA-12).
- **Escala de escritura.** Con las lecturas del panel en el read model (ADR-0004), la base atiende sobre todo las escrituras de la ingesta, que en picos ×10 crecen (QA-6).
- **Viabilidad.** Plazo acotado (R-6) y conocimiento previo del equipo (CRN-2).

**Razones propuestas por el equipo.** El equipo propuso tres razones a favor de SQL. Antes de usarlas como criterio se revisó si cada una se sostiene:

| Razón | ¿Se sostiene? | Cómo se usa en este ADR |
|---|---|---|
| El equipo ya conoce SQL | Sí. Es una razón de riesgo de ejecución, no de adecuación técnica. | Criterio de viabilidad. Desempata entre alternativas que cumplen los QA. |
| La velocidad del motor | Solo como "suficiente". SQL no es intrínsecamente más rápido: el panel lee del read model y las consultas críticas son por clave indexada. | No es un criterio a favor. El límite real (un único primario de escritura) se trata como riesgo. |
| Todos los datos tienen el mismo formato | Solo para el núcleo. Los atributos por tipo y los mensajes crudos no son uniformes. Además, es un argumento sobre el esquema, no sobre el motor. | Se reformula: el núcleo es relacional y uniforme, y la variabilidad por tipo se aísla en un campo de detalle validado. |

## 2. Criterios de decisión

| Criterio | Qué mide en esta decisión |
|---|---|
| QA-1 / QA-5 | Que el cambio y su evento se guarden de forma atómica, para sostener el outbox. |
| QA-12 | Que un tipo de reserva nuevo se agregue sin migrar ni modificar el núcleo. |
| QA-6 | Que el motor sostenga el volumen de escritura estimado en picos ×10. |
| R-6 / CRN-2 | Que el equipo lo conozca y lo pueda levantar y demostrar en el plazo. |

## 3. Alternativas consideradas

| ID | Alternativa | Resumen |
|---|---|---|
| BD1 | **PostgreSQL** | Relacional con JSONB indexable y particionado nativo. |
| BD2 | MySQL / MariaDB | Relacional de la misma familia, soporte JSON más limitado. |
| BD3 | MongoDB | Base documental con esquema flexible y transacciones multi-documento. |
| BD4 | DynamoDB / Cassandra | Clave-valor o wide-column distribuido, con el modelo diseñado a partir de los patrones de acceso. |

## 4. Decisión

**Se elige BD1: PostgreSQL como motor de la base operacional, con un núcleo relacional tipado y un campo `detalle` en JSONB, validado por esquema, para los atributos propios de cada tipo de reserva.**

**Qué es.** El núcleo de la reserva (lo común a todos los tipos) vive en columnas tipadas con restricciones. Lo propio de cada tipo vive en `detalle`, cuya forma la fija el esquema de ese tipo y versión (ADR-0006). La uniformidad del núcleo no existe en el mundo externo: la crean los adaptadores al traducir al modelo canónico (ADR-0006, R-2).

### Reglas de la decisión

- **Núcleo tipado.** Viaje y campos comunes de la reserva (id, viaje, tipo, proveedor, identificador externo, estado, código de confirmación, inicio y fin, precio y moneda, versión del esquema del detalle) son columnas con restricciones. El código de confirmación, el precio y la moneda aceptan nulo, porque no todos los proveedores los informan.
- **Detalle por tipo.** Los atributos propios del tipo (número de vuelo, habitación, punto de retiro, andén) van en `detalle` (JSONB), validado contra el esquema de su tipo y versión antes de guardar.
- **Lo que se consulta, se tipa.** Un campo que se usa para filtrar o buscar sale de `detalle` y pasa a ser columna. JSONB no es un depósito sin esquema.
- **Versión por viaje y por reserva.** Sube con cada cambio. La usan el guardado idempotente de los workers (ADR-0002, ADR-0007) y la escritura condicional del read model (ADR-0004).
- **Outbox en la misma base.** La tabla de outbox vive junto a las tablas de negocio y se escribe en la misma transacción (ADR-0002).
- **Fechas.** `timestamptz` más la zona de origen del viaje.
- **Prototipo.** Una instancia en docker-compose, reproducible por todo el grupo.

### 4.1 Cómo funciona

**Caso práctico.** Siguiendo el ejemplo del ADR-0002: un worker recibe del adaptador de un hotel una reserva nueva para el viaje 123, que está en la versión 3.

1. El adaptador ya validó el `detalle` contra el esquema del tipo hotel y devolvió *Válido* con la reserva canónica (ADR-0006).
2. En una sola transacción: inserta la reserva (núcleo y `detalle`), sube el viaje 123 a la versión 4 y escribe el evento `ReservaCreada` en el outbox.
3. Confirma la transacción. Si algo falla antes, no queda ni el cambio ni el evento.
4. El relay publica el evento (ADR-0002) y el Panel API actualiza el read model (ADR-0004).

**Agregar trenes (QA-12).** Se define el esquema del `detalle` de tren y su adaptador. El núcleo y las tablas no cambian: no hay migración.

**Qué pasa cuando algo falla:**

| Qué falla | Qué pasa | Resultado |
|---|---|---|
| El worker se cae entre guardar y publicar. | El evento quedó en el outbox, en la misma transacción que el cambio. | El relay lo publica después. No se pierde. |
| Llega la misma reserva dos veces. | Restricción única por proveedor e identificador externo, y comparación con el estado guardado (ADR-0007). | Sin efecto. |
| El `detalle` no cumple el esquema del tipo. | El adaptador devuelve *Inválido* y no se guarda nada (ADR-0006). | No entra basura a la base. El mensaje se aparta y se registra con su motivo (QA-8, ADR-0008). |
| La base está en failover. | Las escrituras fallan unos segundos a minutos. El worker pausa el consumo sin apartar mensajes y el trabajo espera en el broker (ADR-0008). | Se demora la frescura. El panel sigue sirviendo el read model (ADR-0004). |

### 4.2 Por qué no las otras alternativas

- **BD2 (MySQL / MariaDB).** Cumple casi todo igual y también es conocido por el equipo. Pierde en lo que este diseño usa: indexación JSON menos directa y particionado menos completo. Si la experiencia del equipo fuera principalmente en MySQL, sería una elección razonable y el resto de la decisión (núcleo tipado más detalle JSON) no cambia.
- **BD3 (MongoDB).** Se ajusta bien a la variabilidad por tipo (QA-12), pero el núcleo es relacional y uniforme, así que esa flexibilidad se usaría poco. Las garantías transaccionales para el outbox son más débiles y el equipo no tiene experiencia (R-6).
- **BD4 (DynamoDB / Cassandra).** Es la mejor en escala de escritura, pero tiene transacciones limitadas, lo que complica el outbox y la gestión manual, y exige diseñar el modelo según los patrones de acceso. Es difícil de reproducir localmente y acopla a un proveedor cloud. Su ventaja no es necesaria con el volumen estimado (R-6).

## 5. Consecuencias

### Positivas

- Outbox transaccional simple y verificable, que sostiene la entrega confiable de eventos (QA-1, QA-5).
- Tipos de reserva nuevos sin migración ni cambios en el núcleo, gracias al campo `detalle` (QA-12).
- Integridad referencial entre usuario, viaje, reserva y permisos.
- Menor riesgo de ejecución: el equipo trabaja con una tecnología que domina (R-6, CRN-2).
- Particionado nativo disponible para cuando se aborde la capacidad (CRN-N2) y la residencia por región (R-4).

### Negativas y riesgos aceptados

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| BD-1 | Un único primario de escritura | Techo de escritura en picos ×10 (QA-6) y punto único de falla (QA-4). | Índices por clave, relay con lotes (ADR-0009), prueba de carga en el prototipo. Particionado o sharding si el volumen real supera el estimado (CRN-N2). |
| BD-2 | Failover de la base | Las escrituras fallan durante segundos a minutos, contra un presupuesto mensual de unos 5 minutos (QA-4). | Standby sincrónico con failover automático y reintentos con backoff. Se define en el ADR de despliegue (CRN-N3). |
| BD-3 | Mal uso de JSONB | Se pierden restricciones y la base se convierte en un depósito sin esquema. | Validación por esquema del tipo en el adaptador (ADR-0006). Los campos consultados van como columnas. |
| BD-4 | Base compartida entre Panel API, workers y relay, y origen de la copia periódica hacia el almacén analítico | Acoplamiento por esquema y presión de escritura, con el outbox como la tabla más escrita. | Se trata en CRN-N2: dueño de tablas por módulo, partición del outbox y réplica de lectura. |
