# ADR-0003: Broker con log persistente como conector de la ingesta

- **Estado:** Aceptado
- **Drivers de entrada:** [Análisis inicial y drivers](Analisis-Inicial-y-Drivers.md) (CU-01, CU-02, CU-03; QA-1, QA-4, QA-5, QA-6, QA-7; R-2, R-6)

---

## 1. Contexto

La ingesta recibe notificaciones push de proveedores de viaje y de correo, y además sondea el correo de forma programada. Un receptor las encola, los workers las procesan (ADR-0002) y un relay publica los eventos que consume el Panel API (ADR-0004), así que el broker cumple dos funciones: buffer de entrada (stream de trabajo) y conector hacia el panel (stream de eventos). Una disrupción masiva o una sincronización masiva de casillas generan picos que el proveedor espera ver respondidos rápido y que procesar no permite (QA-1, QA-6, QA-7). Tras la interrupción de un proveedor o del Panel API, el sistema debe ponerse al día sin perder lo recibido (QA-5), y la caída del broker no debe dejar al panel sin servicio (QA-4). Todo debe poder implementarse en el plazo acotado (R-6). El ADR-0001 incorpora el broker en C-lite y remite a este documento.

**Pregunta:** ¿Qué conector usa la ingesta: un log persistente u otra forma de comunicación?

**Fuera de alcance:** la tecnología concreta del broker, el procesamiento de las notificaciones y el outbox (ADR-0002), el pipeline de correo y el planificador de sondeo (Iteración 2), el read model (ADR-0004) y la analítica, que no consume del log.

---

## 2. Decisión

**Se elige un broker con log persistente como conector de la ingesta, con dos streams: uno de trabajo (receptor y planificador de sondeo → workers) y otro de eventos (relay → Panel API). Cada consumidor lleva su propio offset (su posición de lectura en el log).**

- La entrega es al menos una vez, con consumidores idempotentes y orden por clave: el viaje en el stream de eventos y la cuenta o reserva a la que refiere la notificación en el de trabajo (a definir en el contrato), sin orden global.
- Cada stream retiene sus mensajes al menos lo que dura la interrupción más larga que se quiera absorber (valor a definir) y deriva los mensajes inválidos y el trabajo con reintentos agotados a un stream separado, sin frenar el consumo. El log no es la fuente de verdad: lo es la base relacional.
- El dominio usa el broker detrás de un puerto de mensajería, y los desplegables se comunican entre sí solo por eventos.

### Alternativas descartadas

| Alternativa | Motivo |
|---|---|
| Base de datos como bus | Incumple QA-6 y QA-5: el polling de workers y panel compite con las escrituras de la ingesta en la base primaria, que pasa a ser el único camino de notificaciones y eventos. Las tablas crecen sin límite o se purgan, y se pierde el replay. |
| Cola durable simple (RabbitMQ clásico o similar) | Es la más cercana: cubre buffer, reintentos y aislamiento de inválidos. Pierde en QA-5 porque el mensaje se elimina al confirmarse: no hay replay para reprocesar un lote mal interpretado ni para reconstruir un read model corrupto, y un consumidor futuro no puede leer el historial. Hoy hay un solo consumidor por stream, así que la ventaja depende del replay (BR-4). Si no resulta necesario, es una alternativa válida y el puerto permite cambiar sin tocar el núcleo. |
| Plataforma de streaming completa (Kafka o servicio gestionado) | Incumple R-6: exige operar un cluster con particionado y ajuste de consumidores, y su ventaja (escala y disponibilidad del propio broker) no se puede demostrar con un prototipo acotado. Queda como evolución: el modelo de consumo (grupos y offsets) es el mismo y el puerto aísla el cambio en infraestructura. |

---

## 3. Consecuencias

**Positivas**

- Buffer de entrada, buffer hacia el panel, replay dentro de la retención y aislamiento de mensajes inválidos dan una cobertura defendible de QA-1 y QA-5.
- El receptor responde rápido a los proveedores y el procesamiento sigue su ritmo, respetando sus límites de uso con reintentos y backoff (QA-6, QA-7).
- Si el Panel API se cae, los eventos esperan en el log. Si el broker se cae, el panel sigue respondiendo desde el read model con la última versión conocida (QA-4).
- Los consumidores se pueden reiniciar, escalar o reconstruir sin coordinarse con los productores (QA-6).
- El código refleja la documentación: un puerto de publicación y uno de consumo por desplegable (R-6).

**Negativas y riesgos aceptados**

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| BR-1 | Consistencia eventual y dos saltos entre la notificación y el panel. | Consume parte de los 5 minutos de QA-1 y, en el correo, de los 15 minutos de QA-7. | Medir el retraso de consumo de cada stream con alertas y repartir el presupuesto por etapa (receptor, trabajo, outbox, eventos, read model). |
| BR-2 | El log no recupera lo que el proveedor nunca entregó. | QA-5 no se cumple solo con el log. | Los adaptadores reconcilian con el proveedor (ADR-0002). El log garantiza que lo recibido no se pierde ni se procesa a medias. |
| BR-3 | El broker está en el camino de entrada y su caída detiene la ingesta. | El receptor no puede encolar notificaciones nuevas hasta que vuelva. El panel sigue sirviendo desde el read model (QA-4). | Broker gestionado o clusterizado y multi-AZ en producción. El receptor responde con error para que el proveedor reintente, y al volver se reconcilia (BR-2). |
| BR-4 | La ventaja del log sobre una cola depende de usar el replay. | Si no se usa, se paga un log sin necesitarlo. | El puerto de mensajería permite pasar a una cola durable sin tocar el núcleo. |
