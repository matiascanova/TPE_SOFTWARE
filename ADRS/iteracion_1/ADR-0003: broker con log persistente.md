# ADR-0003: Broker con log persistente como conector de la ingesta

- **Estado:** Propuesto
- **Decisores:** Equipo de diseño (grupo TPE)
- **Drivers de entrada:** [Análisis inicial y drivers](Analisis-Inicial-y-Drivers.md) (CU-01, CU-02, CU-03; QA-1, QA-4, QA-5, QA-6, QA-7; R-2, R-6)
- **ADR relacionados:** ADR-0001 (estilo C-lite), ADR-0002 (workers y outbox), ADR-0004 (read model)

---

## 1. Contexto y problema

El ADR-0001 incorpora el broker con log persistente como parte de C-lite y remite a este documento para justificarlo. Este documento responde una pregunta:

1. **¿Qué conector usa la ingesta: un log persistente u otra forma de comunicación?**

El punto de partida es el siguiente. Las notificaciones de proveedores de viaje llegan por push a la ingesta. El correo llega por push cuando el proveedor de correo lo ofrece (webhooks o notificaciones de Gmail u Outlook) y además se sondea de forma programada, como pide el enunciado; el planificador de sondeo encola trabajo en el mismo stream (su diseño se define en el pipeline de correo, Iteración 2). Un **receptor** recibe las notificaciones push, las encola y responde. Los **workers** las toman a su ritmo, las traducen al modelo canónico y guardan el cambio y un evento en la base (outbox: tabla de eventos pendientes que se guarda junto con el cambio, ADR-0002). Un **relay** (proceso que lee los eventos pendientes del outbox y los publica en el broker) los publica y el **Panel API** los consume para actualizar el read model (copia de los datos optimizada para las lecturas del panel, ADR-0004). El broker aparece entonces dos veces: como buffer de entrada (stream de trabajo) y como conector hacia el panel (stream de eventos).

**Tensiones:**

- **Ráfagas impredecibles en la entrada.** Una disrupción masiva (por ejemplo, el cierre de un aeropuerto) o una sincronización masiva de casillas generan picos de notificaciones. Los proveedores esperan una respuesta rápida, mientras que procesar una notificación es más lento: hay que consultar al proveedor, leer su contenido e interpretarlo (QA-1, QA-6, QA-7).
- **Recuperación.** Tras una interrupción de un proveedor o del Panel API, el sistema debe ponerse al día sin perder lo ya recibido (QA-5).
- **El broker queda en el camino de entrada.** Su caída no debe dejar al panel sin servicio (QA-4).

Como en el ADR-0001, la viabilidad de implementación es un criterio de primer orden con plazo acotado (R-6).

**Fuera de alcance:** la tecnología concreta del broker, el procesamiento de las notificaciones y el outbox (ADR-0002), el pipeline de correo y el planificador de sondeo (Iteración 2), el read model del panel (ADR-0004) y el diseño de la analítica, que no consume del log.

---

## 2. Criterios de decisión

| Criterio | Qué mide en esta decisión |
|---|---|
| QA-1 | Que los cambios lleguen del origen al panel sin perderse y con poca demora. |
| QA-4 | Que la caída del broker o del Panel API no deje al panel sin servicio. |
| QA-5 | Buffer, replay y aislamiento de mensajes inválidos y de proveedores defectuosos. |
| QA-6 / QA-7 | Absorber picos ×10 y escalar productores y consumidores por separado. |
| R-6 | Que se pueda implementar y demostrar en el plazo acotado. |

---

## 3. Decisión

### 3.1 Alternativas consideradas

| ID | Alternativa | Resumen |
|---|---|---|
| CO1 | Comunicación síncrona directa | El receptor procesa en línea antes de responder y el worker llama directo al Panel API. No hay intermediario ni buffer. |
| CO2 | Base de datos como bus | El trabajo pendiente y los eventos pasan por tablas (cola de trabajo y outbox) que los consumidores leen por polling. No hay broker. |
| CO3 | Cola durable simple | Una cola para el trabajo pendiente y otra para los eventos (RabbitMQ clásico o similar). El mensaje se elimina al confirmarse. |
| **CO4** | **Broker con log persistente liviano** | Los mensajes quedan ordenados y retenidos, y cada consumidor lleva su propio offset (su posición de lectura en el log). |
| CO5 | Plataforma de streaming completa | Kafka o un servicio gestionado equivalente. |

### 3.2 Decisión

**Se elige CO4: un broker con log persistente como conector de la ingesta, con dos streams, uno de trabajo pendiente (receptor y planificador → workers) y otro de eventos (relay → Panel API). Cada consumidor lee con su propio offset y la entrega es al menos una vez, con consumidores idempotentes (reprocesar un mensaje no cambia el resultado).**

**Descripción de la decisión.** Un solo componente con un stream de trabajo y otro de eventos, para no sumar piezas. Cada stream tiene retención, mensajes inválidos y monitoreo propios, y agregar un consumidor no modifica a los productores.

- **Un broker detrás de un puerto de mensajería.** El dominio depende de puertos (publicar, consumir) y la tecnología vive en la capa de infraestructura, de modo que se puede cambiar sin tocar el núcleo (ADR-0001).
- **Entrega al menos una vez con idempotencia.** Los workers deduplican por identificador de notificación o versión del proveedor (ADR-0002) y el Panel API ignora duplicados y eventos viejos: relee el estado actual y escribe solo si la versión es mayor o igual a la guardada (ADR-0004).
- **Orden por clave.** En el stream de eventos, el identificador de viaje. En el stream de trabajo, la cuenta o la reserva a la que refiere la notificación (a definir en el contrato). No hay orden global.
- **Retención y mensajes inválidos.** Cada stream conserva sus mensajes durante una ventana que debe cubrir como mínimo la interrupción más larga que se quiera absorber (valor a definir). El log no es la fuente de verdad: lo es la base relacional. Los mensajes inválidos y el trabajo cuyos reintentos se agotaron se derivan a un stream separado y se registran, sin frenar el consumo del resto.
- **Acceso cruzado solo por eventos.** Los desplegables no se llaman entre sí: la ingesta comunica los cambios al panel solo por eventos.

### 3.3 Cómo funciona

- **Receptor.** Verifica la autenticidad de la notificación, la encola y responde. No procesa nada más, para que la respuesta al proveedor sea rápida. Si no puede encolar, responde con error para que el proveedor reintente.
- **Workers.** Toman trabajo del stream a su ritmo, con reintentos y backoff (esperas crecientes entre reintentos) por proveedor. Escalan agregando workers al mismo grupo de consumo. Traducen al modelo canónico (R-2) y guardan el cambio y el evento en la base en una transacción (ADR-0002).
- **Relay y Panel API.** El relay publica en el stream de eventos los eventos pendientes del outbox. El Panel API consume ese stream con su propio offset para actualizar el read model (ADR-0004).

| Qué falla | Qué pasa | Resultado |
|---|---|---|
| El Panel API se cae. | Los eventos esperan en el log y se procesan al volver. | El panel conserva la última versión conocida y se pone al día al volver. |
| El broker se cae. | El panel sigue respondiendo desde el read model. El receptor no puede encolar y responde con error. | Se degrada la frescura, no la disponibilidad del panel. La ingesta de notificaciones nuevas se interrumpe hasta que el broker vuelva (BR-4). |
| Un adaptador interpreta mal un lote de notificaciones. | Una vez corregido, se reprocesan las notificaciones retenidas. | No hace falta que el proveedor las reenvíe. |

### 3.4 Por qué no las otras alternativas

- **CO3 (cola durable simple).** Es la alternativa más cercana. Cubre bien el buffer de entrada, los reintentos y el aislamiento de inválidos. Pierde frente a CO4 en lo que ocurre después de confirmar un mensaje (QA-5):
  - **Replay del trabajo.** Si un adaptador interpreta mal un lote, una vez corregido se pueden reprocesar las notificaciones retenidas sin depender de que el proveedor las reenvíe.
  - **Replay de eventos.** Si el read model queda corrupto o el Panel API estuvo caído más de lo esperado, puede releer los eventos retenidos. Fuera de la ventana de retención se reconstruye desde la base.
  - **Consumidores futuros.** Un consumidor nuevo puede leer el historial retenido en lugar de empezar vacío, sin reconfigurar a los productores.
  - **Qué aporta y qué no.** La analítica queda fuera del log, así que hoy hay un solo consumidor por stream. La ventaja sobre una cola descansa en el replay y en los consumidores futuros, no en un requisito actual. Si en la Iteración 2 el replay no resulta necesario, una cola durable es una alternativa válida y el puerto de mensajería permite cambiar sin tocar el núcleo (BR-5).
- **CO2 (base de datos como bus).** Incumple QA-6 y QA-5. El outbox ya usa la base, pero solo como etapa de salida. Usarla además como bus concentra todo en el componente más caro de escalar: el polling de los workers y del panel compite con las escrituras de la ingesta, y la base primaria pasa a ser el único camino de las notificaciones y los eventos. Además, las tablas crecerían sin límite o habría que purgarlas, con lo que se perdería el replay.
- **CO1 (comunicación síncrona directa).** Incumple QA-1, QA-5 y QA-6. Obliga al receptor a procesar en línea antes de responder: el proveedor espera, reintenta o descarta, y una ráfaga se propaga como carga directa sobre los workers y la base. Si el panel está caído o lento, la ingesta falla o se frena. No hay buffer de picos ni replay, y un proveedor defectuoso o un consumidor lento pueden degradar al resto.
- **CO5 (plataforma de streaming completa).** Es la mejor en calidad, pero incumple R-6, con el mismo razonamiento que el ADR-0001 aplica a microservicios completos:
  - Exige operar un cluster, con particionado y ajuste de consumidores, para un plazo acotado.
  - Su ventaja sobre CO4 (más escalabilidad y disponibilidad del propio broker) no se puede demostrar con un prototipo acotado.
  - Queda como evolución: el modelo de consumo (grupos y offsets) es conceptualmente el mismo y el puerto de mensajería aísla el cambio en la capa de infraestructura.

---

## 4. Consecuencias

### Positivas

- Cobertura defendible de QA-1 y QA-5: buffer de entrada, buffer hacia el panel, replay dentro de la retención y aislamiento de mensajes inválidos.
- El receptor responde rápido a los proveedores y el procesamiento sigue su ritmo propio, respetando los límites de uso con reintentos y backoff (QA-6, QA-7).
- Si el Panel API se cae, los eventos esperan en el log. Si el broker se cae, el panel sigue respondiendo desde el read model con la última versión conocida (QA-4).
- Los consumidores se pueden reiniciar, escalar o reconstruir sin coordinarse con los productores.
- El código refleja la documentación: un puerto de publicación y un puerto de consumo por desplegable (R-6).

### Negativas y riesgos aceptados

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| BR-1 | Consistencia eventual y dos saltos entre la notificación y el panel. | Parte del presupuesto de 5 minutos de QA-1 se consume en el camino. En el correo, también el de 15 minutos de QA-7. | Medir el retraso de consumo de cada stream con alertas y repartir el presupuesto por etapa (receptor, trabajo, outbox, eventos, read model). |
| BR-2 | El log no recupera lo que el proveedor nunca entregó. | QA-5 no se cumple solo con el log. | Los adaptadores reconcilian con el proveedor al restablecerse la API, consultando eventos pendientes o estado actual (ADR-0002). El log garantiza que lo ya recibido no se pierde ni se procesa a medias. |
| BR-3 | Si cae, el receptor no puede encolar notificaciones nuevas (QA-4). | Broker gestionado o clusterizado y multi-AZ en producción. El receptor responde con error para que el proveedor reintente. Reconciliación con el proveedor al volver (BR-3). |
| BR-4 | La ventaja del log sobre una cola depende de usar el replay. | Si no se usa, se paga un log sin necesitarlo. | El puerto de mensajería permite pasar a una cola durable sin tocar el núcleo. |
