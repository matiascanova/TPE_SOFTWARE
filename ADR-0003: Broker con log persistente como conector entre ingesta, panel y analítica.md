# ADR-0003: Broker con log persistente como conector entre ingesta, panel y analítica

- **Estado:** Propuesto
- **Fecha:** 18/10/2026
- **Decisores:** Equipo de diseño (grupo TPE)
- **Drivers de entrada:** Análisis inicial y drivers (CU-01 a CU-05, QA-1, QA-4 a QA-8, QA-12, R-2, R-4), ADR-0001 y ADR-0002.

*El número de ADR es provisional; ajustarlo al siguiente libre.*

---

## 1. Contexto y problema

El ADR-0001 incorporó el broker con log persistente como parte de C-lite, pero sin justificarlo por separado, y la Iteración 1 lo registra entre los conceptos fundamentales sin ADR propio. Este documento cierra esa deuda: explica por qué el conector central entre los tres desplegables es un log persistente y no otra forma de comunicación.

El punto de partida es el siguiente. Los workers de ingesta detectan cambios en las reservas, ya sea por proveedores de viaje o por correo. Esos cambios deben llegar al Panel API, que actualiza el read model, y al consumidor de analítica, que alimenta el almacén OLAP. La pregunta es cómo viaja ese hecho de un desplegable a los otros. Hay cinco tensiones:

1. **Ritmos distintos.** Una disrupción masiva (por ejemplo, el cierre de un aeropuerto) genera ráfagas de eventos que la ingesta recibe de golpe, mientras que el panel y la analítica procesan a su propio ritmo (QA-1, QA-6).
2. **Más de un consumidor del mismo hecho.** El mismo evento lo necesitan el Panel API y la analítica, y probablemente otros consumidores más adelante (QA-12, CU-05).
3. **Recuperación.** Tras una interrupción de un proveedor o de un consumidor, el sistema debe ponerse al día sin perder eventos (QA-5).
4. **Disponibilidad sin acoplamiento temporal.** Que un desplegable esté caído o reiniciándose no debería frenar a los demás (QA-4).
5. **Aislamiento de la analítica.** Las consultas pesadas no deben degradar el panel (CU-05).

Como en el ADR-0001, la viabilidad de implementación es un criterio de primer orden: es un trabajo grupal, con plazo acotado y evaluado por la trazabilidad entre la arquitectura documentada y el código.

**Alcance.** Este ADR decide el *tipo* de conector. No decide la tecnología concreta (ver sección 6), el contrato de eventos y el modelo canónico, el detalle de la tabla outbox (ADR-0002) ni el diseño del read model.

## 2. Criterios de decisión

| Criterio | Peso | Qué mide en esta decisión |
|---|---|---|
| QA-1 (actualizaciones en 5 min) | 3 | Que los cambios lleguen del origen al panel sin perderse y con poca demora. |
| QA-5 / QA-8 (resiliencia e interoperabilidad) | 3 | Buffer, replay y aislamiento de mensajes inválidos y de proveedores defectuosos. |
| QA-4 (99,99 %) | 3 | Ausencia de acoplamiento temporal y de puntos únicos de falla entre desplegables. |
| QA-6 / QA-7 (escalabilidad) | 3 | Absorber picos ×10 y escalar productores y consumidores por separado. |
| QA-12 (evolucionabilidad) | 2 | Sumar consumidores o medios de transporte sin modificar a los emisores. |

Los pesos siguen la columna "Prioridad" del análisis inicial (Alta = 3, Media = 2, Baja = 1), igual que en el ADR-0001. Cuando un criterio agrupa dos QA se toma la prioridad más alta.

**QA no puntuados.** QA-2 y QA-3 dependen del diseño del read model y no del transporte. QA-9 depende de los extractores. QA-10 y QA-11 dependen de la bóveda y de la autorización; el broker solo impone una regla (los eventos no llevan tokens).

**Escala de puntaje (0 a 3):** 0 no cubre el criterio, 1 lo cubre de forma parcial o frágil, 2 lo cubre bien con salvedades, 3 lo cubre de forma natural.

**Método en dos pasos:**

1. **Filtro de viabilidad**, con una dificultad subjetiva de implementación y operación para el equipo.
2. **Puntaje ponderado de QA** entre las alternativas que pasan el filtro. Se calcula también para las que no pasan, a modo de transparencia.

## 3. Alternativas consideradas

| ID | Alternativa | Resumen |
|---|---|---|
| 1 | Comunicación síncrona directa | La ingesta llama por HTTP al Panel API y al consumidor de analítica. No hay intermediario. |
| 2 | Base de datos como bus | La ingesta escribe en la tabla outbox y los consumidores la leen por polling. No hay broker. |
| 3 | Cola simple de consumo destructivo | Una cola por consumidor (RabbitMQ clásico o similar). El mensaje se elimina al confirmarse. |
| 4 | **Broker con log persistente liviano** | Redis Streams o RabbitMQ Streams. Los mensajes quedan ordenados y retenidos, y cada consumidor lleva su propio offset. |
| 5 | Plataforma de streaming completa | Kafka o un servicio gestionado equivalente. |

### 3.1 Filtro de viabilidad

*Valoración inferida del contexto del ADR-0001; ajustar si el equipo tiene otra experiencia.*

| ID | Dificultad de implementación y operación | Resultado |
|---|---|---|
| 1 | Baja | Pasa |
| 2 | Baja a media | Pasa |
| 3 | Media a baja | Pasa |
| 4 | Media: un componente adicional, pero ejecutable con docker-compose. | Pasa |
| 5 | Alta: cluster de varios nodos, particionado y ajuste de consumidores. Más difícil de levantar y demostrar localmente; la versión gestionada exige cuenta cloud. | No pasa |

### 3.2 Puntaje ponderado de QA

| Criterio | Peso | 1 | 2 | 3 | 4 | 5 |
|---|---|---|---|---|---|---|
| QA-1 | 3 | 1 | 2 | 2 | 3 | 3 |
| QA-5 / QA-8 | 3 | 0 | 2 | 1 | 3 | 3 |
| QA-4 | 3 | 0 | 1 | 2 | 2 | 3 |
| QA-6 / QA-7 | 3 | 1 | 1 | 2 | 2 | 3 |
| QA-12 | 2 | 1 | 2 | 2 | 3 | 3 |
| **Total ponderado (máx. 42)** | | **8** | **22** | **25** | **36** | **42** |

## 4. Decisión

**Se elige un broker con log persistente (alternativa 4) como conector central entre los workers de ingesta, el Panel API y el consumidor de analítica. Cada consumidor lee con su propio offset, la entrega es al menos una vez con consumidores idempotentes, y la tecnología concreta se decide aparte (sección 6).**

### Reglas de la decisión

- **Topología.** Los workers publican; el Panel API y la analítica consumen, cada uno con su propio grupo de consumo y su propio offset. Agregar un consumidor no modifica a los productores.
- **Publicación.** Los workers publican a través del outbox (ADR-0002): el cambio y el evento se guardan en la misma transacción y un relay los envía al broker. Si el broker no está disponible, la ingesta sigue escribiendo y el relay se pone al día cuando vuelve.
- **Garantía de entrega.** Al menos una vez. Los consumidores son idempotentes (identificador de evento) y descartan eventos con una versión anterior a la ya aplicada.
- **Orden.** Garantizado por clave, usando el identificador de viaje. No hay orden global.
- **Retención.** El log conserva los eventos durante una ventana configurable, que debe cubrir como mínimo la interrupción más larga que se quiera absorber (valor a definir). El log no es la fuente de verdad: lo es la base relacional.
- **Mensajes inválidos.** Se derivan a un stream separado y se registran (QA-8), sin frenar el consumo del resto.
- **Contenido de los eventos.** Sin tokens ni credenciales, y con los datos personales mínimos, preferentemente como referencias (R-4, QA-10).
- **Acceso cruzado.** Un desplegable no lee los datos de otro: la integración es solo por eventos.
- **Puerto de mensajería.** El dominio depende de puertos (publicar, consumir) y la tecnología vive en la capa de infraestructura, de modo que se puede cambiar sin tocar el núcleo (ADR-0001).

### 4.1 Por qué un log persistente y no una cola simple (alternativa 3)

La alternativa 3 es la más cercana. La diferencia está en lo que ocurre después de leer un mensaje:

- **Varios lectores sobre el mismo hecho.** En una cola, el mensaje se elimina al confirmarse. Para que el panel y la analítica reciban lo mismo hace falta una cola por consumidor y una copia por cada una, y cada consumidor nuevo exige reconfigurar la topología del lado del productor. En un log, cada consumidor solo lleva su posición (QA-12, CU-05).
- **Replay.** Con una cola, un evento ya confirmado no se puede volver a leer. Si el read model queda corrupto por un bug, o si el modelo del OLAP cambia, no hay desde dónde reprocesar (QA-5).
- **Arranque de consumidores nuevos.** Un consumidor agregado más adelante puede leer el historial retenido en lugar de empezar vacío.

Una cola simple sería suficiente si hubiera un solo consumidor y nunca hiciera falta releer. Ninguna de las dos condiciones se cumple.

### 4.2 Por qué no la base de datos como bus (alternativa 2)

El outbox ya usa la base, pero solo como etapa de salida para no perder eventos. Usarla además como bus de distribución concentra todo en el componente más caro de escalar: el polling de varios consumidores compite con las escrituras de la ingesta y agrava el cuello de botella de la base compartida (C-4), y la base primaria pasa a ser el único camino de los eventos (QA-4, QA-6). Además, la tabla crecería sin límite o habría que purgarla, con lo que se perdería el replay.

### 4.3 Por qué no comunicación síncrona directa (alternativa 1)

Es la más simple, pero acopla los desplegables en el tiempo: si el panel está caído o lento, la ingesta falla o se frena, y una disrupción masiva se propaga como carga directa. No hay buffer de picos ni replay, y un proveedor defectuoso o un consumidor lento pueden degradar al resto (QA-4, QA-5, QA-8). Cada consumidor nuevo obliga además a modificar el emisor (QA-12).

### 4.4 Por qué no una plataforma de streaming completa (alternativa 5)

Es la mejor en QA, pero queda descartada por viabilidad, con el mismo razonamiento del ADR-0001 para los microservicios completos:

- Exige operar un cluster, con particionado y ajuste de consumidores, para un trabajo con plazo acotado.
- Su ventaja sobre la alternativa 4 (más escalabilidad y disponibilidad del propio broker) no se puede demostrar con un prototipo acotado, así que se pagaría el costo sin mostrar el beneficio.
- La evolución hacia ella no es una reescritura: el modelo de consumo (grupos y offsets) es conceptualmente el mismo, y el puerto de mensajería aísla el cambio en la capa de infraestructura.

## 5. Consecuencias

### Positivas

- Cobertura defendible de QA-1 y QA-5/QA-8: buffer de picos, replay dentro de la retención y aislamiento de mensajes inválidos.
- Si el broker se cae, el panel sigue respondiendo desde el read model con la última versión conocida: se degrada la frescura de los datos, no la disponibilidad del panel. La ingesta sigue escribiendo en el outbox mientras tanto.
- Los consumidores se pueden reiniciar, escalar o reconstruir sin coordinarse con los productores.
- Sumar un consumidor (por ejemplo, notificaciones) o un medio de transporte nuevo no toca a los emisores existentes (QA-12).
- El código refleja la documentación: un puerto de publicación y un puerto de consumo por desplegable.

### Negativas y riesgos aceptados

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| B-1 | Consistencia eventual entre ingesta y panel | Parte del presupuesto de 5 minutos de QA-1 se consume en el camino. | Medir el retraso de consumo por grupo con alertas y repartir el presupuesto por etapa (ingesta, outbox, broker, read model). |
| B-2 | Entrega al menos una vez | Eventos duplicados o reentregados. | Idempotencia por identificador de evento, versión por viaje y pruebas de reentrega. |
| B-3 | Orden solo por clave | No hay orden relativo entre eventos de distintos viajes. | Clave = identificador de viaje; no depender de un orden global. |
| B-4 | El log no recupera lo que el proveedor nunca entregó | QA-5 no se cumple solo con el log. | Los adaptadores reconcilian con el proveedor al restablecerse la API (eventos pendientes o estado actual). El log garantiza que lo ya ingerido no se pierde ni se procesa a medias. |
| B-5 | Retención finita | El replay llega solo hasta el borde de la ventana. | La base relacional es la fuente de verdad. Procedimiento de reconstrucción del read model y del OLAP desde la base para lo que quede fuera de la ventana. |
| B-6 | Datos personales persistidos en el log (R-4) | Conflicto con borrados y con límites de residencia por región. | Eventos mínimos con referencias, retención acotada, cifrado en reposo. Definir en el ADR de multi-región cómo se propaga un pedido de borrado. |
| B-7 | Un broker más para operar (ya registrado como C-7) | Complejidad operativa y un componente crítico más. | Broker gestionado o clusterizado en producción; una sola instancia en el prototipo. |
| B-8 | El contrato de eventos pasa a ser una API interna | Un cambio incompatible rompe a los consumidores. | Esquemas versionados con compatibilidad hacia atrás, definidos en el ADR de contratos de eventos. |

### Efecto sobre los drivers

| Driver | Aporte | Pendiente |
|---|---|---|
| QA-1 | Buffer, entrega desacoplada y presupuesto de tiempo por etapa. | Tecnología, dimensionamiento y diseño del read model. |
| QA-4 | Sin acoplamiento temporal entre desplegables. | Alta disponibilidad del propio broker (B-7). |
| QA-5 | Replay dentro de la retención y buffer ante caídas de consumidores. | Reconciliación con el proveedor (B-4) y valor de retención. |
| QA-6 / QA-7 | Absorción de picos y escalado independiente de productores y consumidores. | Particionado por clave según la tecnología elegida. |
| QA-8 | Aislamiento de inválidos en un stream separado. | Contratos de adaptadores. |
| QA-12 | Nuevos consumidores sin tocar emisores. | Contrato de eventos versionado. |

## 6. Decisión abierta: tecnología del broker

La Iteración 1 deja abierta la elección entre Redis Streams y RabbitMQ Streams. Ambas cumplen el modelo de log con offsets por consumidor. Las diferencias relevantes para esta decisión son:

| Aspecto | Redis Streams | RabbitMQ Streams |
|---|---|---|
| Almacenamiento | En memoria, con persistencia opcional (AOF o RDB). | Log en disco, segmentado. |
| Durabilidad ante falla de un nodo | La replicación es asíncrona por defecto: ante un failover pueden perderse eventos ya confirmados. | Replicado entre los nodos del cluster. |
| Retención | Acotada por la memoria; se recorta por cantidad o antigüedad. | Por tamaño o antigüedad, en disco; las ventanas largas son más baratas. |
| Particionado por clave | Un stream vive en un solo nodo; se reparte en N streams por hash de la clave de forma manual. | Super streams, con particiones y enrutamiento por clave. |
| Consumo | Grupos de consumo con confirmaciones. | Consumo no destructivo por offset (primero, último, por tiempo o por posición). |
| Operación | Simple de levantar. Si el read model en caché también usa Redis, conviene una instancia separada del broker (políticas de expulsión distintas). | Un componente más; para el prototipo se levanta con docker-compose. |

**Inclinación preliminar.** Como QA-5 y la retención dependen de la durabilidad, RabbitMQ Streams ofrece mejores garantías de base. Redis Streams es defendible solo si se acepta una ventana de pérdida tras un failover y se agrega un mecanismo de reconciliación contra el outbox o la base. Esta inclinación se confirma en la Iteración 2.

**Datos que faltan para cerrarla:** volumen de eventos por segundo en picos, ventana de retención requerida, experiencia del equipo con cada tecnología y costo de las opciones gestionadas.

## 7. Relación con otros ADR y próximos pasos

- **ADR-0001:** define C-lite y su regla de capas; este ADR justifica uno de sus componentes.
- **ADR-0002:** define la tabla outbox, que es el punto de entrada de los eventos al broker.
- **ADR de tecnología del broker:** cerrar la sección 6 (Iteración 2).
- **ADR de contratos de eventos y modelo canónico:** esquema, versionado, clave de partición e identificadores de evento (Iteración 2).
- **ADR de despliegue:** alta disponibilidad del broker y objetivo de failover (Iteración 3).
- **ADR de multi-región:** residencia de datos y borrado sobre el log (B-6).
