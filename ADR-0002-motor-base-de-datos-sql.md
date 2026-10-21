# ADR-0005: Almacenamiento operacional: motor de base de datos (PostgreSQL) y read model del panel

- **Estado:** Propuesto
- **Fecha:** 19/10/2026 (versión 2, ver historial al final)
- **Decisores:** Equipo de diseño (grupo TPE)
- **Depende de:** [ADR-0001: Estilo arquitectónico](ADR-0001-estilo-arquitectonico.md) (C-lite), ADR-0002 (workers y outbox), ADR-0003 (broker con log persistente) y ADR-0004 (almacén analítico)
- **Drivers de entrada:** [Análisis inicial y drivers](Analisis-Inicial-y-Drivers.md) (QA-1, QA-2, QA-3, QA-4, QA-5, QA-6, QA-11, QA-12, R-4)

*El número de ADR es provisional: este documento reemplaza al ADR-0002 anterior (motor de base de datos), cuyo número pasó a los workers y el outbox. Ajustarlo al siguiente libre.*

---

## 1. Contexto y problema

C-lite tiene tres desplegables (Panel API, ingesta y carga de analítica) y una base relacional compartida como almacén del **modelo de escritura**. Además, el ADR-0001 incluye CQRS solo en el panel: las lecturas del panel no salen de esa base sino de un **read model**. Este ADR toma dos decisiones acopladas:

- **Decisión A:** qué motor implementa la base operacional.
- **Decisión B:** qué es el read model del panel, dónde vive y cómo se mantiene.

**Por qué van juntas.** Se condicionan mutuamente. Si las lecturas del panel se sirven desde el read model, la base solo tiene que sostener escrituras, y eso cambia qué se le pide al motor (QA-6, riesgo C-4). A la vez, la forma del read model depende del motor: puede ser una caché, tablas propias o una base documental. Además, comparten una pregunta que quedó abierta en la Iteración 1: qué hace el Panel API con el stream de eventos del broker.

**Alcance.** Solo el almacenamiento operacional y la lectura del panel. La base guarda usuarios, viajes, reservas, permisos de compartir, whitelists, auditoría y la tabla de outbox. Quedan fuera, y tienen su propio ADR:

- El **almacén analítico** (CU-05, ADR-0004).
- El **broker** con log persistente (ADR-0003).
- El **mecanismo** de los workers y del outbox (ADR-0002). Este ADR solo decide dónde viven sus tablas.
- Los **tokens de correo**, que van en una bóveda aislada (QA-10) y no en esta base.

**Factores que el equipo propone como razones a favor de SQL (decisión A):**

1. El equipo ya conoce SQL.
2. La velocidad del motor.
3. Que todos los datos tendrán el mismo formato y estructura.

Antes de decidir, el ADR evalúa si cada uno es una razón válida (sección 2), porque el tercero no se sostiene tal como está formulado.

## 2. Validez de los factores de decisión (decisión A)

### 2.1 Conocimiento previo del equipo: válido y de peso alto

Es una razón legítima en este contexto. El TPE es grupal, de plazo acotado, y evalúa que el prototipo sea trazable y consistente con la arquitectura documentada. Esa es la lógica del filtro de viabilidad del ADR-0001: lo que el equipo no domina se paga en tiempo de aprendizaje, bugs y deriva entre documentación y código.

Limitación: es una razón de **riesgo de ejecución**, no de adecuación técnica. Solo es suficiente si ninguna alternativa conocida falla en un QA crítico. Acá no ocurre: SQL cubre los QA Alta con tácticas conocidas (sección 5). También conviene saber cuánto pesa: sin este factor, las cuatro familias evaluadas quedan a menos de 5 puntos entre sí (ver 4.2). El conocimiento es lo que desempata, y hay que escribirlo como tal.

### 2.2 Velocidad: válido solo como "suficiente", no como "más rápida"

SQL no es intrínsecamente más rápido que las alternativas. Un almacén clave-valor distribuido responde mejor a lecturas por clave a escala masiva. Lo correcto es afirmar que PostgreSQL es **suficientemente rápido** para este volumen, y por tres razones:

- **El panel no lee de la base operacional.** El QA-2 (800 ms) y el QA-3 (FCP de 1,4 s) los sostiene el read model del panel (decisión B, sección 6). La base operacional atiende las escrituras de la ingesta, las lecturas de gestión manual, la reconstrucción del read model y la carga de analítica.
- **El volumen es moderado para un motor relacional.** Estimación del equipo, a verificar en el prototipo: decenas de millones de reservas en total y, en pico ×10, del orden de miles de escrituras por segundo. Eso está dentro del rango de un primario PostgreSQL bien indexado y con tablas particionadas.
- **Las consultas críticas son por clave** (usuario, viaje, proveedor + identificador externo) y se resuelven con índices.

Dónde sí limita: la escritura está en un único primario. Es el techo de QA-6 y se trata como riesgo (sección 7).

### 2.3 "Todos los datos tienen un formato y estructura similar": parcialmente válido

Los datos no van a ser todos exactamente iguales y no van a tener la misma estructura, va a haber una parte uniforme y una parte no uniforme:

| Dato | ¿Uniforme? | Tratamiento |
|---|---|---|
| Usuarios, cuentas, permisos de compartir, whitelists, auditoría, outbox | Sí | Tablas relacionales tradicionales |
| Viaje y campos comunes de una reserva (id, viaje, tipo, proveedor, estado, código de confirmación, inicio y fin en UTC con zona, precio y moneda) | Sí | Columnas tipadas con restricciones |
| Atributos propios del tipo: vuelo (número, terminal, asiento), hotel (dirección, habitación), auto (punto de retiro), tren (andén, coche) | **No** | Columna `detalle` en JSONB, validada por esquema según el tipo |
| Mensajes originales de proveedores y texto extraído de correos | **No** | JSONB, o almacenamiento de objetos si el volumen lo exige |

Consideraciones:

- La **uniformidad del modelo canónico** (modelo canónico y contratos de adaptadores, pendientes) es lo que permite que el núcleo sea relacional. Esa uniformidad no existe en el mundo externo, la crean los adaptadores al traducir (R-2).
- Además, **la uniformidad de la estructura es un argumento sobre el esquema, no sobre el motor**. Un modelo uniforme se puede guardar en un motor documental, y un modelo heterogéneo se puede guardar en uno relacional con columnas JSON. Pesa a favor de SQL solo en combinación con las razones de transaccionalidad (outbox) y de relaciones entre entidades (usuario, viaje, reserva, permisos).
- La heterogeneidad **choca con QA-12** (sumar trenes con un esfuerzo máximo de 2 días-persona sin modificar el núcleo). Si cada tipo de reserva fuera una tabla propia, agregar trenes exigiría una migración de esquema. Por eso el diseño usa núcleo tipado más detalle en JSONB.

**Conclusión:** la razón es válida para el núcleo del modelo y falsa para los atributos por tipo y los datos crudos. Se reformula como: *"el núcleo del dominio es relacional y uniforme; la variabilidad por tipo se aísla en un campo de detalle validado"*.

## 3. Alternativas consideradas (decisión A)

| ID | Alternativa | Resumen |
|---|---|---|
| 1 | **PostgreSQL** | Relacional con JSONB, particionado nativo, row-level security. |
| 2 | MySQL / MariaDB | Relacional de la misma familia, soporte JSON más limitado. |
| 3 | MongoDB | Base documental con esquema flexible y transacciones multi-documento. |
| 4 | DynamoDB / Cassandra | Clave-valor o wide-column distribuido, diseño orientado a patrones de acceso. |

## 5. Decisión A: motor de la base operacional

**Se elige PostgreSQL como motor de la base operacional, con un núcleo relacional tipado y un campo `detalle` en JSONB validado por esquema para los atributos propios de cada tipo de reserva.**

### 5.1 Por qué PostgreSQL

- **Outbox transaccional.** El ADR-0002 define un outbox para no perder eventos entre guardar el cambio y publicarlo en el broker. Eso requiere una transacción ACID que cubra la tabla de negocio y la tabla de outbox. Es la razón arquitectónica más fuerte para un motor relacional, más que la velocidad o la forma de los datos.
- **Relaciones entre entidades.** Usuario, viaje, reserva y permisos de compartir son relacionales, y el QA-11 (solo personas designadas ven un itinerario) se puede reforzar con restricciones de integridad y *row-level security*.
- **JSONB indexable.** Permite el campo `detalle` con índices solo donde se consulta y absorbe nuevos tipos de transporte sin migrar (QA-12).
- **Particionado nativo** por región o por usuario, que sirve a la escala (QA-6) y a la residencia de datos (R-4, una instancia por región). También sirve para particionar por tiempo la tabla de outbox (ADR-0002).
- **Fechas.** `timestamptz` junto con la zona de origen resuelve el almacenamiento en UTC con zona del viaje.
- **Replicación y failover** maduros, con servicio gestionado o herramientas conocidas, para la disponibilidad (QA-4). Una réplica de lectura sirve además a la carga de analítica (ADR-0004).
- **Prototipo.** Se levanta con una imagen en docker-compose y es reproducible por todo el grupo.

### 5.2 Por qué no MySQL

Cubre casi todo igual y también está en la familia que el equipo conoce. Pierde en rasgos que este diseño usa: indexación JSON menos directa, particionado y seguridad por fila menos completos. Si la experiencia del equipo fuera principalmente en MySQL, **sería una elección razonable** y el resto de la decisión (SQL, núcleo tipado más detalle JSON) se mantiene sin cambios.

### 5.3 Por qué no MongoDB

Se ajusta bien a la variabilidad por tipo y a QA-12, pero el núcleo es relacional y uniforme, de modo que esa flexibilidad se usaría poco. Las garantías transaccionales para el outbox son más débiles, y el equipo no tiene la experiencia, lo que lo saca por el filtro de viabilidad.

### 5.4 Por qué no DynamoDB / Cassandra

Es la mejor opción en escalabilidad de escritura y disponibilidad, pero exige diseñar el modelo a partir de los patrones de acceso y tiene transacciones limitadas, lo que complica el outbox y la gestión manual de reservas. Es difícil de reproducir localmente y añade un acoplamiento a un proveedor cloud. Falla el filtro de viabilidad y su ventaja (escala de escritura) no es necesaria con el volumen estimado.

### 5.5 Reglas del esquema

Estas reglas cierran los requisitos que dejaron los otros ADR sobre la base:

- **Dueño por módulo.** Cada módulo es dueño de sus tablas. El acceso cruzado va por vistas o por eventos (ADR-0001, riesgo de base compartida).
- **Versión en cada tabla de negocio.** Cada viaje y cada reserva llevan una versión que sube con cada cambio. La usan el guardado idempotente de los workers (ADR-0002), el read model (decisión B) y la carga incremental de analítica (ADR-0004). Las demás tablas llevan una marca de modificación.
- **Vistas de solo lectura** documentadas para la carga de analítica, que no lee las tablas internas de otros módulos (ADR-0004).
- **Tabla de auditoría.** Registra los cambios de las reservas y los accesos rechazados a itinerarios compartidos (QA-11). Es la fuente de historia fina para la analítica (ADR-0004, A-2).
- **Outbox particionado por tiempo**, con descarte de particiones viejas (ADR-0002).
- **Migraciones versionadas** (Flyway, Liquibase o similar), con prueba de la carga de analítica en CI.

## 6. Decisión B: read model del panel

### 6.1 Qué es y qué problema resuelve

El **read model** es una copia derivada y desnormalizada de lo que el panel necesita mostrar. La base guarda el modelo de escritura y el panel lee del read model (CQRS solo en el panel, ADR-0001). No es fuente de verdad: la base lo es, y el read model se puede reconstruir desde ella.

Resuelve tres cosas:

- **Latencia y picos de lectura.** El panel concentra la mayor parte del tráfico: 2 M de usuarios activos semanales, con picos ×10, 800 ms de respuesta (QA-2) y 1,4 s hasta el primer contenido (QA-3).
- **Aislamiento de la escritura.** Sin read model, las lecturas con joins competirían con las escrituras de la ingesta en un único primario. Es el problema que el ADR-0001 le atribuye a la alternativa E.
- **El uso del stream de eventos.** Da respuesta a qué hace el Panel API con los eventos que consume (ADR-0003): los usa para mantener el read model al día.

### 6.2 Alternativas consideradas

| ID | Alternativa | Resumen |
|---|---|---|
| R1 | Sin read model | El panel lee directo de la base operacional, con índices. |
| R2 | Tablas de lectura en PostgreSQL | Tablas desnormalizadas en la misma base (o en una réplica), mantenidas por el Panel API a partir de eventos. |
| R3 | **Almacén clave-valor en memoria** | Un documento por viaje y un índice por usuario en Redis, mantenidos por el Panel API a partir de eventos y reconstruibles desde la base. |
| R4 | Base documental propia | MongoDB con persistencia propia, mantenida a partir de eventos. |

### 6.4 Decisión

**Se elige R3: el read model es un almacén clave-valor en memoria (Redis), con un documento por viaje y un índice por usuario. El Panel API lo mantiene a partir de los eventos de la ingesta y de sus propios cambios manuales. El panel lee solo del read model, y este se reconstruye desde la base cuando falta una entrada.**

**Reglas:**

- **Contenido.** El documento de un viaje tiene lo que el panel muestra: reservas, estados, fechas con zona y el `detalle`, más la lista de personas designadas a verlo (referencias, no datos personales). El índice de un usuario lista sus viajes con un resumen. Sin tokens ni contenido de correo (R-4).
- **No es fuente de verdad.** Toda entrada se puede reconstruir desde la base.
- **Actualización por eventos.** El evento es un aviso mínimo con referencias (ADR-0002, ADR-0003): dice que el viaje X llegó a la versión N. El Panel API lee el estado actual del viaje en la base y reescribe el documento. Como siempre lee el estado actual, reprocesar el mismo evento o recibirlo fuera de orden no tiene efecto.
- **Escritura condicional por versión.** Un documento solo se reemplaza si la versión nueva es mayor o igual a la guardada, con una operación atómica (por ejemplo un script de Redis). Así dos escritores no se pisan.
- **Agrupación.** Los eventos de un mismo viaje que llegan en una ventana corta se resuelven con una sola lectura de la base.
- **Cambios manuales (CU-06).** El Panel API guarda el cambio en la base y después actualiza el read model directamente, para que el viajero vea su propio cambio sin esperar. No pasan por el broker (ADR-0003).
- **Permisos de compartir (CU-04, QA-11).** El Panel API verifica en cada acceso a un itinerario compartido que la persona esté en la lista del documento. Ante un cambio de permisos, **borra** las entradas afectadas en lugar de actualizarlas: se reconstruyen desde la base en la próxima lectura.
- **Lecturas.** El panel lee del read model. Si falta la entrada (primer acceso, expulsión o pérdida), la reconstruye desde la base y la guarda, con protección para que muchas lecturas simultáneas de la misma entrada provoquen una sola reconstrucción.
- **Memoria acotada.** Con expulsión por uso (LRU): los usuarios inactivos se reconstruyen bajo demanda.
- **Instancia propia.** Distinta de la del broker si ambos usaran Redis, por sus políticas de expulsión (ADR-0003, sección 6). Una por región (R-4).
- **Reconstrucción masiva.** Un procedimiento repuebla el read model desde la base tras una pérdida, con ritmo limitado y empezando por los usuarios activos.
- **Puerto.** El dominio accede al read model por un puerto, y la tecnología vive en la capa de infraestructura. Pasar a R2 o a R4 no toca el núcleo (ADR-0001).

### 6.5 Cómo funciona

**Caso práctico.** Siguiendo el ejemplo del ADR-0002: el worker guardó la reserva del hotel (versión 4 del viaje) y escribió el evento en el outbox, y el relay lo publicó en el stream de eventos.

1. El Panel API recibe el evento `ReservaCreada` del viaje 123, versión 4.
2. Lee el viaje 123 de la base, por clave indexada.
3. Escribe el documento `viaje:123` en el read model, solo si su versión es mayor o igual a la guardada.
4. Actualiza el índice del usuario, que incluye ese viaje.

Cuando el viajero abre el panel, el Panel API lee su índice y los documentos de sus viajes del read model, sin tocar la base.

**Qué pasa cuando algo falla:**

| Qué falla | Qué pasa | Resultado |
|---|---|---|
| Se pierde el read model (reinicio o falla). | Las lecturas reconstruyen cada entrada desde la base y corre la reconstrucción masiva. | El panel responde más lento un rato. No se pierde información. |
| La base está en failover. | Las entradas presentes se siguen sirviendo. Las ausentes no se pueden reconstruir hasta que vuelva. | El panel sigue disponible para los usuarios con entrada (QA-4, QA-5). |
| El broker está caído. | No llegan eventos nuevos y el read model conserva la última versión. | Se degrada la frescura, no la disponibilidad (ADR-0003). |
| El Panel API está caído o atrasado. | Los eventos esperan en el stream. | Al volver, actualiza el read model sin perder eventos (ADR-0003). |
| Falla la actualización del read model tras un cambio manual. | Se reintenta y, si no se logra, se borra la entrada. | La próxima lectura reconstruye desde la base. |
| Llega un evento duplicado o fuera de orden. | Se relee el estado actual y la escritura es condicional por versión. | Sin efecto. |

### 6.6 Por qué no las otras alternativas

- **R1 (sin read model).** Es la más simple, pero las lecturas del panel hacen joins sobre el modelo de escritura y compiten con la ingesta en un único primario. No sostiene los picos ×10 (QA-6) y una falla de la base tira el panel (QA-4).
- **R2 (tablas de lectura en PostgreSQL).** Es la opción más conservadora y no suma tecnología. Aísla las lecturas de las tablas de negocio, pero sigue consumiendo el mismo motor y el mismo primario, o una réplica con su propio retraso, y un único motor limita el crecimiento de las lecturas. Es una alternativa razonable **si el equipo prefiere no sumar Redis**: se mantienen las reglas de actualización de la decisión B y solo cambia el almacén.
- **R4 (base documental propia).** Es la mejor en QA, sobre todo por la persistencia propia, que permite servir el panel aunque la base esté caída. Se descarta por viabilidad: es una tecnología nueva para el equipo. Queda como evolución si la dependencia de la base ante un failover prolongado resultara un problema (S-8).

## 7. Consecuencias

### Positivas

- Outbox transaccional simple y verificable, que sostiene la entrega confiable de eventos (QA-1, QA-5).
- Menor riesgo de ejecución: el equipo trabaja con una tecnología que domina, lo que ayuda a la trazabilidad del prototipo.
- Modelo claro, con integridad referencial, y extensible a trenes sin migraciones gracias al campo `detalle`.
- Camino directo a particionado por región (R-4).
- Las lecturas del panel quedan aisladas de las escrituras de la ingesta, con latencia y escalado de lectura propios (QA-2, QA-3, QA-6).
- El stream de eventos tiene un uso concreto, y la reconstrucción del read model desde la base cubre lo que quede fuera de la retención del log (ADR-0003, B-5).
- Si el broker o la base fallan, el panel sigue sirviendo la última versión conocida para los usuarios con entrada (QA-4, QA-5).

### Negativas y riesgos aceptados

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| S-1 | Un único primario de escritura | Techo de escritura en picos ×10 (QA-6) y punto único de falla ante QA-4. | Particionado, índices ajustados, outbox con lotes. Si el volumen real supera el estimado: particionar por región o usuario con herramientas de sharding, o mover tablas de alto volumen a otro almacén. |
| S-2 | Failover de la base | Las escrituras fallan durante unos segundos a minutos, y el presupuesto mensual de QA-4 es de unos 5 minutos. | Standby sincrónico multi-AZ con failover automático, reintentos con backoff en los clientes. El panel sigue sirviendo el read model (QA-5). |
| S-3 | Mal uso de JSONB | Se pierden restricciones y la base se convierte en un depósito sin esquema. | Los campos que se consultan o filtran van como columnas tipadas. Cada tipo tiene un esquema de validación en su adaptador, parte del contrato (spec-driven). |
| S-4 | Base compartida entre Panel API, workers, relay y carga de analítica (riesgo C-4) | Acoplamiento por esquema y presión de escritura, con la tabla outbox como la más escrita. | Cada módulo es dueño de sus tablas. El acceso cruzado va por vistas o por eventos. Migraciones versionadas. La carga de analítica lee de una réplica en producción (ADR-0004). |
| S-5 | Residencia de datos entre regiones | El compartir itinerarios entre regiones se complica con instancias por región. | Se resuelve en el ADR de multi-región. |
| S-6 | Read model con retraso | El cambio aparece en el panel después del evento y de la lectura de la base, y consume parte de los 5 minutos de QA-1. | Medir el retraso de consumo con alertas, agrupar eventos por viaje y repartir el presupuesto por etapa (ADR-0003, B-1). |
| S-7 | Una lectura adicional de la base por cada evento | Presión de lectura sobre la base compartida. | Lecturas por clave indexada, agrupación por viaje y réplica de lectura si hace falta. |
| S-8 | El read model depende de la base para reconstruirse | Si se pierde o se expulsa una entrada mientras la base está en failover, ese usuario no se puede servir. | Persistencia y réplica del read model, memoria dimensionada para los usuarios activos y reconstrucción priorizada. Evolución a R4 si no alcanza. |
| S-9 | Permisos desactualizados en el read model (QA-11) | Una persona a la que se quitó el acceso podría ver un itinerario si falla la actualización. | Borrar la entrada ante un cambio de permisos en lugar de actualizarla, con reintentos. Registrar los rechazos en la auditoría. |
| S-10 | Dos escritores del read model (eventos y cambios manuales) | Pueden pisarse entre sí. | Escritura condicional por versión, con operación atómica. |
| S-11 | Una pieza más para operar | Complejidad operativa del read model. | Una sola instancia en el prototipo, con docker-compose. Servicio gestionado o replicado en producción. |

## 8. Decisiones abiertas

- **Estructura exacta de los documentos del read model.** Depende del modelo canónico y de los contratos de adaptadores.
- **Dimensionamiento del read model.** Memoria, política de expulsión y ventana de agrupación de eventos, a definir con una prueba de carga.
- **Persistencia y réplica del read model**, según la alta disponibilidad que se defina en el ADR de despliegue.
- **Contenido de la tabla de auditoría** y granularidad de los accesos rechazados.
- **Particionado** de la base y del outbox: criterio, granularidad y ventana de conservación.
- **Réplica de lectura para la carga de analítica** (ADR-0004).

## 9. Relación con otros ADR y próximos pasos

- **ADR-0001:** define C-lite, la base compartida (C-4) y CQRS solo en el panel.
- **ADR-0002:** define los workers y el outbox. Este ADR decide dónde viven sus tablas y la versión por viaje que usa el guardado idempotente.
- **ADR-0003:** define el stream de eventos que consume el Panel API para mantener el read model.
- **ADR-0004:** la carga de analítica usa las vistas de solo lectura, la versión por tabla y la réplica de lectura que define este ADR.
- **ADR de despliegue (Iteración 3):** alta disponibilidad de la base y del read model, y objetivo de failover.
- **ADR de multi-región:** residencia de datos y compartir itinerarios entre regiones (S-5).
- **ADR de contratos de mensajes y modelo canónico (Iteración 2):** de él depende la estructura de los documentos del read model.

## Historial de cambios

- **v1 (14/10/2026):** decisión del motor de la base operacional (PostgreSQL), con el read model fuera de alcance.
- **v2 (19/10/2026):** se agrega la decisión B (read model del panel) y el usuario pasa a tener un solo ADR para el almacenamiento operacional. Se alinea con los ADR-0001 a 0004. Se renumera como ADR-0005. Se agregan los totales a la tabla de evaluación del motor y la sección 4.2, a la que ya hacía referencia la v1. Se agregan las reglas del esquema (sección 5.5) y los riesgos S-6 a S-11.
