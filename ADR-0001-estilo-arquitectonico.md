# ADR-0001: Estilo arquitectónico de Trotamundos

- **Estado:** Propuesto
- **Fecha:** 14/10/2026 (versión 2, ver historial al final)
- **Decisores:** Equipo de diseño (grupo TPE)
- **Drivers de entrada:** [Análisis inicial y drivers](Analisis-Inicial-y-Drivers.md) (CU-01 a CU-04, QA-1 a QA-12, R-1 a R-5)

---

## 1. Contexto y problema

Hay que elegir el estilo arquitectónico general de Trotamundos antes de empezar las iteraciones de ADD. La decisión condiciona todas las demás (broker, almacenamiento, despliegue, seguridad).

El sistema no es difícil por el tráfico de lectura del panel: 2 M de usuarios activos semanales con picos ×10 se resuelven con caché y un read model. La dificultad está en cuatro puntos:

1. **Ingesta:** correo de 15 M de casillas (QA-7, QA-9, QA-10) y eventos de proveedores heterogéneos (QA-1, QA-5, QA-8) con picos impredecibles.
2. **Disponibilidad:** 99,99 % (QA-4) exige redundancia real y ausencia de puntos únicos de falla.
3. **Aislamiento de la analítica:** el CU-05 prevé diseñar su aislamiento del sistema operativo, de modo que las consultas pesadas no degraden el panel. Ya no existe un QA formal para esto, así que es una preocupación de diseño y no un criterio puntuado.
4. **Extensibilidad:** sumar trenes sin tocar el núcleo (QA-12).

Además, el TPE se evalúa por la **trazabilidad entre la arquitectura documentada y el código del prototipo**, y es un trabajo grupal con plazo acotado. La viabilidad de implementación es entonces un criterio de decisión de primer orden y no un detalle.

## 2. Criterios de decisión

| Criterio | Peso | Origen del peso |
|---|---|---|
| QA-1 (actualizaciones en 5 min) | 3 | Prioridad Alta |
| QA-2 / QA-3 (latencia del panel) | 2 | QA-2 Media, QA-3 Baja |
| QA-4 (99,99 %) | 3 | Prioridad Alta |
| QA-5 / QA-8 (resiliencia e interoperabilidad con proveedores) | 3 | QA-8 Alta, QA-5 Media |
| QA-6 / QA-7 (escalabilidad) | 3 | QA-6 Alta, QA-7 Media |
| QA-10 / QA-11 (seguridad) | 2 | QA-10 Media, QA-11 Baja |
| QA-12 (evolucionabilidad) | 2 | Prioridad Media |
| Facilidad de implementar | 3 | Viabilidad del prototipo en el plazo del TPE |
| Trazabilidad doc ↔ código | 3 | Criterio de evaluación explícito del TPE |

Los pesos de los QA siguen la columna "Prioridad" del análisis inicial (Alta = 3, Media = 2, Baja = 1). Cuando un criterio agrupa dos QA se toma la prioridad más alta.

**QA no puntuado:** QA-9 (extracción correcta del 95 % de los correos de la whitelist en menos de 15 segundos) depende de la calidad de los extractores y no del estilo arquitectónico, por lo que no discrimina entre alternativas.

**Método en dos pasos:**

1. **Filtro de viabilidad.** Mientras que no se puede designarle puntajes objetivos a la dificultad de implementar ciertas arquitecturas puede designarle una dificultad subjetiva a estas en base a experiencias previas y los conocimientos que poseen. Esto y el factor de con cuanto tiempo cuenta el equipo va a guiar de cierta forma que arquitectura se va a elegir
2. **Puntaje ponderado de QA** entre las alternativas que pasan el filtro.

## 3. Alternativas consideradas

| ID | Alternativa | Resumen |
|---|---|---|
| A | Monolito modular | Una aplicación con módulos internos, N réplicas, base relacional con réplicas de lectura, Redis y cola simple. |
| B | Microservicios completos | Un servicio por capacidad, base por servicio, Kafka, CQRS, saga, gateway, service mesh, multi-región. |
| C | Híbrida (5-6 servicios) | Servicios de granularidad media agrupados por patrón de carga, event-driven, CQRS solo en el panel. |
| D | Serverless | Funciones disparadas por colas y webhooks, API Gateway, NoSQL gestionado. |
| E | Capas / N-tier | Capas lógicas estrictas desplegadas como tiers (web/API, workers, datos). Un solo código, varios roles. |
| **C-lite** | Capas/hexagonal con 3 desplegables | Panel API, workers de ingesta y consumidor de analítica. Broker con log persistente y outbox. |

### Descripción de C-lite

```mermaid
flowchart LR
    CL([Clientes web / móvil])
    PV[(Proveedores de viaje)]
    MC[(Proveedores de correo)]

    subgraph TROT[Trotamundos]
        direction LR
        API[Panel API<br/>lecturas, gestión manual,<br/>compartir, auth]
        ING[Workers de ingesta<br/>adaptadores de proveedores<br/>y de correo]
        ANA[Consumidor de analítica]
        BR[[Broker con log persistente<br/>Redis Streams / RabbitMQ Streams]]
        DB[(BD relacional<br/>+ read model en caché)]
        OLAP[(Almacén OLAP)]
        VAULT[(Bóveda de tokens)]
    end

    CL --> API
    PV --> ING
    MC -- push --> ING
    ING -- outbox --> BR
    BR --> API
    BR --> ANA
    API --> DB
    ANA --> OLAP
    ING --> VAULT
```

- **Estructura interna:** cada desplegable usa capas/hexagonal (presentación, aplicación, dominio, infraestructura), con adaptadores por proveedor y por medio de transporte que traducen al modelo canónico (R-2).
- **Tácticas conservadas:** buffer de picos y replay por log persistente, outbox transaccional, CQRS solo en el panel, circuit breaker, reintentos con backoff, cola de mensajes inválidos, escalado independiente de la ingesta, aislamiento de analítica vía eventos (CU-05), despliegue multi-AZ.
- **Tácticas diferidas:** separar notificaciones y procesador de itinerarios en servicios propios, y separar la ingesta de correo de la de proveedores (ver sección 7).

## 4. Decisión

**Se elige C-lite: arquitectura en capas/hexagonal con tres desplegables (Panel API, workers de ingesta y consumidor de analítica), comunicados por un broker con log persistente y outbox, con la separación en más servicios documentada como evolución prevista.**

### 5.1 Por qué C-lite y no E (capas / N-tier)

E es la alternativa más cercana y la que más se parece en implementación (facilidad 4 contra 3). La diferencia está en los QA de prioridad Alta y Media:

- **QA-1 y QA-5:** E no incluye por defecto un log persistente ni outbox. Sin ellos el replay tras una caída del proveedor es limitado, el requisito de 5 minutos queda expuesto a pérdidas de mensajes y el 99 % de recuperación de QA-5 es difícil de garantizar. Si a E se le agregan ambos, E deja de ser E y pasa a ser C-lite.
- **QA-2/3 y QA-6/7:** en E el panel, la ingesta y la analítica comparten el modelo y la base de escritura, así que las lecturas del panel hacen joins sobre el modelo de escritura y la presión de la ingesta se siente en la base. C-lite separa el read model del panel y manda la analítica por eventos a un almacén propio.
- **El costo adicional de C-lite sobre E es pequeño:** un broker y un outbox. Es un punto de facilidad (4 → 3) a cambio de 11 puntos de QA ponderados.

De E se conserva lo que mejor funciona: la organización interna en capas, que da trazabilidad 4 y buena testabilidad.

### 4.2 Por qué no B (microservicios completos)

B es la mejor en QA pero la peor en implementabilidad (1). Tiene que ser descartada por tres razones:

- Exige consistencia eventual en todas partes, trazabilidad distribuida, múltiples pipelines y mucha infraestructura (service mesh, saga, multi-región) para un trabajo grupal con plazo acotado.
- Implementar solo un subconjunto agrava la trazabilidad: cuanto más grande es la arquitectura documentada respecto del código, más difícil es defender que son consistentes.
- Su ventaja sobre C-lite en QA-4 y QA-6 (aislamiento fino por servicio) no se puede demostrar con un prototipo acotado, así que se pagaría el costo sin poder mostrar el beneficio.

### 4.3 Por qué no C (híbrida de 5-6 servicios)

C tiene la misma cobertura de QA que C-lite en los atributos de prioridad Alta (QA-1, QA-5/8, QA-6/7) y es mejor solo en QA-4 (3 puntos ponderados de diferencia). Pero implica 5 o 6 desplegables con contratos propios, outbox, CQRS sincronizado por eventos, circuit breakers, DLQ, simuladores y observabilidad de flujos que cruzan varios servicios. Eso la deja en facilidad 2 y la saca por el filtro.

Los servicios que C separa y C-lite no (notificaciones y procesador de itinerarios) escalan con patrones de carga parecidos a los que ya agrupa el Panel API y los workers, de modo que separarlos hoy agrega costo de integración sin mejorar un QA de prioridad Alta. Por eso se difieren y se documentan como evolución.

### 4.4 Por qué no D (serverless)

- Acoplamiento a un proveedor cloud y necesidad de cuenta cloud para todo el grupo, con riesgo de costos.
- Arranques en frío que amenazan QA-2 y QA-3 (mitigable con concurrencia provisionada, a mayor costo).
- Difícil de ejecutar y demostrar localmente, lo que debilita la trazabilidad del prototipo (2) y el criterio de que el código sea reproducible.

### 4.5 Por qué no A (monolito modular)

Es la más simple (5) pero cubre mal los QA Alta: la ingesta compite con el panel por recursos, un despliegue defectuoso tira todo (QA-4), no hay log para replay (QA-1, QA-5) y se escala todo junto (QA-6). Se puede construir muy rápido, pero habría que reconocer en el diseño que varios drivers inmutables no se cumplen.

## 5. Consecuencias

### Positivas

- Cobertura defendible de los QA de prioridad Alta: buffer de picos y replay (QA-1, QA-5), ingesta escalable por separado (QA-6, QA-7), y analítica aislada por eventos (CU-05).
- El código refleja la documentación casi uno a uno: tres desplegables, capas visibles en cada uno y adaptadores por proveedor (QA-12, R-2).
- Es implementable por un grupo con plazo acotado: un broker, un outbox y tres procesos, ejecutables con docker-compose y proveedores simulados.
- La evolución a C o B no es una reescritura: los límites internos ya están modelados como módulos con puertos.

### Negativas y riesgos aceptados

| Riesgo | Efecto | Mitigación |
|---|---|---|
| QA-4 queda con cobertura parcial (3) | Un bug en el código de dominio compartido o en la base primaria puede afectar a más de un desplegable. | Multi-AZ, failover automático de la base, despliegues graduales con rollback. Documentar el límite en los ADR de despliegue. |
| Radio de impacto mayor en la ingesta: un adaptador defectuoso puede afectar a los workers | Amenaza QA-8 ("sin interrumpir la integración con el resto de los proveedores"). | Aislamiento por adaptador dentro del worker (colas, pools y circuit breaker por proveedor). |
| Fugas entre capas por presión de plazo | La arquitectura documentada deja de coincidir con el código. | Reglas de dependencia verificadas automáticamente (por ejemplo ArchUnit o dependency-cruiser) en CI. |
| Base relacional compartida entre Panel API y workers | Cuello de botella de escritura a 15 M de cuentas (QA-6). | Read model separado para las lecturas; particionado por usuario o región como evolución (ADR de almacenamiento). |
| Un broker más para operar | Complejidad operativa y un nuevo componente crítico. | Broker gestionado o clusterizado en producción; una sola instancia en el prototipo. |

## 6. Evolución prevista

La arquitectura se divide más cuando se observe alguna de estas condiciones, y no antes:

| Disparador | Separación |
|---|---|
| Las notificaciones en tiempo real exigen escalar según usuarios conectados, distinto del Panel API | Servicio de notificaciones |
| La ingesta de correo y la de proveedores tienen perfiles de carga o requisitos de seguridad distintos (por ejemplo, aislamiento de tokens de correo en un servicio propio, QA-10) | Ingesta de correo e ingesta de proveedores como desplegables separados |
| La actualización del read model compite con el procesamiento de eventos del dominio | Procesador de itinerarios como servicio propio |
| Un equipo adicional necesita desplegar de forma independiente un componente | Extraer el módulo correspondiente |

Con los cuatro aplicados se llega a la Alternativa C.

