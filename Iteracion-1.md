# Iteración 1 de ADD: estructura general de Trotamundos

- **Estado:** Borrador para revisión
- **Fecha:** 19/10/2026 (versión 2, ver historial al final)
- **Equipo:** grupo TPE
---

## 1. Resumen ejecutivo

Esta iteración fija la estructura global de Trotamundos. Se eligió una arquitectura en capas/hexagonal con **tres desplegables** (Panel API, ingesta y carga de analítica). La ingesta y el Panel API se comunican por un **broker con log persistente y outbox**, y la analítica se alimenta por **copia periódica** desde la base operacional hacia un almacén propio (alternativa C-lite del ADR-0001, con los ADR-0002 a 0004).

La estructura cubre bien los drivers de prioridad Alta que dependen de la ingesta (QA-1, QA-5) y deja la evolución hacia más servicios como camino previsto, sin reescritura.

## 2. Objetivo de la iteración y drivers seleccionados

**Objetivo.** Establecer la estructura global del sistema (estilo arquitectónico, comunicación entre desplegables y aislamiento de la analítica) de modo que los drivers de mayor prioridad y dificultad tengan un camino de cumplimiento defendible y el diseño sea implementable por el grupo en el plazo del TPE.

Al tratarse de un desarrollo nuevo, el elemento a refinar es el **sistema completo**, que en esta iteración se descompone en desplegables y almacenes de apoyo.

*Selección inferida de los ADR; si el equipo eligió otro conjunto, ajustar esta tabla.*

| Driver | Motivo de la selección |
|---|---|
| CU-01, CU-02, CU-03, CU-04 | Funcionalidad primaria. Concentran la mayor parte de los requisitos de calidad. |
| QA-1, QA-6 | Prioridad Alta y dificultad Alta. Condicionan el estilo arquitectónico. |
| QA-2, QA-3, QA-7 | Intervienen en la decisión de estilo. Se abordan de forma parcial o quedan pendientes. |
| R-2, R-6 | Restricciones que moldean la integración (adaptadores). |
| CRN-3 |Solo se diseña su aislamiento del sistema operativo. |
| CRN-1 | Se moldea el concepto general del sistema (ADR-0001). |

## 3. Conceptos de diseño

En esta primera iteración se documentan solo los conceptos que se desprenden directamente de los ADR. Las tácticas de detalle que enumera el ADR-0001 (circuit breaker, reintentos, cola de mensajes inválidos y otras) no se repiten acá.

| Concepto | Drivers | ADR | Alternativas descartadas |
|---|---|---|---|
| Arquitectura en capas/hexagonal con 3 desplegables (C-lite) | QA-1, QA-4, QA-5, QA-6, QA-7 | 0001 | Monolito modular (A), microservicios (B), híbrida de 5-6 servicios (C), serverless (D), capas/N-tier (E) |
| Workers de ingesta asíncronos, con un adaptador por proveedor y por medio de transporte | QA-1, QA-5, QA-6, QA-7, QA-8, QA-12 | 0002 | Procesar en el receptor, un servicio por proveedor, funciones serverless por evento |
| Outbox transaccional con relay por polling | QA-1, QA-4, QA-5, QA-8 | 0002 | Doble escritura directa, publicar primero y guardar después, captura de cambios (CDC), transacción distribuida |
| Broker con log persistente, con un stream de trabajo (buffer de entrada) y uno de eventos | QA-1, QA-4, QA-5, QA-6, QA-7, QA-8, QA-12 | 0003 | Comunicación síncrona directa, base de datos como bus, cola durable simple, plataforma de streaming completa |

## 4. Elementos instanciados y responsabilidades

| Elemento | Responsabilidades | Comunicación |
|---|---|---|
| **Panel API** | Lecturas del panel (CU-01), gestión manual de reservas (CU-06), compartir itinerarios (CU-04), autenticación. | HTTP con clientes web y móvil. Lee y escribe en la BD. Consume el stream de eventos del broker; qué hace con esos eventos se define en una iteración posterior. |
| **Ingesta: receptor de notificaciones** | Recibe las notificaciones push de proveedores de viaje y de correo, verifica su autenticidad, las encola y responde. | Recibe de los proveedores. Publica en el stream de trabajo del broker. |
| **Ingesta: workers** | Adaptadores de proveedores de viaje (CU-02) y de correo (CU-03), filtros y whitelists (CU-08). Traducen al modelo canónico. Guardan el cambio y el evento en la BD en una sola transacción (outbox). | Toman trabajo del stream de trabajo. Consultan a los proveedores. Leen tokens de la bóveda. Escriben en la BD. |
| **Ingesta: relay del outbox** | Publica en el stream de eventos los eventos pendientes del outbox. | Lee de la BD. Publica en el broker. |
| **Carga de analítica** | Tarea programada que copia datos de forma incremental desde la BD al almacén analítico, seudonimizando y excluyendo los campos personales innecesarios (CU-05). | Lee de la BD por vistas de solo lectura. Escribe en el almacén analítico. No usa el broker. |
| **Broker con log persistente** | Dos streams: de trabajo (buffer de entrada) y de eventos (hacia el panel). Buffer de picos, replay dentro de la retención y reintentos. | Producción: receptor y relay. Consumo: workers y Panel API. |
| **BD** | Usuarios, viajes, reservas, permisos, whitelists, auditoría y outbox. Motor por definir. | Panel API, workers, relay y carga de analítica. |
| **Almacén analítico (OLAP)** | Consultas analíticas del equipo de datos, sobre datos seudonimizados. Motor por definir. | Lo alimenta la carga de analítica. |
| **Bóveda de tokens** | Custodia aislada de los tokens de acceso al correo. | Solo los workers de ingesta. |

Las interfaces concretas (contratos de trabajo y de eventos, modelo canónico y API del panel) quedan para las próximas iteraciones.

## 5. Consecuencias negativas de las decisiones

Resumen consolidado de los riesgos aceptados en los ADR-0001 a 0004. Cuando varios ADR registran la misma consecuencia, se unifica en una sola fila. Los identificadores B, W y A son los de cada ADR (ADR-0003, ADR-0002 y ADR-0004).

| ID | Consecuencia negativa | Efecto | Drivers afectados | Mitigación prevista | ADR |
|---|---|---|---|---|---|
| C-1 | Cobertura parcial de QA-4 | Un bug en el código de dominio compartido o en la base primaria puede afectar a más de un desplegable. | QA-4 | Multi-AZ, failover automático de la base y despliegues graduales con rollback. Documentar el límite en los ADR de despliegue. | 0001 |
| C-4 | Base relacional compartida entre Panel API, workers y carga de analítica (ver W-2) | Cuello de botella de escritura a 15 M de cuentas y acoplamiento por esquema. | QA-6 | Cada módulo es dueño de sus tablas y el acceso cruzado va por vistas o por eventos. Migraciones versionadas. Particionado por usuario o región como evolución. La separación de las lecturas del panel queda para una iteración posterior. | 0001, 0002 |
| C-5 | Mayor radio de impacto en la ingesta (ver W-1) | Un adaptador defectuoso puede afectar a los workers y amenaza la integración con el resto de los proveedores. | QA-8 | Aislamiento por adaptador dentro del worker: colas, pools y circuit breaker por proveedor. | 0001, 0002 |
| C-6 | Fugas entre capas por presión de plazo | La arquitectura documentada deja de coincidir con el código. | Trazabilidad del TPE | Reglas de dependencia verificadas automáticamente en CI (por ejemplo ArchUnit o dependency-cruiser). | 0001 |
| C-7 | Un broker más para operar (ver B-7) | Complejidad operativa y un nuevo componente crítico. | QA-1, QA-4, QA-5 | Broker gestionado o clusterizado en producción. Una sola instancia en el prototipo. | 0001, 0003 |
| B-1 | Consistencia eventual y dos saltos entre la notificación y el panel | Parte del presupuesto de 5 minutos de QA-1 (y de 15 minutos de QA-7 en el correo) se consume en el camino. | QA-1, QA-7 | Medir el retraso de consumo de cada stream con alertas y repartir el presupuesto por etapa. | 0003 |
| B-4 | El log no recupera lo que el proveedor nunca entregó | QA-5 no se cumple solo con el log. | QA-5 | Los adaptadores reconcilian con el proveedor al restablecerse la API. | 0003 |
| B-6 | Datos personales en el log y en el outbox | Conflicto con borrados y con límites de residencia por región. | R-4 | Mensajes mínimos con referencias, retención acotada y cifrado en reposo. Definir el borrado en el ADR de multi-región. | 0002, 0003 |
| B-10 | El broker queda en el camino de entrada | Si cae, el receptor no puede encolar notificaciones nuevas. | QA-4 | Broker clusterizado y multi-AZ. El receptor responde con error para que el proveedor reintente. Reconciliación al volver. | 0003 |
| B-11 | La ventaja del log sobre una cola depende de usar el replay | Si no se usa, se paga un log sin necesitarlo. | Evaluación de la decisión | Revisar en la Iteración 2. El puerto de mensajería permite pasar a una cola durable sin tocar el núcleo. | 0003 |
| W-3 | La tabla outbox es la más escrita | Cada cambio implica dos escrituras y la tabla genera basura al borrar filas. | QA-6 | Partición por tiempo y descarte de particiones viejas. Evolución a captura de cambios (CDC). | 0002 |
| W-5 | El relay es un componente más y puede detenerse | Los eventos se acumulan y el panel deja de actualizarse sin que falle otro componente. | QA-1 | Monitorear la antigüedad de la fila pendiente más vieja, con alerta. Reinicio automático. | 0002 |
| A-1 | Analítica con datos diferidos | Las consultas reflejan el estado de la última carga y no sirven para tiempo real. | CU-05 | CU-05 no tiene requisito de frescura. Frecuencia de carga configurable. | 0001, 0004 |
| A-2 | Analítica solo con el estado actual, sin historia fina | No se pueden analizar secuencias de cambios de una misma reserva. | CU-05 | Usar la tabla de auditoría como fuente adicional. Evolución a alimentar la analítica desde el stream de eventos. | 0004 |
| A-3 | La carga de analítica lee la base operacional | Carga adicional sobre la base compartida. | QA-1, QA-2, QA-6 | Carga incremental, fuera de horas pico y con ritmo limitado. Réplica de lectura en producción. | 0001, 0004 |
| A-6 | Datos personales en el almacén analítico | Residencia por región y pedidos de borrado. | R-4 | Seudonimización y minimización en la carga. Carga y almacén por región. Propagar los borrados de usuarios. | 0004 |

## 6. Estado de los drivers al cierre

Evaluación para esta documentación, con tres estados: no abordado, parcialmente abordado y abordado.

| Driver | Estado | Fundamento | Pendiente |
|---|---|---|---|
| QA-1 | Parcial | Buffer de entrada, outbox y log persistente hacia el panel. | Tecnología del broker, intervalo del relay y presupuesto de tiempo por etapa (B-1). |
| QA-2, QA-3 | Parcial | La analítica no compite con el panel por la base (ADR-0004). | Diseño de las lecturas del panel. QA-3 depende además del cliente. |
| QA-4 | Parcial | Multi-AZ, failover automático y despliegue gradual. La ingesta sigue guardando si cae el broker. La analítica no puede afectar a la base operacional. | C-1, C-2, C-3 y C-7. B-10. ADR de despliegue. |
| QA-5 | Parcial | La última versión conocida queda en la BD. Outbox sin pérdida y replay dentro de la retención. | Reconciliación con el proveedor (B-4), valor de retención y política de recuperación dentro de los 5 min. |
| QA-6 | Parcial | Ingesta escalable por separado, buffer de entrada y analítica aislada. | C-2 y C-4. Dimensionamiento. |
| QA-7 | Parcial | Workers escalables de forma independiente y buffer de entrada. | Pipeline de ingesta de correo. |
| QA-8 | Parcial | Adaptadores, aislamiento por proveedor y stream de inválidos. | C-5. Modelo canónico y contratos de adaptadores. |
| QA-10 | Parcial | Los workers verifican la revocación en la bóveda al tomar cada trabajo. | ADR de bóveda y revocación de acceso. |
| QA-11 | No abordado | Sin diseño de autorización en esta iteración. | Diseño de autorización. |
| QA-12 | Parcial | Adaptadores por puertos dentro de los workers. | Modelo canónico y contratos. |
| R-2 | Abordado a nivel de estructura | Adaptadores por proveedor en los workers. | Contratos concretos. |
| R-4 | Parcial | Seudonimización en la carga analítica y mensajes mínimos en el log y el outbox. Instancia por región y particionado como evolución. | C-9. B-6 y A-6. ADR de multi-región. |
| CU-05 | Parcial | Aislamiento con almacén propio y copia periódica. | Elección del motor del almacén (ADR-0004, sección 6). |

## 7. Decisiones pendientes y próximas iteraciones

ADR necesarios, derivados de las mitigaciones y de los drivers parciales:

1. Conceptos de diseño cruciales: modelo canónico y contratos de adaptadores (ver 3.1).
2. Contratos de mensajes del stream de trabajo y del de eventos (ADR-0003, sección 7).
3. Tecnología del broker (ADR-0003, sección 6).
4. Pipeline de ingesta de correo (QA-7).
5. Despliegue, multi-AZ y objetivo de failover, incluida la alta disponibilidad del broker (C-1, C-3, B-10).
6. Multi-región y residencia de datos, incluido el borrado sobre el log y el almacén analítico (C-9, B-6, A-6).
7. Bóveda de tokens y revocación de acceso (QA-10).
8. Almacenamiento operacional y lecturas del panel: motor de la base, partición del outbox, vistas de solo lectura y tabla de auditoría.
9. Motor del almacén analítico (CU-05, ADR-0004 sección 6), cuando el volumen lo justifique.

Propuesta de orden:

- **Iteración 2: ingesta y eventos.** Tecnología del broker, modelo canónico, adaptadores, contratos de mensajes y pipeline de correo (QA-1, QA-5, QA-7, QA-8, QA-12).
- **Iteración 3: panel y disponibilidad.** Almacenamiento operacional y lecturas del panel, despliegue, failover y seguridad de la revocación (QA-2, QA-3, QA-4, QA-6, QA-10).

## Historial de cambios

- **v1 (14/10/2026):** versión inicial, con la analítica como consumidora del broker, un motor de base relacional definido y un read model en caché.
- **v2 (19/10/2026):** se alinea con el ADR-0001 v3, el ADR-0002 (workers y outbox), el ADR-0003 v2 y el ADR-0004. Se retiran de este documento el motor de la base relacional definido en la v1 y el read model en caché, que se definen en una iteración posterior. Se agregan el receptor, el relay y la carga de analítica a la tabla de elementos, y los riesgos de los nuevos ADR a la tabla de consecuencias.
