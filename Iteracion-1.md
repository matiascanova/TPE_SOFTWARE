# Iteración 1 de Trotamundos: establecer la estructura general

- **Estado:** Borrador para revisión
- **Fecha:** 27/10/2026

---

## Paso 1. Revisar las entradas del diseño

### 1.1 Contexto

Trotamundos unifica los arreglos de viaje en un panel (CU-01). Recibe notificaciones push de los proveedores de viaje y sondea el correo de forma programada (15 M de cuentas, QA-7); traduce ambos a un modelo canónico. El panel tiene 2 M de usuarios activos semanales y picos ×10* (QA-6). Es la primera iteración: no hay iteraciones previas y la estructura general no está fijada. Sus decisiones quedan en ADR-0001 a ADR-0004, hoy en estado Propuesto, más una decisión menor sin ADR (PostgreSQL, 6.2).

### 1.2 Entradas

**Iteración 1**

| Categoría | Detalle |
|---|---|
| Propósito del diseño | Greenfield. Prototipo en plazo acotado (R-6) |
| Primary functionality | CU-01, CU-02, CU-03 y CU-04. Secundarios: CU-05 a CU-09 (CU-06 se toca por ADR-0004) |
| QA scenarios primarios | Los de prioridad Alta del catálogo. QA-1: actualizaciones de un proveedor en el panel en < 5 min. QA-4: tolerar la caída de una instancia o zona con ≤ 5 min mensuales de inactividad (≈ 99,99 %). QA-6: 15 M de cuentas, 2 M de usuarios activos semanales y picos ×10*, cumpliendo QA-1 y QA-2. QA-8: 99 % de los mensajes válidos procesados y 99 % de los inválidos registrados, sin afectar a otros proveedores |
| Constraints | R-1 a R-6. Condicionan esta iteración R-2 (APIs estándar de los proveedores, sin modificarlas) y R-6 (prototipo rápido en plazo acotado) |
| Concerns | CRN-1 (estructura general), CRN-2 (aprovechar el conocimiento del equipo: Java, PostgreSQL, C++, Python, entre otros), CRN-3 (aislar los casos de uso secundarios) |

\* Medida propuesta por el equipo (catálogo de drivers, sección 4).

---

## Paso 2. Establecer el objetivo de la iteración seleccionando drivers

### 2.1 Objetivo

**Objetivo:** Fijar la estructura general de Trotamundos (CRN-1), de modo que la ingesta cubra QA-1 y QA-5 a nivel de decisiones: estilo, desplegables, conector entre ingesta y panel, almacén de lectura del panel y tecnología de la base.

**Tipo:** Un driver importante (CRN-1)

### 2.2 Drivers seleccionados

| Driver | Rol | Por qué condiciona las decisiones |
|---|---|---|
| CRN-1 | Principal | Fija estilo, desplegables, conector y almacenes |
| QA-1 | Principal | Las actualizaciones de proveedores llegan al panel en < 5 min; prioridad y dificultad Altas, y la dificultad está en la ingesta |
| QA-5 | Principal | Con la API de un proveedor caída, el panel muestra la última versión conocida y, dentro de 5 min* de restablecida, se recupera el 99 % de los eventos generados durante la caída |
| QA-2 | Contexto | El panel responde en < 800 ms con carga normal |
| QA-6 | Contexto | 15 M de cuentas, 2 M de usuarios semanales y picos ×10* cumpliendo QA-1 y QA-2: exige separar las lecturas del panel de las escrituras de la ingesta |
| QA-7 | Contexto | El correo de usuarios con viajes próximos se procesa en ≤ 15 min* para el 95 %*: condiciona planificador y workers |
| QA-8 | Contexto | 99 % de los mensajes válidos procesados y 99 % de los inválidos registrados sin afectar a otros proveedores: condiciona el aislamiento por adaptador |
| R-6 | Contexto | La viabilidad en un plazo acotado es criterio de primer orden |
| CRN-2 | Contexto | Fija PostgreSQL como tecnología de la base |
| CRN-3 | Contexto | La analítica (CU-05) va en un desplegable propio, aislada del sistema operativo |
| CU-01 a CU-04 | Contexto | Flujos de ingesta y panel que la estructura debe soportar |

### 2.3 Alcance

**Dentro:** Estilo general, desplegables y piezas de apoyo (ADR-0001), conector de la ingesta (ADR-0003), workers con outbox (ADR-0002), read model del panel (ADR-0004) y tecnología de la base (6.2). R-2, QA-3, QA-4, QA-11, QA-12 y CU-06 los citan los ADR sin ser objetivo y se evalúan como relacionados en 7.1.

**Fuera de alcance:** Partición de la base operacional, almacén analítico y copia hacia él. Pipeline de correo y planificador de sondeo. Despliegue, seguridad, diseño interno de la analítica, tecnología concreta del broker, dimensionamiento y réplicas del read model, y compartir entre regiones. El resto de los drivers queda sin tratar (7.3).

---

## Paso 3. Elegir uno o más elementos del sistema para refinar

### 3.1 Elementos seleccionados

El sistema Trotamundos completo (diagrama de contexto).

**Tipo de refinamiento:** Descomposición (top-down)

### 3.2 Límite del refinamiento

Se descompone en tres desplegables (Panel API, Ingesta y Analítica) y cuatro piezas de apoyo (base relacional, read model, broker y almacén analítico), con los roles internos de la Ingesta. El diseño interno de cada desplegable más allá de capas y puertos queda fuera.

---

## Paso 4. Elegir conceptos de diseño que satisfagan los drivers seleccionados

### 4.1 Conceptos seleccionados

| Concepto | Tipo | Drivers |
|---|---|---|
| C-lite: capas/hexagonal con tres desplegables | Patrón | QA-1, QA-2, QA-5, QA-6, QA-7, QA-8, R-2, R-6, CRN-1, CRN-3 |
| Broker con log persistente | Patrón | QA-1, QA-4, QA-5, QA-6, QA-7, R-6 |
| Workers asíncronos con outbox transaccional | Patrón | QA-1, QA-5, QA-6, QA-7, QA-8, QA-12, R-2, R-6 |
| CQRS en el panel con read model en una instancia de base propia | Patrón | QA-1, QA-2, QA-3, QA-4, QA-6, QA-11, R-6, CRN-2 |
| Aislamiento por adaptador: circuit breaker, reintentos con backoff y cola de mensajes inválidos | Táctica | QA-5, QA-6, QA-7, QA-8, R-2 |

### 4.2 Cómo se combinan

Los conceptos forman una cadena: receptor, trabajo, outbox, eventos y read model. Su costo conjunto es el retraso acumulado contra los 5 minutos de QA-1 (BR-1) y la base operacional compartida entre Panel API y Workers (W-2, RM-1); ambos se recogen en CRN-4 y CRN-5.

---

## Paso 5. Instanciar elementos, asignar responsabilidades y definir interfaces

### 5.1 Elementos instanciados

Los flujos entre elementos están en la vista 6.1; acá solo se indica con quién y cómo se comunica cada uno.

| Elemento | Tipo | Responsabilidades | Se comunica con (mecanismo) |
|---|---|---|---|
| Panel API | Componente (desplegable) | Atiende el panel, verifica la lista de personas de un itinerario compartido y mantiene el read model a partir de los eventos | Usuarios (HTTP); Base relacional (SQL); Read model (puerto); Broker (mensajes) |
| Ingesta | Componente (desplegable) | Agrupa Receptor, Planificador de sondeo, Workers y Relay, que corren y escalan por separado | Sus roles |
| Receptor | Componente (rol de Ingesta) | Recibe el push y lo encola; responde con error si el broker no está, para que el proveedor reintente | Proveedores (push); Broker (mensajes) |
| Planificador de sondeo | Componente (rol de Ingesta) | Dispara el sondeo programado del correo (diseño fuera de alcance, 2.3) | Broker (mensajes) |
| Workers | Componente (rol de Ingesta) | Traducen al modelo canónico con un adaptador aislado por proveedor y medio; guardan cambio y evento en una transacción; reconcilian al volver el proveedor | Broker (mensajes); Proveedores (API o casilla); Base relacional (SQL) |
| Relay | Componente (rol de Ingesta) | Publica por polling los eventos pendientes del outbox, al menos una vez | Base relacional (SQL); Broker (mensajes) |
| Analítica | Componente (desplegable) | Procesa datos sobre su almacén propio; sin acceso a la base operacional ni al log | Almacén analítico |
| Base relacional | Almacén | Fuente de verdad: itinerarios, cambios y outbox | Panel API, Workers y Relay |
| Read model | Almacén | Documento por viaje e índice por usuario en una instancia propia; se reconstruye desde la base relacional | Panel API |
| Broker con log persistente | Otro (conector) | Streams de trabajo, eventos e inválidos con offset por consumidor; no es fuente de verdad | Receptor, Planificador, Workers, Relay y Panel API |
| Almacén analítico | Almacén | Datos de la analítica, alimentados con una copia periódica de la base | Analítica; Base relacional (copia) |
| Proveedores de viaje y de correo | Otro (externo) | Los de viaje envían push y exponen una API; los de correo exponen casillas | Receptor; Workers |
| Usuarios del panel | Otro (externo) | Consultan, editan (CU-04) y comparten itinerarios (CU-06) | Panel API |

### 5.2 Tecnologías

| Tecnología | Elemento o concepto |
|---|---|
| PostgreSQL | Base relacional y Read model (instancia propia) |

**Por definir:** Broker (ADR-0003), partición de la base y almacén analítico (fuera de alcance, 2.3), lenguajes y frameworks (CRN-2).

### 5.3 Interfaces y contratos

| Elemento | Tipo | Operación | Contrato |
|---|---|---|---|
| Puerto de mensajería | Interna | Publicar y consumir en un stream | Al menos una vez y consumidores idempotentes; orden por clave (el viaje en eventos; en trabajo, a definir), sin orden global; inválidos y reintentos agotados van al stream de inválidos |
| Workers y Base relacional | Interna | Guardar cambio y evento | Una transacción, idempotente por identificador de notificación o versión del proveedor |
| Puerto del read model | Interna | Reemplazar documento; borrar entradas | Reemplaza solo si la versión nueva es mayor o igual a la guardada (atómico); borra las entradas afectadas ante un cambio de permisos; si falta una, se reconstruye desde la base relacional |

**Pendientes de detallar:** Modelo canónico. Contratos del push y de la API o casillas de los proveedores. API externa del Panel API. Clave de orden del stream de trabajo y retención de los streams (ADR-0003). Contrato de la copia hacia el almacén analítico (CRN-8).

---

## Paso 6. Esbozar vistas y registrar decisiones de diseño

### 6.1 Vistas

**Vista: Ingesta, panel y almacenes**

- **Tipo de vista:** Component & Connector
- **Qué muestra:** Los elementos en ejecución y los flujos de la ingesta y del panel.

Diagrama:

```mermaid
flowchart LR
  Usuarios(["Usuarios del panel"])
  Proveedores(["Proveedores de viaje y de correo"])

  subgraph Ingesta["Ingesta (desplegable)"]
    Receptor["Receptor"]
    Planificador["Planificador de sondeo"]
    Workers["Workers"]
    Relay["Relay"]
  end

  subgraph PanelD["Panel API (desplegable)"]
    PanelAPI["Panel API"]
  end

  subgraph AnaliticaD["Analítica (desplegable)"]
    Analitica["Analítica"]
  end

  subgraph Broker["Broker con log persistente"]
    SWork[("Stream de trabajo")]
    SEv[("Stream de eventos")]
    SInv[("Stream de inválidos")]
  end

  Base[("Base relacional")]
  ReadModel[("Read model")]
  AlmAn[("Almacén analítico")]

  Proveedores -->|"notificación push"| Receptor
  Receptor ==>|"encola"| SWork
  Planificador ==>|"encola sondeo"| SWork
  SWork ==>|"consume"| Workers
  Workers -->|"consulta API o lee correo"| Proveedores
  Workers -->|"transacción: cambio + outbox"| Base
  Workers ==>|"inválidos y reintentos agotados"| SInv
  Relay -->|"lee outbox pendiente"| Base
  Relay ==>|"publica evento"| SEv
  SEv ==>|"consume"| PanelAPI
  PanelAPI ==>|"inválidos"| SInv
  Usuarios -->|"consulta y edita"| PanelAPI
  PanelAPI -->|"lee y escribe"| Base
  PanelAPI -->|"lee; escribe por versión"| ReadModel
  Base -.->|"copia periódica"| AlmAn
  Analitica -->|"lee y procesa"| AlmAn
```

Clave:

- Óvalo: actor externo. Rectángulo: elemento de software. Cilindro: almacén o stream del log.
- Recuadro con título: desplegable o broker.
- Flecha fina: llamada o acceso directo. Flecha gruesa: mensaje asíncrono por el broker. Flecha punteada: copia periódica.

### 6.2 Registro de decisiones

| Decisión | ADR | Descartadas | Justificación (solo si no hay ADR) |
|---|---|---|---|
| C-lite: capas/hexagonal con tres desplegables | ADR-0001 | Capas / N-tier, Monolito modular, Microservicios | — |
| Broker con log persistente | ADR-0003 | Base de datos como bus, Cola durable simple, Plataforma de streaming completa | — |
| Workers asíncronos con outbox transaccional | ADR-0002 | Procesar en el receptor, Doble escritura sin outbox, Outbox con CDC | — |
| CQRS en el panel con read model en una instancia de base propia | ADR-0004 | Sin read model, Tablas de lectura en la misma base operacional, Almacén en memoria | — |
| Aislamiento por adaptador: circuit breaker, reintentos con backoff y cola de mensajes inválidos | ADR-0002 y ADR-0003 | Sin alternativas registradas | — |
| PostgreSQL como tecnología de la base relacional y del read model | «menor» | Sin alternativas registradas | Es la tecnología que el equipo conoce (CRN-2). |

---

## Paso 7. Analizar el diseño y revisar el objetivo y el propósito del diseño
### 7.1 Concerns nuevas

| ID | Concern | Riesgos de origen | Mitigación prevista |
|---|---|---|---|
| CRN-4 | Las medidas de latencia no están verificadas bajo carga: la cadena receptor, trabajo, outbox, eventos y read model suma retrasos contra los 5 minutos de QA-1 (15 en el correo, QA-7), y el read model en disco debe sostener los picos ×10 de QA-2 y QA-3 | BR-1, W-3, RM-1, RM-2 | Presupuesto por etapa; alertas por retraso de consumo y por antigüedad de la fila más vieja del outbox; prueba de carga con picos ×10; caché en memoria delante del read model si no alcanza (ADR-0004) |
| CRN-5 | Los límites internos se sostienen por disciplina y no por separación física: base operacional compartida, adaptadores en un mismo proceso y capas | W-1, W-2, ES-2, ES-3 (base compartida) | Colas, pools y circuit breaker por adaptador; reglas de dependencia en CI; cada módulo dueño de sus tablas; read model separado; prueba de carga |
| CRN-6 | La ingesta depende del broker y del proveedor para no perder datos: el broker está en el camino de entrada, el log no recupera lo que el proveedor nunca entregó y su ventaja sobre una cola depende de usar el replay | BR-2, BR-3, BR-4 | Broker gestionado o clusterizado y multi-AZ; error al proveedor para que reintente; reconciliación de los adaptadores (ADR-0002); el puerto de mensajería permite pasar a una cola durable |
| CRN-7 | El read model puede servir datos pisados o con permisos desactualizados | RM-3, RM-4 | Escritura condicional por versión; borrar la entrada ante un cambio de permisos, con reintentos; verificar la lista en cada acceso y registrar los rechazos |
| CRN-8 | La copia periódica hacia el almacén analítico no tiene elemento responsable: la analítica no accede a la base operacional (CRN-3) y la copia sale de ella | ES-3 (tramo de la copia) | Asignar la copia a un elemento y definir si lee de una réplica o en ventanas controladas |

Los IDs son provisionales y continúan el catálogo (último: CRN-3).

### 7.2 Kanban al cierre

| Not Yet Addressed | Partially Addressed | Completely Addressed |
|---|---|---|
| CU-05, CU-07, CU-08, CU-09, QA-9, QA-10, R-1, R-3, R-4, R-5, CRN-4, CRN-5, CRN-6, CRN-7, CRN-8 | CU-01, CU-02, CU-03, CU-04, CU-06, QA-1, QA-2, QA-3, QA-4, QA-5, QA-6, QA-7, QA-8, QA-11, QA-12, R-2, R-6, CRN-2, CRN-3 | CRN-1 |

### 7.3 Evaluación final

- **¿Se alcanzó el objetivo del paso 2?** Sí. La estructura general quedó fijada (ADR-0001 a ADR-0004 y 6.2) y QA-1 y QA-5 cuentan con buffer, replay y outbox a nivel de decisiones. Sus medidas no están verificadas, por eso quedan en Parcial (7.2).

### Resultado de la iteración

Trotamundos queda estructurado en tres desplegables (Panel API, Ingesta y Analítica), con broker de log persistente, workers con outbox y un read model en una instancia PostgreSQL propia para el panel (ADR-0001 a ADR-0004); la base se fija en PostgreSQL por ser la tecnología que el equipo conoce. CRN-1 queda Abordado y el resto de los drivers tocados en Parcial. Quedan sin verificar QA-1, QA-5 y los picos del panel, y sin definir el modelo canónico y las interfaces externas.

