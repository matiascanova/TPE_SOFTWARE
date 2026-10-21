# ADR-0001: Estilo arquitectónico de Trotamundos

- **Estado:** Propuesto
- **Fecha:** 19/10/2026 (versión 3, ver historial al final)
- **Decisores:** Equipo de diseño (grupo TPE)
- **Drivers de entrada:** [Análisis inicial y drivers](Analisis-Inicial-y-Drivers.md) (CU-01 a CU-04, QA-1,QA-2,QA-3,QA-5,QA-6,QA-7, R-2,R-6,CRN-1,CRN-3)

---

## 1. Contexto y problema

Hay que elegir el estilo arquitectónico general de Trotamundos antes de empezar las iteraciones de ADD. La decisión condiciona todas las demás (broker, almacenamiento, despliegue, seguridad).

El sistema no es difícil por el tráfico de lectura del panel: 2 M de usuarios activos semanales con picos ×10 se resuelven con caché y un read model. La dificultad está en tres puntos:

1. **Ingesta:** correo de 15 M de casillas (QA-7) y eventos de proveedores heterogéneos (QA-1, QA-5) con picos impredecibles.
2. **Aislamiento de la analítica:** el CRN-3 prevé diseñar su aislamiento del sistema operativo. En esta iteración solo se define que la analítica queda aislada. Cómo se logra ese aislamiento se termina de desarrollar en iteraciones futuras.
3. **Trazabilidad entre la arquitectura documentada y el código del prototipo.** Con plazo acotado, la viabilidad de implementación es un criterio de decisión de primer orden y no un detalle (R-6).

## 2. Criterios de decisión

| Criterio | Origen del peso |
|---|---|
| QA-1 (actualizaciones en 5 min) | Prioridad Alta |
| QA-2 / QA-3 (latencia del panel) | QA-2 Media, QA-3 Baja |
| QA-5 (resiliencia e interoperabilidad con proveedores) | Prioridad Media |
| QA-6 / QA-7 (escalabilidad) | QA-6 Alta, QA-7 Media |

## 3. Alternativas consideradas

| ID | Alternativa | Resumen |
|---|---|---|
| A | Monolito modular | Una aplicación con módulos internos, N réplicas, base relacional con réplicas de lectura, Redis y cola simple. |
| B | Microservicios completos | Un servicio por capacidad, base por servicio, Kafka, CQRS, saga, gateway, service mesh, multi-región. |
| C | Híbrida (5-6 servicios) | Servicios de granularidad media agrupados por patrón de carga, event-driven, CQRS solo en el panel. |
| D | Serverless | Funciones disparadas por colas y webhooks, API Gateway, NoSQL gestionado. |
| E | Capas / N-tier | Capas lógicas estrictas desplegadas como tiers (web/API, workers, datos). Un solo código, varios roles. |
| **C-lite** | Capas/hexagonal con 3 desplegables | Panel API, ingesta (receptor de notificaciones, workers y relay del outbox) y analítica. Broker con log persistente y outbox entre la ingesta y el panel. La analítica queda aislada del sistema operativo (diseño a completar en iteraciones futuras). |

## 4. Decisión

**Se elige C-lite: arquitectura en capas/hexagonal con tres desplegables (Panel API, ingesta y analítica). La ingesta y el Panel API se comunican por un broker con log persistente y outbox (ADR-0003). La analítica queda aislada del sistema operativo (CRN-3), y su diseño se termina de desarrollar en iteraciones futuras. La separación en más servicios queda documentada como evolución prevista.**

### Descripción de C-lite

- **Estructura interna:** cada desplegable usa capas/hexagonal (presentación, aplicación, dominio, infraestructura), con adaptadores por proveedor y por medio de transporte que traducen al modelo canónico (R-2). El desplegable de ingesta tiene dos roles: un receptor liviano que recibe las notificaciones de proveedores de viaje y de correo, y los workers que las procesan.
- **Flujo principal entre desplegables:**
  - *Ingesta:* receptor → stream de trabajo del broker → workers. Los workers guardan el cambio y el evento en la base (outbox) y un relay publica el evento en el stream de eventos, que consume el Panel API (ADR-0003).
- **Analítica:** queda aislada del flujo de ingesta y del panel. Su diseño detallado se define en iteraciones futuras.
- **Tácticas conservadas:** buffer de entrada y de picos, replay por log persistente, outbox transaccional, CQRS solo en el panel, circuit breaker, reintentos con backoff, cola de mensajes inválidos, escalado independiente de la ingesta, aislamiento de la analítica respecto del sistema operativo (CRN-3), despliegue multi-AZ.
- **Tácticas diferidas:** separar notificaciones y procesador de itinerarios en servicios propios, y separar la ingesta de correo de la de proveedores.

### 4.1 Por qué C-lite y no E (capas / N-tier)

E es la alternativa más cercana y la que más se parece en implementación. La diferencia está en los QA de prioridad Alta y Media:

- **QA-1 y QA-5:** E no incluye por defecto un log persistente ni outbox. Sin ellos el replay tras una caída del proveedor es limitado, el requisito de 5 minutos queda expuesto a pérdidas de mensajes y el 99 % de recuperación de QA-5 es difícil de garantizar. Si a E se le agregan ambos, E deja de ser E y pasa a ser C-lite.
- **QA-2/3 y QA-6/7:** en E el panel, la ingesta y la analítica comparten el modelo y la base de escritura, así que las lecturas del panel hacen joins sobre el modelo de escritura y la presión de la ingesta se siente en la base. C-lite separa el read model del panel y aísla la analítica del sistema operativo.
- **El costo adicional de C-lite sobre E es pequeño:** un broker y un outbox, además del aislamiento de la analítica.

De E se conserva lo que mejor funciona: la organización interna en capas, que da trazabilidad y buena testabilidad.

### 4.2 Por qué no B (microservicios completos)

B es la mejor en QA pero la peor en implementabilidad. Tiene que ser descartada por tres razones:

- Exige consistencia eventual en todas partes, trazabilidad distribuida, múltiples pipelines y mucha infraestructura (service mesh, saga, multi-región) para un trabajo con plazo acotado.
- Implementar solo un subconjunto agrava la trazabilidad: cuanto más grande es la arquitectura documentada respecto del código, más difícil es defender que son consistentes.
- Su ventaja sobre C-lite en QA-6 (aislamiento fino por servicio) no se puede demostrar con un prototipo acotado, así que se pagaría el costo sin poder mostrar el beneficio.

### 4.3 Por qué no C (híbrida de 5-6 servicios)

C tiene la misma cobertura de QA que C-lite en los atributos de prioridad Alta (QA-1, QA-5, QA-6/7). Pero implica 5 o 6 desplegables con contratos propios, outbox, CQRS sincronizado por eventos, circuit breakers, DLQ, simuladores y observabilidad de flujos que cruzan varios servicios. Eso aumenta la dificultad de implementación.

Los servicios que C separa y C-lite no (notificaciones y procesador de itinerarios) escalan con patrones de carga parecidos a los que ya agrupa el Panel API y los workers, de modo que separarlos hoy agrega costo de integración sin mejorar un QA de prioridad Alta. Por eso se difieren y se documentan como evolución.

### 4.4 Por qué no D (serverless)

- Acoplamiento a un proveedor cloud y necesidad de cuenta cloud para todo el grupo, con riesgo de costos.
- Arranques en frío que amenazan QA-2 y QA-3 (mitigable con concurrencia provisionada, a mayor costo).
- Difícil de ejecutar y demostrar localmente, lo que debilita la trazabilidad del prototipo y el criterio de que el código sea reproducible.

### 4.5 Por qué no A (monolito modular)

Es la más simple pero cubre mal los QA Alta: la ingesta compite con el panel por recursos, un despliegue defectuoso tira todo, no hay log para replay (QA-1, QA-5) y se escala todo junto (QA-6). Se puede construir muy rápido, pero habría que reconocer en el diseño que varios drivers inmutables no se cumplen.

## 5. Consecuencias

### Positivas

- Cobertura defendible de los QA de prioridad Alta: buffer de entrada, buffer de picos y replay (QA-1, QA-5), ingesta escalable por separado (QA-6, QA-7), y analítica aislada (CRN-3).
- El código refleja la documentación casi uno a uno: tres desplegables, capas visibles en cada uno y adaptadores por proveedor (R-2).
- Es implementable por un grupo con plazo acotado: un broker, un outbox y tres procesos, ejecutables con docker-compose y proveedores simulados.
- La evolución a C o B no es una reescritura: los límites internos ya están modelados como módulos con puertos.

### Negativas y riesgos aceptados

| Riesgo | Efecto | Mitigación |
|---|---|---|
| Radio de impacto mayor en la ingesta: un adaptador defectuoso puede afectar a los workers | Puede interrumpir la integración con el resto de los proveedores (QA-8). | Aislamiento por adaptador dentro del worker (colas, pools y circuit breaker por proveedor). |
| Fugas entre capas por presión de plazo | La arquitectura documentada deja de coincidir con el código. | Reglas de dependencia verificadas automáticamente (por ejemplo ArchUnit o dependency-cruiser) en CI. |
| Base relacional compartida entre Panel API y workers | Cuello de botella de escritura a 15 M de cuentas. | Read model separado para las lecturas; particionado por usuario o región como evolución (ADR de almacenamiento). |
| Un broker más para operar | Complejidad operativa y un nuevo componente crítico. | Broker gestionado o clusterizado en producción; una sola instancia en el prototipo. |

