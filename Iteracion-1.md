# Iteración 1 de ADD: estructura general de Trotamundos

- **Estado:** Borrador para revisión
- **Fecha:** 23/10/2026 
- **Equipo:** grupo TPE
---

## 1. Resumen ejecutivo

Esta iteración fija la estructura global de Trotamundos. Se eligió una arquitectura en capas/hexagonal con **tres desplegables** (Panel API, ingesta y analítica). La ingesta y el Panel API se comunican por un **broker con log persistente y outbox**, el panel lee de un **read model** propio (CQRS solo en el panel) y la analítica queda aislada del sistema operativo, con un almacén analítico propio y su diseño detallado para iteraciones futuras (alternativa C-lite del ADR-0001, con los ADR-0002 a 0004).

La estructura da un camino de cumplimiento defendible a los drivers de mayor prioridad y dificultad (QA-1, QA-6) y deja la evolución hacia más servicios como camino previsto, sin reescritura. Los riesgos aceptados en los ADR se consolidan en **cuatro concerns nuevas** (sección 5), con una iteración prevista para cada una.

## 2. Objetivo de la iteración y drivers seleccionados

**Objetivo.** Establecer la estructura global del sistema (estilo arquitectónico, comunicación entre desplegables y aislamiento de la analítica) de modo que los drivers de mayor prioridad y dificultad tengan un camino de cumplimiento defendible y el diseño sea implementable por el grupo en el plazo del TPE.

Al tratarse de un desarrollo nuevo, el elemento a refinar es el **sistema completo**, que en esta iteración se descompone (refinamiento por descomposición) en desplegables y almacenes de apoyo.

| Driver | Motivo de la selección |
|---|---|
| CU-01, CU-02, CU-03, CU-04 | Funcionalidad primaria. Concentran la mayor parte de los requisitos de calidad. |
| QA-1, QA-6 | Los de mayor prioridad y dificultad del análisis inicial. Condicionan el estilo arquitectónico. |
| QA-2, QA-3, QA-4, QA-5, QA-7, QA-8, QA-12 | Intervienen en la decisión de estilo o en los riesgos de los ADR (QA-4 en BR-4, QA-8 en ES-1 y W-1, QA-12 en los adaptadores). Se abordan de forma parcial o quedan pendientes. |
| CU-06, QA-11 | El read model incluye la lista de personas autorizadas y define el borrado de entradas ante cambios de permisos (ADR-0004). Se abordan de forma parcial. |
| R-2, R-6 | R-2 moldea la integración (adaptadores por proveedor). R-6 (prototipo en plazo acotado) hace de la viabilidad de implementación un criterio de decisión de primer orden. |
| CRN-1 | Se moldea el concepto general del sistema (ADR-0001). |
| CRN-2 | Interviene en la elección del almacén del read model (ADR-0004). Se aborda de forma parcial. |
| CRN-3 | Aislar los casos de uso secundarios. En esta iteración solo se diseña el aislamiento de la analítica (CU-05) respecto del sistema operativo. El diseño detallado queda para iteraciones futuras. |

## 3. Conceptos de diseño

En esta primera iteración se documentan solo los conceptos que se desprenden directamente de los ADR. Las tácticas de detalle que enumera el ADR-0001 (circuit breaker, reintentos, cola de mensajes inválidos y otras) no se repiten acá.

| Concepto | Drivers | ADR | Alternativas descartadas |
|---|---|---|---|
| Arquitectura en capas/hexagonal con 3 desplegables (C-lite) | QA-1, QA-2, QA-5, QA-6, QA-7, QA-8, R-2, R-6, CRN-1, CRN-3 | 0001 | Monolito modular (A), microservicios (B), híbrida de 5-6 servicios (C), serverless (D), capas/N-tier (E) |
| Workers de ingesta asíncronos, con un adaptador por proveedor y por medio de transporte | CU-02, CU-03, QA-1, QA-5, QA-6, QA-7, QA-8, QA-12, R-2 | 0002 | Procesar en el receptor, un servicio por proveedor, funciones serverless por evento |
| Outbox transaccional con relay por polling | QA-1, QA-5 | 0002 | Doble escritura directa, publicar primero y guardar después, captura de cambios (CDC), transacción distribuida |
| Broker con log persistente, con un stream de trabajo (buffer de entrada) y uno de eventos | QA-1, QA-4, QA-5, QA-6, QA-7 | 0003 | Comunicación síncrona directa, base de datos como bus, cola durable simple, plataforma de streaming completa |
| Read model del panel en un almacén clave-valor en memoria (CQRS solo en el panel) | CU-01, CU-04, CU-06, QA-1, QA-2, QA-3, QA-4, QA-6, QA-11, CRN-2 | 0004 | Sin read model, tablas de lectura en la base relacional, base documental propia |

**Tecnologías.** El read model usa Redis (ADR-0004); las tablas de lectura en PostgreSQL quedan como reemplazo si el equipo prefiere no sumarlo. La tecnología del broker queda por definir. El motor de la BD queda por definir.

**Costo explícito.** La estructura suma piezas que operar (broker, relay, read model, almacén analítico) y datos que mantener consistentes entre sí. Ese costo se refleja en las concerns de la sección 5.

## 4. Vista modular y elementos instanciados

### 4.1 Vista modular

```mermaid
flowchart LR
    CL([Clientes web / móvil])
    PV(Proveedores de viaje)
    MC(Proveedores de correo)

    subgraph TROT[Trotamundos]
        direction LR
        API["Panel API<br/>lecturas, gestión manual,<br/>compartir, auth<br/>+ mantiene el read model"]
        RM[("Read model<br/>(Redis)")]

        subgraph INGD[Ingesta]
            direction LR
            RX["Receptor de notificaciones<br/>solo encola y responde"]
            PS["Planificador de sondeo<br/>de correo"]
            WK["Workers de ingesta<br/>adaptadores de proveedores<br/>y de correo"]
            RL["Relay del outbox"]
        end

        BR[["Broker con log persistente"]]
        DB[("BD")]
        AA[("Almacén analítico")]
        CA["Analítica"]
    end

    CL --> API
    API <-->|"lee y escribe"| DB
    API <-->|"lee y actualiza"| RM

    PV -- "notificaciones" --> RX
    MC -- "notificaciones push" --> RX
    PS -- "encola sondeo" --> BR
    RX -- "encola trabajo" --> BR
    BR -- "entrega trabajo" --> WK
    WK -- "consulta detalles" --> PV
    WK -- "lee correo" --> MC
    WK -- "cambio + evento<br/>misma transacción" --> DB

    DB -- "eventos pendientes" --> RL
    RL -- "publica eventos" --> BR
    BR -- "eventos" --> API

    DB -- "copia periódica" --> AA
    CA <-- "consultas analíticas" --> AA
```

**Clave.** Los rectángulos son elementos de código desplegable, los cilindros son almacenes, el rectángulo con doble borde es el broker y los óvalos son actores externos. Las flechas son comunicación, con su sentido y su motivo. La ingesta es un único artefacto con tres roles (receptor, workers y relay) que corren como procesos separados y escalan por separado; el planificador de sondeo se suma para el correo y su diseño queda para la Iteración 2.

### 4.2 Elementos y responsabilidades

| Elemento | Responsabilidades | Comunicación |
|---|---|---|
| **Panel API** | Lecturas del panel (CU-01), gestión manual de reservas (CU-04), compartir itinerarios y autenticación. Mantiene el read model a partir de los eventos y de sus propios cambios. | HTTP con clientes web y móvil. Lee y escribe en la BD. Lee y actualiza el read model. Consume el stream de eventos del broker. |
| **Read model** | Copia derivada del panel: un documento por viaje y un índice por usuario, con la lista de personas autorizadas a ver cada itinerario. No es fuente de verdad: se reconstruye desde la BD. | Lo lee y lo actualiza el Panel API. |
| **Ingesta: receptor de notificaciones** | Recibe las notificaciones push de proveedores de viaje y de correo, verifica su autenticidad, las encola y responde. | Recibe de los proveedores. Publica en el stream de trabajo del broker. |
| **Ingesta: planificador de sondeo** | Programa el sondeo periódico del correo de los usuarios y encola el trabajo. Diseño en el pipeline de correo (Iteración 2). | Publica en el stream de trabajo del broker. |
| **Ingesta: workers** | Adaptadores de proveedores de viaje (CU-02) y de correo (CU-03). Traducen al modelo canónico. Guardan el cambio y el evento en la BD en una sola transacción (outbox). | Toman trabajo del stream de trabajo. Consultan a los proveedores de viaje y leen el correo. Escriben en la BD. |
| **Ingesta: relay del outbox** | Publica en el stream de eventos los eventos pendientes del outbox. | Lee de la BD. Publica en el broker. |
| **Broker con log persistente** | Dos streams: de trabajo (buffer de entrada) y de eventos (hacia el panel). Buffer de picos, replay dentro de la retención y reintentos. | Producción: receptor, planificador y relay. Consumo: workers y Panel API. |
| **BD** | Usuarios, viajes, reservas, permisos, auditoría y outbox. Motor por definir. | Panel API, workers, relay y copia periódica hacia el almacén analítico. |
| **Almacén analítico** | Copia derivada para las consultas pesadas del equipo de datos. No es fuente de verdad. | Se alimenta con una copia periódica de la BD. Lo consulta la analítica. |
| **Analítica** | Queda aislada del sistema operativo (CRN-3): lee y escribe solo en su almacén analítico, sin acceder a la BD operacional ni al log. Su diseño detallado queda para iteraciones futuras. | Consultas sobre el almacén analítico. El detalle de la copia se define en iteraciones futuras. |

Las interfaces concretas (contratos de trabajo y de eventos, modelo canónico y API del panel) quedan para las próximas iteraciones.

## 5. Consecuencias negativas y nuevos drivers

Los ADR-0001 a 0004 registran 14 riesgos aceptados. Muchos se repiten entre ADR o se resuelven con drivers que ya están en la sección 2. Para no cerrar la iteración con una lista inmanejable, 14 se consolidan en **cuatro concerns nuevas** y los 6 restantes se resuelven donde indica la sección 5.2. Los identificadores W, BR y RM son los de los ADR-0002, 0003 y 0004. Los riesgos del ADR-0001 se citan como ES. Los IDs de las concerns son provisionales hasta incorporarlas al catálogo de drivers.

### 5.1 Concerns nuevas

| ID | Concern | Riesgos de origen | Mitigación ya prevista en los ADR |
|---|---|---|---|
| CRN-N1 | Aislar la falla de un proveedor del resto de la ingesta | ES-1, W-1 | Colas, pools y circuit breaker por proveedor dentro del worker. |
| CRN-N2 | Evitar que la base operacional compartida sea el cuello de botella | ES-3, W-2, W-3, RM-1 | Cada módulo es dueño de sus tablas. Partición y descarte del outbox. Lecturas por clave indexada y agrupadas. Réplica de lectura. Copia analítica desde réplica o en ventanas controladas. Prueba de carga. |
| CRN-N3 | Operar y mantener disponibles el broker, el relay y el read model | BR-3, W-3 | Broker gestionado o clusterizado y multi-AZ. El receptor responde con error para que el proveedor reintente. Alertas por antigüedad de la fila pendiente y retraso de consumo. Reinicio automático. |
| CRN-N4 | Mantener consistentes los datos entre BD, log y read model, y poder reconstruirlos | BR-3, RM-3, RM-2 | Idempotencia por identificador de notificación o versión del proveedor y por versión del viaje. Escritura condicional por versión en el read model. Reconstrucción del read model desde la BD. |

### 5.2 Riesgos que no se consolidan en una concern nueva

| Riesgo | Dónde se resuelve |
|---|---|
| ES-2 (fugas entre capas) | Reglas de dependencia verificadas en CI. |
| BR-1 (presupuesto de tiempo de QA-1) | QA-1, presupuesto por etapa (ítems 2 y 3 de la sección 7). |
| BR-2 (el log no recupera lo que el proveedor no entregó) | QA-5, reconciliación con proveedores (ítem 4). |
| BR-4 (valor del replay) | Revisión del valor del replay (ítem 3). |
| RM-4 (permisos desactualizados en el read model) | CU-06 y QA-11, ítem 8. |

## 6. Estado de los drivers al cierre

Evaluación para esta documentación, con tres estados: no abordado, parcialmente abordado y abordado. La tabla muestra los drivers abordados o parcialmente abordados. Los no abordados se listan debajo.

| Driver | Estado | Fundamento | Pendiente |
|---|---|---|---|
| CRN-1 | Abordado | Estructura general definida y justificada: C-lite con tres desplegables, broker, outbox y read model. | — |
| CRN-2 | Parcial | El read model usa Redis, que no figura entre las tecnologías del equipo, con PostgreSQL como reemplazo (ADR-0004). | Confirmar la experiencia del equipo con Redis. Elegir el motor de la BD. |
| CRN-3 | Parcial | La analítica (CU-05) queda como desplegable aislado, con almacén analítico propio y sin acceso a la BD operacional. | Diseño de la copia y del acceso a los datos. Aislamiento del resto de los casos de uso secundarios. |
| R-2 | Abordado a nivel de estructura | Adaptadores por proveedor y por medio de transporte en los workers. | Contratos concretos y modelo canónico. |
| R-6 | Parcial | Se descartaron las alternativas de mayor costo de implementación (microservicios, híbrida, serverless, plataforma de streaming). Tres desplegables y cuatro piezas de apoyo (BD, broker, Redis y almacén analítico) ejecutables con docker-compose y proveedores simulados. | Reverificar el esfuerzo en cada iteración, en particular el costo de operar las piezas nuevas (CRN-N3). |
| CU-01 | Parcial | El Panel API lee del read model, mantenido por eventos. | Diseño de las lecturas del panel. |
| CU-02 | Parcial | Workers con un adaptador por proveedor de viaje. | Modelo canónico y contratos de adaptadores. |
| CU-03 | Parcial | Workers con adaptador de correo, buffer de entrada y planificador de sondeo. | Pipeline de ingesta de correo. |
| CU-04 | Parcial | El Panel API guarda el cambio manual en la BD y actualiza el read model directamente, sin pasar por el broker. | API de gestión manual de reservas y reglas de validación. |
| CU-06 | Parcial | El read model incluye la lista de personas autorizadas y se borra ante cambios de permisos. | Diseño del compartir itinerarios y de la gestión de permisos. |
| QA-1 | Parcial | Buffer de entrada, outbox, log persistente y read model actualizado por eventos. | Presupuesto de tiempo por etapa, tecnología del broker e intervalo del relay. |
| QA-2, QA-3 | Parcial | El panel lee de un read model en memoria, aislado de las escrituras de la ingesta. | Dimensionamiento y prueba de carga. QA-3 depende además del cliente. |
| QA-4 | Parcial | El panel sirve desde el read model si cae el broker. Broker multi-AZ previsto (BR-3). | Despliegue y alta disponibilidad de BD, broker y read model. |
| QA-5 | Parcial | La última versión conocida queda en la BD y se sirve desde el read model. Outbox sin pérdida y replay del log dentro de la retención. | Reconciliación con el proveedor al restablecerse la API, valor de retención y política de recuperación dentro de los 5 minutos. |
| QA-6 | Parcial | Ingesta escalable por separado, buffer de entrada y lecturas aisladas de las escrituras. | CRN-N2. Dimensionamiento. |
| QA-7 | Parcial | Workers escalables de forma independiente y buffer de entrada. | Pipeline de ingesta de correo. |
| QA-8 | Parcial | Adaptador y circuit breaker por proveedor, y stream de mensajes inválidos. | Validación de mensajes y contratos. |
| QA-11 | Parcial | El Panel API verifica la lista de personas en cada acceso y registra los rechazos en la auditoría (ADR-0004). | Diseño del compartir itinerarios. |
| QA-12 | Parcial | Adaptadores por puertos: un transporte nuevo es un adaptador nuevo (ADR-0002). | Modelo canónico y contratos de adaptadores. |

### Drivers no abordados

Los siguientes drivers del análisis inicial no se abordan en esta iteración, y las concerns nuevas quedan pendientes:

- **Funcionalidad secundaria:**
  - CU-05 (analítica de datos; solo se registra su aislamiento a través de CRN-3)
  - CU-07 (agrupar reservas por viaje y archivado automático)
  - CU-08 (filtros y *whitelists* de remitentes)
  - CU-09 (resolución de problemas con la agencia de viajes)
- **Atributos de calidad:**
  - QA-9 (extracción de reservas desde el correo)
  - QA-10 (revocación del acceso al correo)
- **Restricciones:**
  - R-1 (sistema multiplataforma)
  - R-3 (dependencia de sistemas externos)
  - R-4 (protección de datos por región)
  - R-5 (soporte limitado en celulares de tecnología móvil antigua)
- **Concerns nuevas de esta iteración:**
  - CRN-N1 (aislar la falla de un proveedor)
  - CRN-N2 (capacidad de la base compartida)
  - CRN-N3 (operar broker, relay y read model)
  - CRN-N4 (consistencia y reconstrucción de datos)

## 7. Decisiones pendientes y próximas iteraciones

ADR necesarios, derivados de las concerns nuevas y de los drivers parciales:

1. Modelo canónico, contratos de adaptadores y validación de mensajes (R-2, QA-8, QA-12, CU-02, CU-03, CRN-N1).
2. Contratos de mensajes del stream de trabajo y del de eventos: identificador, versión, clave de orden y campo de deduplicación (CRN-N4).
3. Tecnología del broker (ADR-0003, sección 1, Fuera de alcance), valor de retención del log y revisión del valor del replay (QA-5).
4. Reconciliación con los proveedores y política de recuperación tras una interrupción, dentro de los 5 minutos (QA-5).
5. Pipeline de ingesta de correo, incluido el planificador de sondeo (CU-03, QA-7).
6. Almacenamiento operacional: motor de la base, partición del outbox, capacidad y réplica de lectura (CRN-N2, CRN-2).
7. Despliegue, alta disponibilidad y monitoreo del broker, el relay y el read model (QA-4, CRN-N3).
8. Lecturas del panel, gestión manual de reservas, compartir itinerarios y reconstrucción del read model (CU-01, CU-04, CU-06, QA-11, CRN-N4).
9. Aislamiento de la analítica, copia periódica y acceso a los datos (CRN-3).
10. Multi-región y protección de datos por región (R-4).
11. Módulo avanzado obligatorio del enunciado: Opción A (recomendación) u Opción B (asistente virtual). <PENDIENTE: el grupo debe elegir una opción.>
12. Vistas arquitectónicas complementarias a la modular: despliegue y secuencia. <PENDIENTE: asignar a iteración.>

Propuesta de orden:

- **Iteración 2: ingesta y eventos.** Modelo canónico, adaptadores, contratos de mensajes, tecnología del broker, reconciliación con proveedores y pipeline de correo (CU-02, CU-03, QA-1, QA-5, QA-7, QA-8, R-2, CRN-N1, CRN-N4).
- **Iteración 3: panel, almacenamiento y disponibilidad.** Lecturas del panel, gestión manual, compartir, almacenamiento operacional, despliegue y monitoreo (CU-01, CU-04, CU-06, QA-2, QA-3, QA-4, QA-6, QA-11, CRN-N2, CRN-N3).
- **Iteración 4 o posterior.** Aislamiento de la analítica (CRN-3), multi-región (R-4) y módulo avanzado.

