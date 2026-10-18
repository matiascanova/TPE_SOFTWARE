# Iteración 1 de ADD: estructura general de Trotamundos

- **Estado:** Borrador para revisión
- **Fecha:** 14/10/2026
- **Equipo:** grupo TPE
---

## 1. Resumen ejecutivo

Esta iteración fija la estructura global de Trotamundos. Se eligió una arquitectura en capas/hexagonal con **tres desplegables** (Panel API, workers de ingesta y consumidor de analítica) comunicados por un **broker con log persistente y outbox** (alternativa C-lite del ADR-0001).

La estructura cubre bien los drivers de prioridad Alta que dependen de la ingesta (QA-1, QA-5, QA-8) y deja la evolución hacia más servicios como camino previsto, sin reescritura.

## 2. Objetivo de la iteración y drivers seleccionados

**Objetivo.** Establecer la estructura global del sistema (estilo arquitectónico y almacenamiento operacional) de modo que los drivers de mayor prioridad y dificultad tengan un camino de cumplimiento defendible y el diseño sea implementable por el grupo en el plazo del TPE.

Al tratarse de un desarrollo nuevo, el elemento a refinar es el **sistema completo**, que en esta iteración se descompone en desplegables y almacenes de apoyo.

*Selección inferida de los ADR; si el equipo eligió otro conjunto, ajustar esta tabla.*

| Driver | Motivo de la selección |
|---|---|
| CU-01, CU-02, CU-03, CU-04 | Funcionalidad primaria. Concentran la mayor parte de los requisitos de calidad. |
| QA-1, QA-4, QA-6 | Prioridad Alta y dificultad Alta. Condicionan el estilo arquitectónico. |
| QA-5, QA-8 | Resiliencia e interoperabilidad con proveedores. Determinan el broker, el outbox y los adaptadores. |
| QA-2, QA-3, QA-7, QA-10, QA-11, QA-12 | Intervienen en la decisión de estilo y de almacenamiento. Se abordan de forma parcial. |
| R-2, R-4 | Restricciones que moldean la integración (adaptadores) y la distribución de datos por región. |
| CU-05 | Secundario. Solo se diseña su aislamiento por eventos. |

**Fuera de alcance de la iteración:** QA-9 (depende de la calidad de los extractores y no discrimina entre estilos), R-1 y R-5.

## 3. Conceptos de diseño

En esta primera iteración se documentan solo los conceptos que se desprenden directamente de los ADR. Las tácticas de detalle que enumera el ADR-0001 (circuit breaker, reintentos, cola de mensajes inválidos y otras) no se repiten acá.

| Concepto | Drivers | ADR | Alternativas descartadas |
|---|---|---|---|
| Arquitectura en capas/hexagonal con 3 desplegables (C-lite) | QA-1, QA-4, QA-5, QA-6, QA-7, QA-12 | 0001 | Monolito modular (A), microservicios (B), híbrida de 5-6 servicios (C), serverless (D), capas/N-tier (E) |
| Broker con log persistente y outbox, como parte de la definición de C-lite | QA-1, QA-5 | 0001, 0002 (tabla de outbox) | E sin log persistente ni outbox |
| PostgreSQL con núcleo tipado y `detalle` en JSONB | QA-11, QA-12, R-4, outbox transaccional | 0002 | MySQL/MariaDB, MongoDB, DynamoDB/Cassandra |

### 3.1 Conceptos fundamentales sin ADR propio

La vista simplificada muestra elementos que sostienen la estructura pero que los ADR de esta iteración solo mencionan o dejan fuera de alcance:

- **Broker con log persistente.** Es el conector central entre ingesta, panel y analítica. La vista deja abierta la tecnología (Redis Streams o RabbitMQ Streams) y el ADR-0001 solo lo incluye como parte de C-lite.
- **Adaptadores y modelo canónico.** El ADR-0002 depende de la uniformidad del modelo canónico para justificar el núcleo relacional, y la vista agrupa los adaptadores dentro de los workers de ingesta.

## 4. Elementos instanciados y responsabilidades

| Elemento | Responsabilidades | Comunicación |
|---|---|---|
| **Panel API** | Lecturas del panel desde el read model (CU-01), gestión manual de reservas (CU-06), compartir itinerarios (CU-04), autenticación. Actualiza el read model a partir de eventos. | HTTP con clientes web y móvil. Escribe en la BD. Consume del broker. |
| **Workers de ingesta** | Adaptadores de proveedores de viaje (CU-02) y de correo (CU-03), filtros y whitelists (CU-08). Traducen al modelo canónico. Guardan el cambio y el evento en la BD (outbox). | Reciben de proveedores de viaje y por push de proveedores de correo. Leen tokens de la bóveda. Publican en el broker. |
| **Consumidor de analítica** | Consume eventos y los carga en el almacén OLAP, sin tocar la base operacional (CU-05). | Consume del broker. Escribe en OLAP. |
| **Broker con log persistente** | Buffer de picos, replay tras interrupciones y entrega de eventos. | Producción desde ingesta. Consumo por Panel API y analítica. |
| **BD** | Modelo de escritura: usuarios, viajes, reservas, permisos, whitelists, auditoría y outbox. | Panel API y workers. |
| **Read model en caché** | Respuestas rápidas del panel y última versión conocida ante caídas de proveedores. | Lo alimenta el Panel API desde eventos. |
| **Almacén OLAP** | Consultas analíticas del equipo de datos. | Lo alimenta el consumidor de analítica. |
| **Bóveda de tokens** | Custodia aislada de los tokens de acceso al correo. | Solo los workers de ingesta. |

Las interfaces concretas (contratos de eventos, modelo canónico y API del panel) quedan para las próximas iteraciones.

## 5. Consecuencias negativas de las decisiones

Resumen consolidado de los riesgos aceptados en el ADR-0001 y el ADR-0002. Cuando ambos ADR registran la misma consecuencia, se unifica en una sola fila.

| ID | Consecuencia negativa | Efecto | Drivers afectados | Mitigación prevista | ADR |
|---|---|---|---|---|---|
| C-1 | Cobertura parcial de QA-4 | Un bug en el código de dominio compartido o en la base primaria puede afectar a más de un desplegable. | QA-4 | Multi-AZ, failover automático de la base y despliegues graduales con rollback. Documentar el límite en los ADR de despliegue. | 0001 |
| C-4 | Base relacional compartida entre Panel API y workers | Cuello de botella de escritura a 15 M de cuentas y acoplamiento por esquema. | QA-6 | Read model separado para las lecturas. Cada módulo es dueño de sus tablas y el acceso cruzado va por vistas o por eventos. Migraciones versionadas. Particionado por usuario o región como evolución. | 0001 |
| C-5 | Mayor radio de impacto en la ingesta | Un adaptador defectuoso puede afectar a los workers y amenaza la integración con el resto de los proveedores. | QA-8 | Aislamiento por adaptador dentro del worker: colas, pools y circuit breaker por proveedor. | 0001 |
| C-6 | Fugas entre capas por presión de plazo | La arquitectura documentada deja de coincidir con el código. | Trazabilidad del TPE | Reglas de dependencia verificadas automáticamente en CI (por ejemplo ArchUnit o dependency-cruiser). | 0001 |
| C-7 | Un broker más para operar | Complejidad operativa y un nuevo componente crítico. | QA-1, QA-4, QA-5 | Broker gestionado o clusterizado en producción. Una sola instancia en el prototipo. | 0001 |

## 6. Estado de los drivers al cierre

Evaluación para esta documentación, con tres estados: no abordado, parcialmente abordado y abordado.

| Driver | Estado | Fundamento | Pendiente |
|---|---|---|---|
| QA-1 | Parcial | Log persistente, outbox y read model alimentado por eventos. | Tecnología del broker y diseño del read model. |
| QA-2, QA-3 | Parcial | El panel lee del read model y no de la BD operacional. | Diseño del read model. QA-3 depende además del cliente. |
| QA-4 | Parcial | Multi-AZ, failover automático y despliegue gradual. | C-1, C-2, C-3 y C-7. ADR de despliegue. |
| QA-5 | Parcial | Última versión conocida en el read model y replay por log. | Política de recuperación dentro de los 5 min. |
| QA-6 | Parcial | Ingesta escalable por separado y read model. | C-2 y C-4. |
| QA-7 | Parcial | Workers escalables de forma independiente. | Pipeline de ingesta de correo. |
| QA-8 | Parcial | Adaptadores, aislamiento por proveedor y cola de inválidos. | C-5. Modelo canónico y contratos de adaptadores. |
| QA-10 | No abordado | Solo se reserva una bóveda aislada. | ADR de bóveda y revocación de acceso. |
| QA-11 | Parcial | Restricciones de integridad y row-level security como refuerzo. | Diseño de autorización. |
| R-2 | Abordado a nivel de estructura | Adaptadores por proveedor. | Contratos concretos. |
| R-4 | Parcial | Instancia por región y particionado. | C-9. ADR de multi-región. |
| CU-05 | Parcial | Aislamiento por eventos hacia un almacén propio. | Elección del almacén OLAP. |

## 7. Decisiones pendientes y próximas iteraciones

ADR necesarios, derivados de las mitigaciones y de los drivers parciales:

1. Conceptos de diseño cruciales: broker con log persistente y outbox, CQRS con read model, modelo canónico y adaptadores (sección 4.1).
2. Despliegue, multi-AZ y objetivo de failover (C-1, C-3).
3. Multi-región y residencia de datos (C-9).
4. Bóveda de tokens y revocación de acceso (QA-10).
5. Pipeline de ingesta de correo (QA-7).
6. Almacén OLAP (CU-05).

Propuesta de orden:

- **Iteración 2: ingesta y eventos.** Broker, modelo canónico, adaptadores y pipeline de correo (QA-1, QA-5, QA-7, QA-8, QA-12).
- **Iteración 3: panel y disponibilidad.** Read model, despliegue, failover y seguridad de la revocación (QA-2, QA-3, QA-4, QA-6, QA-10).
