# ADR-0003: Broker con log persistente como conector de la ingesta

- **Estado:** Propuesto
- **Fecha:** 19/10/2026 (versión 2, ver historial al final)
- **Decisores:** Equipo de diseño (grupo TPE)
- **Drivers de entrada:** Análisis inicial y drivers (CU-01 a CU-03, QA-1,QA-5 a QA-7, R-2, R-6).

*El número de ADR es provisional; ajustarlo al siguiente libre.*

---

## 1. Contexto y problema

El ADR-0001 incorporó el broker con log persistente como parte de C-lite, pero sin justificarlo por separado, y la Iteración 1 lo registra entre los conceptos fundamentales sin ADR propio. Este documento cierra esa deuda: explica por qué la ingesta usa un log persistente como conector y no otra forma de comunicación.

El punto de partida es el siguiente. Las notificaciones de proveedores de viaje y de proveedores de correo llegan por push a la ingesta. Un **receptor** las recibe, las encola y responde. Los **workers** las toman a su ritmo, las traducen al modelo canónico y guardan el cambio y un evento en la base (outbox, ADR-0002). Un **relay** publica los eventos pendientes y el **Panel API** los consume para actualizar el read model. El broker aparece entonces dos veces: como buffer de entrada (stream de trabajo) y como conector hacia el panel (stream de eventos).

Hay dos tensiones que abordaremos en esta iteracion:

1. **Ráfagas impredecibles en la entrada.** Una disrupción masiva (por ejemplo, el cierre de un aeropuerto) o una sincronización masiva de casillas de correo generan picos de notificaciones. Los proveedores esperan una respuesta rápida, mientras que procesar una notificación es más lento: hay que consultar al proveedor, leer su contenido e interpretarlo (QA-1, QA-6, QA-7).
2. **Recuperación.** Tras una interrupción de un proveedor o del Panel API, el sistema debe ponerse al día sin perder lo ya recibido (QA-5).

Como en el ADR-0001, la viabilidad de implementación es un criterio de primer orden con plazo acotado

## 2. Criterios de decisión

| Criterio | Qué mide en esta decisión |
|---|---|
| QA-1 (actualizaciones en 5 min) | Que los cambios lleguen del origen al panel sin perderse y con poca demora. |
| QA-5(resiliencia e interoperabilidad) | Buffer, replay y aislamiento de mensajes inválidos y de proveedores defectuosos. |
| QA-6 / QA-7 (escalabilidad) | Absorber picos ×10 y escalar productores y consumidores por separado. |

## 3. Alternativas consideradas

| ID | Alternativa | Resumen |
|---|---|---|
| 1 | Comunicación síncrona directa | El receptor procesa en línea antes de responder y el worker llama directo al Panel API. No hay intermediario ni buffer. |
| 2 | Base de datos como bus | El trabajo pendiente y los eventos pasan por tablas (cola de trabajo y outbox) que los consumidores leen por polling. No hay broker. |
| 3 | Cola durable simple | Una cola para el trabajo pendiente y otra para los eventos (RabbitMQ clásico o similar). El mensaje se elimina al confirmarse. |
| 4 | **Broker con log persistente liviano** | Los mensajes quedan ordenados y retenidos, y cada consumidor lleva su propio offset. |
| 5 | Plataforma de streaming completa | Kafka o un servicio gestionado equivalente. |

### 3.1 Filtro de viabilidad

## 4. Decisión

**Se elige un broker con log persistente (alternativa 4) como conector de la ingesta, con dos streams: uno de trabajo pendiente (receptor → workers) y otro de eventos (relay → Panel API). Cada consumidor lee con su propio offset, la entrega es al menos una vez con consumidores idempotentes, y la tecnología concreta se decide aparte (sección 6).**

### Reglas de la decisión

- **Un broker, dos streams.** Un solo componente con un stream de trabajo y otro de eventos, para no sumar piezas. Los dos tienen retención, inválidos y monitoreo propios.
- **Receptor.** Verifica la autenticidad de la notificación, la encola y responde. No procesa nada más, para que la respuesta al proveedor sea rápida. Si no puede encolar, responde con error para que el proveedor reintente.
- **Workers.** Toman trabajo del stream a su ritmo, con reintentos y backoff por proveedor. Escalan agregando workers al mismo grupo de consumo. Guardan el cambio y el evento en la base en una transacción (outbox, ADR-0002).
- **Relay y Panel API.** El relay publica en el stream de eventos los eventos pendientes del outbox. El Panel API consume ese stream con su propio offset para actualizar el read model. Agregar un consumidor no modifica a los productores.
- **Garantía de entrega.** Al menos una vez en ambos streams. Los workers son idempotentes (reprocesar la misma notificación no duplica la reserva) y el Panel API descarta eventos con una versión anterior a la ya aplicada.
- **Orden.** Garantizado por clave. En el stream de eventos, el identificador de viaje. En el stream de trabajo, la cuenta o la reserva a la que refiere la notificación (a definir en el contrato). No hay orden global.
- **Retención.** Cada stream conserva sus mensajes durante una ventana configurable, que debe cubrir como mínimo la interrupción más larga que se quiera absorber (valor a definir). El log no es la fuente de verdad: lo es la base relacional.
- **Mensajes inválidos y trabajo fallido.** Se derivan a un stream separado y se registran, sin frenar el consumo del resto. Esto incluye el trabajo cuyos reintentos se agotaron.
- **Acceso cruzado.** Los desplegables no se llaman entre sí: la ingesta comunica los cambios al panel solo por eventos. Cada módulo es dueño de sus tablas en la base compartida (ADR-0001, riesgo de base compartida).
- **Puerto de mensajería.** El dominio depende de puertos (publicar, consumir) y la tecnología vive en la capa de infraestructura, de modo que se puede cambiar sin tocar el núcleo (ADR-0001).

### 4.1 Por qué un log persistente y no una cola durable (alternativa 3)

La alternativa 3 es la más cercana. Cubre bien el buffer de entrada, los reintentos y el aislamiento de inválidos. La ventaja del log está en lo que ocurre después de confirmar un mensaje:

- **Replay del trabajo.** Si un adaptador interpreta mal un lote de notificaciones, una vez corregido se pueden reprocesar las notificaciones retenidas sin depender de que el proveedor las reenvíe.
- **Replay de eventos.** Si el read model del panel queda corrupto, o el Panel API estuvo caído más tiempo del esperado, puede releer los eventos retenidos. Fuera de la ventana de retención se reconstruye desde la base (B-5).
- **Consumidores futuros.** Un consumidor nuevo puede leer el historial retenido en lugar de empezar vacío, sin reconfigurar a los productores.

**Qué aporta y qué no.** Con la analítica fuera del log, hoy hay un solo consumidor por stream: los workers en el de trabajo y el Panel API en el de eventos. La ventaja sobre una cola descansa en el replay y en los consumidores futuros, no en un requisito actual. Si en la Iteración 2 el replay no resulta necesario, una cola durable es una alternativa válida, y el puerto de mensajería permite cambiar sin tocar el núcleo (B-11).

### 4.2 Por qué no la base de datos como bus (alternativa 2)

El outbox ya usa la base, pero solo como etapa de salida para no perder eventos. Usarla además como bus para el trabajo pendiente y los eventos concentra todo en el componente más caro de escalar: el polling de los workers y del panel compite con las escrituras de la ingesta y agrava el cuello de botella de la base compartida, y la base primaria pasa a ser el único camino de las notificaciones y de los eventos (QA-6). Además, las tablas crecerían sin límite o habría que purgarlas, con lo que se perdería el replay.

### 4.3 Por qué no comunicación síncrona directa (alternativa 1)

Es la más simple, pero obliga al receptor a procesar en línea antes de responder: el proveedor espera, reintenta o descarta, y una ráfaga se propaga como carga directa sobre los workers y la base. Si el panel está caído o lento, la ingesta falla o se frena. No hay buffer de picos ni replay, y un proveedor defectuoso o un consumidor lento pueden degradar al resto ( QA-5, QA-8).

### 4.4 Por qué no una plataforma de streaming completa (alternativa 5)

Es la mejor en QA, pero queda descartada por viabilidad, con el mismo razonamiento del ADR-0001 para los microservicios completos:

- Exige operar un cluster, con particionado y ajuste de consumidores, para un trabajo con plazo acotado.
- Su ventaja sobre la alternativa 4 (más escalabilidad y disponibilidad del propio broker) no se puede demostrar con un prototipo acotado, así que se pagaría el costo sin mostrar el beneficio.
- La evolución hacia ella no es una reescritura: el modelo de consumo (grupos y offsets) es conceptualmente el mismo, y el puerto de mensajería aísla el cambio en la capa de infraestructura.

## 5. Consecuencias

### Positivas

- Cobertura defendible de QA-1 y QA-5: buffer de entrada, buffer hacia el panel, replay dentro de la retención y aislamiento de mensajes inválidos.
- El receptor responde rápido a los proveedores y el procesamiento sigue el ritmo propio, respetando los límites de uso con reintentos y backoff.
- Si el Panel API se cae, los eventos esperan en el log y se procesan al volver. Si el broker se cae, el panel sigue respondiendo desde el read model con la última versión conocida: se degrada la frescura de los datos, no la disponibilidad del panel. La contracara es que la ingesta de notificaciones nuevas se interrumpe hasta que el broker vuelva (B-10).
- Los consumidores se pueden reiniciar, escalar o reconstruir sin coordinarse con los productores.
- El código refleja la documentación: un puerto de publicación y un puerto de consumo por desplegable.

### Negativas y riesgos aceptados

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| B-1 | Consistencia eventual y dos saltos entre la notificación y el panel | Parte del presupuesto de 5 minutos de QA-1 se consume en el camino. En el correo, también el de 15 minutos de QA-7. | Medir el retraso de consumo de cada stream con alertas y repartir el presupuesto por etapa (receptor, trabajo, outbox, eventos, read model). |
| B-2 | Entrega al menos una vez | Trabajos o eventos duplicados o reentregados. | Idempotencia en workers y en el Panel API (identificador y versión por viaje) y pruebas de reentrega. |
| B-3 | Orden solo por clave | No hay orden relativo entre mensajes de distintas claves. | Definir la clave en el contrato; no depender de un orden global. |
| B-4 | El log no recupera lo que el proveedor nunca entregó | QA-5 no se cumple solo con el log. | Los adaptadores reconcilian con el proveedor al restablecerse la API (eventos pendientes o estado actual). El log garantiza que lo ya recibido no se pierde ni se procesa a medias. |
| B-5 | Retención finita | El replay llega solo hasta el borde de la ventana. | La base relacional es la fuente de verdad. Procedimiento de reconstrucción del read model desde la base para lo que quede fuera de la ventana. |
| B-6 | Datos personales persistidos en el log (R-4) | Las notificaciones y los eventos pueden incluir identificadores de cuenta. Conflicto con borrados y con límites de residencia por región. | Mensajes mínimos con referencias, retención acotada, cifrado en reposo. Definir en el ADR de multi-región cómo se propaga un pedido de borrado. |
| B-7 | Un broker más para operar (ya registrado en el ADR-0001) | Complejidad operativa y un componente crítico más. | Broker gestionado o clusterizado en producción; una sola instancia en el prototipo. |
| B-8 | Los contratos de mensajes pasan a ser una API interna | Un cambio incompatible rompe a los consumidores. | Esquemas versionados con compatibilidad hacia atrás, definidos en el ADR de contratos. |
| B-9 | Trabajo encolado de cuentas que revocan el acceso después | Una lectura con token revocado violaría QA-10. | El worker consulta la bóveda al tomar cada trabajo y descarta el que corresponda a un acceso revocado. |
| B-10 | El broker queda en el camino de entrada | Si cae, el receptor no puede encolar notificaciones nuevas. | Broker clusterizado y multi-AZ (B-7). El receptor responde con error para que el proveedor reintente. Reconciliación con el proveedor al volver (B-4). QA-4 queda parcial. |
| B-11 | La ventaja del log sobre una cola depende de usar el replay | Si no se usa, se paga un log sin necesitarlo. | Revisar en la Iteración 2. El puerto de mensajería permite pasar a una cola durable sin tocar el núcleo. |

## 6. Decisión abierta: tecnología del broker

La Iteración 1 deja abierta la elección entre Redis Streams y RabbitMQ Streams. Ambas cumplen el modelo de log con offsets por consumidor.

## 7. Relación con otros ADR y próximos pasos

- **ADR-0001:** define C-lite y su regla de capas; este ADR justifica uno de sus componentes.
- **ADR-0002:** define la tabla outbox, que es el punto de entrada de los eventos al stream de eventos.
- **ADR-0004:** justifica que la analítica no use el broker y se alimente desde la base. Su evolución (consumir el stream de eventos) depende de este ADR.
- **ADR de tecnología del broker:** cerrar la sección 6 (Iteración 2).
- **ADR de contratos de mensajes y modelo canónico:** esquemas del stream de trabajo y del de eventos, versionado, claves de orden e identificadores (Iteración 2).
- **ADR de despliegue:** alta disponibilidad del broker y objetivo de failover (Iteración 3).
- **ADR de multi-región:** residencia de datos y borrado sobre el log (B-6).
