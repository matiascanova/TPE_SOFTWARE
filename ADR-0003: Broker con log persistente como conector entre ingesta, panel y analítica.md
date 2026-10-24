# ADR-0003: Broker con log persistente como conector de la ingesta
 
- **Estado:** Propuesto
- **Fecha:** 21/10/2026
- **Decisores:** Equipo de diseño (grupo TPE)
- **Drivers de entrada:** Análisis inicial y drivers (CU-01 a CU-03, QA-1,QA-5 a QA-7, R-2, R-6).
- **ADR relacionados:** ADR-0001 (estilo C-lite), ADR-0002 (workers y outbox), ADR-0004 (read model)
---
 
## 1. Contexto y problema
 
El ADR-0001 incorpora el broker con log persistente como parte de C-lite y remite a este documento para justificarlo, y la Iteración 1 lo registra entre los conceptos fundamentales sin ADR propio. Este documento cierra esa deuda y responde una pregunta: **¿Qué conector usa la ingesta: un log persistente u otra forma de comunicación?**
 
El punto de partida es el siguiente. Las notificaciones de proveedores de viaje y de proveedores de correo llegan por push a la ingesta. Un **receptor** las recibe, las encola y responde. Los **workers** las toman a su ritmo, las traducen al modelo canónico y guardan el cambio y un evento en la base (outbox: tabla de eventos pendientes que se guarda junto con el cambio, ADR-0002). Un **relay** (proceso que lee los eventos pendientes del outbox y los publica en el broker) los publica y el **Panel API** los consume para actualizar el read model (copia de los datos optimizada para las lecturas del panel, ADR-0004). El broker aparece entonces dos veces: como buffer de entrada (stream de trabajo) y como conector hacia el panel (stream de eventos).
 
**Tensiones:**
 
- **Ráfagas impredecibles en la entrada.** Una disrupción masiva (por ejemplo, el cierre de un aeropuerto) o una sincronización masiva de casillas de correo generan picos de notificaciones. Los proveedores esperan una respuesta rápida, mientras que procesar una notificación es más lento: hay que consultar al proveedor, leer su contenido e interpretarlo (QA-1, QA-6, QA-7).
- **Recuperación.** Tras una interrupción de un proveedor o del Panel API, el sistema debe ponerse al día sin perder lo ya recibido (QA-5).
Como en el ADR-0001, la viabilidad de implementación es un criterio de primer orden con plazo acotado (R-6).
 
**Fuera de alcance:** la tecnología concreta del broker, que sigue abierta (Redis Streams o RabbitMQ Streams; ambas cumplen el modelo de log con offsets por consumidor), el procesamiento de las notificaciones y el outbox (ADR-0002), el read model del panel (ADR-0004) y el diseño de la analítica, que no consume del log (iteraciones futuras).
 
---
 
## 2. Criterios de decisión
 
| Criterio | Qué mide en esta decisión |
|---|---|
| QA-1 | Que los cambios lleguen del origen al panel sin perderse y con poca demora. |
| QA-5 | Buffer, replay y aislamiento de mensajes inválidos y de proveedores defectuosos. |
| QA-6 / QA-7 | Absorber picos ×10 y escalar productores y consumidores por separado. |
| R-6 | Que se pueda implementar y demostrar en el plazo acotado. |
 
---
 
## 3. Alternativas consideradas
 
| ID | Alternativa | Resumen |
|---|---|---|
| CO1 | Comunicación síncrona directa | El receptor procesa en línea antes de responder y el worker llama directo al Panel API. No hay intermediario ni buffer. |
| CO2 | Base de datos como bus | El trabajo pendiente y los eventos pasan por tablas (cola de trabajo y outbox) que los consumidores leen por polling. No hay broker. |
| CO3 | Cola durable simple | Una cola para el trabajo pendiente y otra para los eventos (RabbitMQ clásico o similar). El mensaje se elimina al confirmarse. |
| CO4 | **Broker con log persistente liviano** | Los mensajes quedan ordenados y retenidos, y cada consumidor lleva su propio offset (su posición de lectura en el log). |
| CO5 | Plataforma de streaming completa | Kafka o un servicio gestionado equivalente. |
 
---
 
## 4. Decisión
 
**Se elige un broker con log persistente (CO4) como conector de la ingesta, con dos streams: uno de trabajo pendiente (receptor → workers) y otro de eventos (relay → Panel API). Cada consumidor lee con su propio offset y la entrega es al menos una vez, con consumidores idempotentes (reprocesar un mensaje no cambia el resultado).**
 
**Descripción de la decisión.** Un solo componente con un stream de trabajo y otro de eventos, para no sumar piezas. Cada stream tiene retención, mensajes inválidos y monitoreo propios, y agregar un consumidor no modifica a los productores.
 
- **Un broker detrás de un puerto de mensajería.** El dominio depende de puertos (publicar, consumir) y la tecnología vive en la capa de infraestructura, de modo que se puede cambiar sin tocar el núcleo (ADR-0001).
- **Entrega al menos una vez con idempotencia.** Los workers son idempotentes (reprocesar la misma notificación no duplica la reserva, ADR-0002) y el Panel API ignora duplicados y eventos viejos: relee el estado actual y escribe solo si la versión es mayor o igual a la guardada (ADR-0004).
- **Orden por clave.** En el stream de eventos, el identificador de viaje. En el stream de trabajo, la cuenta o la reserva a la que refiere la notificación (a definir en el contrato). No hay orden global.
- **Retención y mensajes inválidos.** Cada stream conserva sus mensajes durante una ventana configurable, que debe cubrir como mínimo la interrupción más larga que se quiera absorber (valor a definir). El log no es la fuente de verdad: lo es la base relacional. Los mensajes inválidos y el trabajo cuyos reintentos se agotaron se derivan a un stream separado y se registran, sin frenar el consumo del resto.
- **Acceso cruzado solo por eventos.** Los desplegables no se llaman entre sí: la ingesta comunica los cambios al panel solo por eventos. Cada módulo es dueño de sus tablas en la base compartida (ADR-0001, ES-3).
### 4.1 Cómo funciona
 
- **Receptor.** Verifica la autenticidad de la notificación, la encola y responde. No procesa nada más, para que la respuesta al proveedor sea rápida. Si no puede encolar, responde con error para que el proveedor reintente.
- **Workers.** Toman trabajo del stream a su ritmo, con reintentos y backoff (esperas crecientes entre reintentos) por proveedor. Escalan agregando workers al mismo grupo de consumo. Traducen al modelo canónico (R-2) y guardan el cambio y el evento en la base en una transacción (ADR-0002).
- **Relay y Panel API.** El relay publica en el stream de eventos los eventos pendientes del outbox. El Panel API consume ese stream con su propio offset para actualizar el read model (ADR-0004).
### 4.2 Por qué no una cola durable (CO3)
 
CO3 es la alternativa más cercana. Cubre bien el buffer de entrada, los reintentos y el aislamiento de inválidos. La ventaja del log está en lo que ocurre después de confirmar un mensaje:
 
- **Replay del trabajo.** Si un adaptador interpreta mal un lote de notificaciones, una vez corregido se pueden reprocesar las notificaciones retenidas sin depender de que el proveedor las reenvíe.
- **Replay de eventos.** Si el read model del panel queda corrupto, o el Panel API estuvo caído más tiempo del esperado, puede releer los eventos retenidos. Fuera de la ventana de retención se reconstruye desde la base.
- **Consumidores futuros.** Un consumidor nuevo puede leer el historial retenido en lugar de empezar vacío, sin reconfigurar a los productores.
**Qué aporta y qué no.** La analítica queda fuera del log (ver Fuera de alcance), así que hoy hay un solo consumidor por stream: los workers en el de trabajo y el Panel API en el de eventos. La ventaja sobre una cola descansa en el replay y en los consumidores futuros, no en un requisito actual. Si en la Iteración 2 el replay no resulta necesario, una cola durable es una alternativa válida, y el puerto de mensajería permite cambiar sin tocar el núcleo (BR-5).
 
### 4.3 Por qué no la base de datos como bus (CO2)
 
El outbox ya usa la base, pero solo como etapa de salida para no perder eventos. Usarla además como bus para el trabajo pendiente y los eventos concentra todo en el componente más caro de escalar: el polling de los workers y del panel compite con las escrituras de la ingesta y agrava el cuello de botella de la base compartida, y la base primaria pasa a ser el único camino de las notificaciones y de los eventos (QA-6). Además, las tablas crecerían sin límite o habría que purgarlas, con lo que se perdería el replay.
 
### 4.4 Por qué no comunicación síncrona directa (CO1)
 
Es la más simple, pero obliga al receptor a procesar en línea antes de responder: el proveedor espera, reintenta o descarta, y una ráfaga se propaga como carga directa sobre los workers y la base. Si el panel está caído o lento, la ingesta falla o se frena. No hay buffer de picos ni replay, y un proveedor defectuoso o un consumidor lento pueden degradar al resto (QA-5).
 
### 4.5 Por qué no una plataforma de streaming completa (CO5)
 
Es la mejor en QA, pero queda descartada por viabilidad (R-6), con el mismo razonamiento del ADR-0001 para los microservicios completos:
 
- Exige operar un cluster, con particionado y ajuste de consumidores, para un trabajo con plazo acotado.
- Su ventaja sobre CO4 (más escalabilidad y disponibilidad del propio broker) no se puede demostrar con un prototipo acotado, así que se pagaría el costo sin mostrar el beneficio.
- La evolución hacia ella no es una reescritura: el modelo de consumo (grupos y offsets) es conceptualmente el mismo, y el puerto de mensajería aísla el cambio en la capa de infraestructura.
---
 
## 5. Consecuencias
 
### Positivas
 
- Cobertura defendible de QA-1 y QA-5: buffer de entrada, buffer hacia el panel, replay dentro de la retención y aislamiento de mensajes inválidos.
- El receptor responde rápido a los proveedores y el procesamiento sigue el ritmo propio, respetando los límites de uso con reintentos y backoff.
- Si el Panel API se cae, los eventos esperan en el log y se procesan al volver. Si el broker se cae, el panel sigue respondiendo desde el read model con la última versión conocida: se degrada la frescura de los datos, no la disponibilidad del panel. La contracara es que la ingesta de notificaciones nuevas se interrumpe hasta que el broker vuelva (BR-4).
- Los consumidores se pueden reiniciar, escalar o reconstruir sin coordinarse con los productores.
- El código refleja la documentación: un puerto de publicación y un puerto de consumo por desplegable.
### Negativas y riesgos aceptados
 
| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| BR-1 | Consistencia eventual y dos saltos entre la notificación y el panel | Parte del presupuesto de 5 minutos de QA-1 se consume en el camino en las notificaciones de proveedores de viaje. En el correo, el camino consume el presupuesto de 15 minutos de QA-7, que se mide para el 95 % de los usuarios con viajes próximos. | Medir el retraso de consumo de cada stream con alertas y repartir el presupuesto por etapa (receptor, trabajo, outbox, eventos, read model). |
| BR-2 | Entrega al menos una vez y orden solo por clave | Trabajos o eventos duplicados o reentregados. No hay orden relativo entre mensajes de distintas claves. | Idempotencia en workers (versión del viaje) y en el Panel API (escritura condicional por versión, ADR-0004) y pruebas de reentrega. Definir la clave en el contrato y no depender de un orden global. |
| BR-3 | El log no recupera lo que el proveedor nunca entregó | QA-5 no se cumple solo con el log. | Los adaptadores reconcilian con el proveedor al restablecerse la API, consultando eventos pendientes o estado actual (ADR-0002). El log garantiza que lo ya recibido no se pierde ni se procesa a medias. |
| BR-4 | El broker es un componente más para operar y queda en el camino de entrada | Complejidad operativa y un componente crítico más. Si cae, el receptor no puede encolar notificaciones nuevas (QA-4). | Broker gestionado o clusterizado y multi-AZ en producción; una sola instancia en el prototipo. El receptor responde con error para que el proveedor reintente. Reconciliación con el proveedor al volver (BR-3). |
| BR-5 | La ventaja del log sobre una cola depende de usar el replay | Si no se usa, se paga un log sin necesitarlo. | Revisar en la Iteración 2. El puerto de mensajería permite pasar a una cola durable sin tocar el núcleo. |
