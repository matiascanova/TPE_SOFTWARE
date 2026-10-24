# ADR-0001: Estilo C-lite: capas/hexagonal con tres desplegables

- **Estado:** Propuesto
- **Decisores:** Equipo de diseño (grupo TPE)
- **Drivers de entrada:** [Análisis inicial y drivers](Analisis-Inicial-y-Drivers.md) (CU-01 a CU-04; QA-1, QA-2, QA-5, QA-6, QA-7, QA-8; R-2, R-6; CRN-1, CRN-3)
- **ADR relacionados:** ADR-0002 (workers y outbox), ADR-0003 (broker), ADR-0004 (read model)

---

## 1. Contexto y problema

Hay que elegir el estilo arquitectónico general de Trotamundos antes de empezar las iteraciones de ADD. La decisión condiciona todas las demás: broker, almacenamiento, despliegue y seguridad.

Este documento responde una pregunta:

1. **¿Qué estilo arquitectónico general adopta Trotamundos?**

El sistema no es difícil por el tráfico de lectura del panel: 2 M de usuarios activos semanales con picos ×10 se resuelven con caché y un read model (copia de los datos optimizada para las lecturas del panel, ADR-0004). La dificultad está en tres tensiones.

**Tensiones:**

- **Ingesta.** El correo de usuarios (15 M de cuentas, QA-7) y los eventos de proveedores heterogéneos (QA-1, QA-5, QA-8) llegan con picos impredecibles. Cómo se encolan las notificaciones se decide en ADR-0003 y cómo se procesan en ADR-0002.
- **Aislamiento de la analítica.** El CRN-3 exige aislar la analítica del sistema operativo. Esta iteración define que queda aislada y sin acceso a la base operacional; el mecanismo se completa en iteraciones futuras.
- **Trazabilidad entre arquitectura y prototipo.** Con plazo acotado, que el código sea consistente con la arquitectura documentada y se pueda construir a tiempo es un criterio de primer orden (R-6).

**Fuera de alcance:** el broker y sus streams (ADR-0003), los workers y el outbox (ADR-0002), el read model del panel (ADR-0004), el motor de la base (decisión de almacenamiento posterior (futuro ADR), Iteración 3), el despliegue y la seguridad (decisiones posteriores) y el diseño interno de la analítica (iteraciones futuras).

---

## 2. Criterios de decisión

| Criterio | Qué mide en esta decisión |
|---|---|
| QA-1 | Que una actualización de un proveedor llegue al panel en menos de 5 minutos. |
| QA-2 | Latencia del panel web, también en picos. |
| QA-5 | Que el sistema siga mostrando la última versión conocida ante fallas de proveedores y recupere el 99 % de los eventos tras la interrupción. |
| QA-6 / QA-7 | Escalar las lecturas del panel con picos ×10 (QA-6) y el procesamiento del correo de 15 M de cuentas (QA-7). |
| QA-8 | Que un proveedor defectuoso no interrumpa la integración con los demás. |
| R-2 | Un adaptador por proveedor que traduce al modelo canónico. |
| R-6 | Que el prototipo se pueda construir y demostrar en el plazo acotado, con código consistente con la arquitectura documentada. |
| CRN-1 | Definir la estructura global del sistema. |
| CRN-3 | Que la analítica quede aislada del sistema operativo. |

---

## 3. Decisión

### 3.1 Alternativas consideradas

| ID | Alternativa | Resumen |
|---|---|---|
| A | Monolito modular | Una aplicación con módulos internos, N réplicas, base relacional con réplicas de lectura, Redis y cola simple. |
| B | Microservicios completos | Un servicio por capacidad, base por servicio, Kafka, CQRS (modelo de escritura separado del de lectura), saga, gateway, service mesh y multi-región. |
| C | Híbrida (5-6 servicios) | Servicios de granularidad media agrupados por patrón de carga, orientados a eventos, con CQRS solo en el panel. |
| D | Serverless | Funciones disparadas por colas y webhooks, API Gateway y NoSQL gestionado. |
| E | Capas / N-tier | Capas lógicas estrictas desplegadas como tiers (web/API, workers, datos). Un solo código, varios roles. |
| **C-lite** | **Capas/hexagonal con tres desplegables** | Capas con puertos y adaptadores hacia afuera. Desplegables: Panel API, ingesta y analítica. Broker con log persistente y outbox (los eventos se guardan junto con el cambio y se publican después) entre la ingesta y el panel. |

### 3.2 Decisión

**Se elige C-lite: arquitectura en capas/hexagonal con tres desplegables (Panel API, ingesta y analítica), con la ingesta y el Panel API comunicados por un broker con log persistente y un outbox (ADR-0003, ADR-0002).**

**Descripción de la decisión.** C-lite organiza cada desplegable en capas/hexagonal y separa el trabajo de ingesta del de lectura del panel mediante un broker con log persistente y un outbox. El código refleja la arquitectura documentada casi uno a uno (R-6).

- **Capas en cada desplegable.** Presentación, aplicación, dominio e infraestructura. El dominio depende de puertos y la tecnología vive en infraestructura.
- **Adaptadores por proveedor y por medio de transporte.** Traducen al modelo canónico (R-2). El procesamiento se detalla en ADR-0002.
- **Ingesta con tres roles en un mismo artefacto.** Un receptor liviano que recibe las notificaciones, workers que las procesan y un relay (proceso que lee los eventos pendientes del outbox y los publica en el broker). Corren como procesos separados y escalan por separado. Para el correo se suma un planificador de sondeo, cuyo diseño queda para la Iteración 2.
- **Flujo entre ingesta y Panel API.** Receptor → stream de trabajo → workers → base (cambio y evento en el outbox) → relay → stream de eventos → Panel API, que actualiza su read model.
- **Analítica aislada.** Queda fuera del flujo de ingesta y del panel y no accede a la base operacional ni al log: trabaja sobre un almacén analítico propio, alimentado con una copia periódica de los registros (CU-05 del análisis inicial). El diseño detallado se completa en iteraciones futuras.
- **Relativa simpleza.**El lapso de tiempo con el que se cuenta para presentar un prototipo funcional del sistema trotamundos tiene como consecuencia que la complejidad de la arquitectura queda acotada al tiempo con el que se cuenta para presentar dicho prototipo, limitando la implementacion de arquitecturas que cuentan con mayor complejidad. Este punto sera mas desarrollado en el la seccion 3.4

**Tácticas que incorpora de otras alternativas:** de E, la organización interna en capas (trazabilidad y testabilidad). De B y C, el buffer de entrada y de picos con replay por log persistente, el outbox transaccional, CQRS solo en el panel, circuit breaker, reintentos con backoff, cola de mensajes inválidos y el escalado independiente de la ingesta.

**Tácticas que difiere:** separar notificaciones y procesador de itinerarios en servicios propios, y separar la ingesta de correo de la de proveedores (ver 3.4). Quedan como evolución prevista, sin reescritura, porque los límites internos ya están modelados como módulos con puertos.

### 3.4 Por qué no las otras alternativas

- **E (capas / N-tier).** Es la más cercana y la más parecida en implementación, pero incumple QA-1, QA-5, QA-2 y QA-6/7:
  - No incluye un log persistente ni un outbox por defecto. Sin ellos el replay tras una caída del proveedor es limitado, el requisito de 5 minutos queda expuesto a pérdidas y el 99 % de recuperación de QA-5 es difícil de garantizar. Si se le agregan ambos, E pasa a ser C-lite.
  - El panel, la ingesta y la analítica comparten el modelo y la base de escritura, así que las lecturas del panel hacen joins sobre ese modelo y la presión de la ingesta se siente en la base (QA-2, QA-6/7). C-lite separa el read model (ADR-0004).
  - El costo adicional de C-lite sobre E es pequeño: un broker (ADR-0003) y un outbox (ADR-0002).
- **B (microservicios completos).** Es la mejor en calidad pero la peor en implementabilidad (R-6):
  - Exige consistencia eventual en todas partes, trazabilidad distribuida, varios pipelines y mucha infraestructura (service mesh, saga, multi-región) para un plazo acotado.
  - Implementar solo un subconjunto agrava la trazabilidad: cuanto más grande es la arquitectura documentada respecto del código, más difícil es defender que son consistentes.
  - Su ventaja en QA-6 (aislamiento fino por servicio) no se puede demostrar con un prototipo acotado: se pagaría el costo sin mostrar el beneficio.
- **C (híbrida de 5-6 servicios).** Cubre QA-1, QA-5 y QA-6/7 igual que C-lite, pero implica 5 o 6 desplegables con contratos propios, outbox, CQRS sincronizado por eventos, circuit breakers, cola de mensajes inválidos, simuladores y observabilidad de flujos que cruzan varios servicios. Eso aumenta la dificultad de implementación (R-6).
  - Los servicios que C separa y C-lite no (notificaciones y procesador de itinerarios) escalan con patrones de carga parecidos a los del Panel API y los workers. Separarlos hoy agrega costo de integración sin mejorar un QA que condicione esta decisión.
  - La ingesta de correo y la de proveedores tampoco se separan: los adaptadores aíslan colas, pools y circuit breaker dentro del worker (QA-8) y la ingesta escala agregando instancias al mismo grupo de consumo (ADR-0002). Separarlas sumaría un desplegable con contrato propio sin mejorar un QA.
- **D (serverless).** Incumple R-6 y amenaza QA-2:
  - Acopla el sistema a un proveedor cloud, exige cuenta cloud para todo el grupo y arrastra riesgo de costos.
  - Los arranques en frío amenazan QA-2 (mitigable con concurrencia provisionada, a mayor costo).
  - Es difícil de ejecutar y demostrar localmente, lo que debilita la trazabilidad del prototipo.
- **A (monolito modular).** Es la más simple, pero incumple QA-1, QA-5 y QA-6: la ingesta compite con el panel por recursos, un despliegue defectuoso tira todo, no hay log para replay y se escala todo junto. Se construye muy rápido, pero habría que reconocer en el diseño que esos tres drivers no se cumplen.

---

## 4. Consecuencias

### Positivas

- Cobertura defendible de QA-1, QA-5, QA-6 y QA-7: buffer de entrada, buffer de picos y replay (QA-1, QA-5) e ingesta escalable por separado (QA-6, QA-7).
- El código refleja la documentación casi uno a uno: tres desplegables, capas visibles en cada uno y adaptadores por proveedor (R-2).
- Es implementable por un grupo con plazo acotado (R-6): tres desplegables y cuatro piezas de apoyo (base, broker, Redis para el read model y almacén analítico), ejecutables con docker-compose y proveedores simulados.
- La evolución a C o B no es una reescritura: los límites internos ya están modelados como módulos con puertos.

### Negativas y riesgos aceptados

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| ES-1 | Radio de impacto mayor en la ingesta: un adaptador defectuoso puede afectar a los workers. | Puede interrumpir la integración con el resto de los proveedores (QA-8). | Aislamiento por adaptador dentro del worker: colas, pools y circuit breaker por proveedor (ADR-0002). |
| ES-2 | Base relacional compartida entre Panel API, workers y la copia hacia la analítica. | Cuello de botella de escritura a 15 M de cuentas (QA-7). | Read model separado para las lecturas (ADR-0004). La copia analítica se toma desde una réplica o en ventanas controladas. Motor y particionado: decisión de almacenamiento posterior. |
| ES-3 | Un broker más para operar. | Complejidad operativa y un nuevo componente crítico. | Broker gestionado o clusterizado en producción; una sola instancia en el prototipo (ADR-0003). |

