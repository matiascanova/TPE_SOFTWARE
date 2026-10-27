# ADR-0001: Estilo C-lite: capas/hexagonal con tres desplegables

- **Estado:** Aceptado
- **Drivers de entrada:** [Análisis inicial y drivers](Analisis-Inicial-y-Drivers.md) (CU-01 a CU-04; QA-1, QA-2, QA-5, QA-6, QA-7, QA-8; R-2, R-6; CRN-1, CRN-3)

---

## 1. Contexto

Hay que fijar la estructura global de Trotamundos (CRN-1), porque condiciona broker, almacenamiento, despliegue y seguridad. El panel (2 M de usuarios activos semanales, picos ×10) se resuelve con caché y un read model (ADR-0004), así que la dificultad está en la ingesta: el correo de 15 M de cuentas (QA-7) y los eventos de proveedores heterogéneos llegan con picos impredecibles, deben alcanzar el panel en menos de 5 minutos con 99 % de recuperación tras una interrupción (QA-1, QA-5), sin que un proveedor defectuoso afecte a los demás (QA-8) y con un adaptador por proveedor (R-2). A la vez, el panel debe responder rápido también en picos (QA-2, QA-6) y la analítica debe quedar aislada del sistema operativo (CRN-3). Todo debe construirse en un plazo acotado y con código consistente con la arquitectura documentada, lo que hace de la viabilidad (R-6) un criterio de primer orden.

**Pregunta:** ¿Qué estilo arquitectónico general adopta Trotamundos?

**Fuera de alcance:** el broker (ADR-0003), los workers y el outbox (ADR-0002), el read model (ADR-0004), el motor de la base (decisión de almacenamiento posterior, Iteración 2), el despliegue, la seguridad y el diseño interno de la analítica (iteraciones futuras).

---

## 2. Decisión

**Se elige C-lite: arquitectura en capas/hexagonal con tres desplegables (Panel API, ingesta y analítica), con la ingesta y el Panel API comunicados por un broker con log persistente y un outbox (ADR-0003, ADR-0002).**

- Cada desplegable se organiza en capas (presentación, aplicación, dominio e infraestructura): el dominio depende de puertos y la tecnología vive en infraestructura, con un adaptador por proveedor y por medio de transporte que traduce al modelo canónico (R-2).
- La ingesta es un solo artefacto con tres roles (receptor, workers y relay) que corren como procesos separados y escalan por separado, más un planificador de sondeo para el correo (Iteración 3).
- La analítica queda fuera del flujo de ingesta y del panel: no accede a la base operacional ni al log y trabaja sobre un almacén analítico propio, alimentado con una copia periódica (CRN-3).

**Incorpora:** de capas/N-tier, la organización interna en capas. De los microservicios, el buffer de entrada con replay por log persistente, el outbox transaccional, CQRS solo en el panel, circuit breaker, reintentos con backoff, cola de mensajes inválidos y el escalado independiente de la ingesta. **Difiere:** separar notificaciones y procesador de itinerarios en servicios propios, y la ingesta de correo de la de proveedores. Queda como evolución prevista sin reescritura, porque los límites internos ya son módulos con puertos.

### Alternativas descartadas

| Alternativa | Motivo |
|---|---|
| Capas / N-tier (capas lógicas estrictas desplegadas como tiers: web/API, workers y datos) | Es la más cercana, pero incumple QA-1, QA-5, QA-2 y QA-6/7. Sin log persistente ni outbox por defecto el replay es limitado y el 99 % de QA-5 difícil de garantizar. Panel, ingesta y analítica comparten modelo y base de escritura: las lecturas hacen joins sobre ese modelo y la presión de la ingesta llega a la base. Con un broker, un outbox y un read model pasa a ser C-lite, con un costo adicional pequeño. |
| Monolito modular (una aplicación con N réplicas, base con réplicas de lectura, Redis y cola simple) | Es la más simple y rápida de construir, pero incumple QA-1, QA-5 y QA-6: la ingesta compite con el panel por recursos, un despliegue defectuoso tira todo, no hay log para replay y se escala todo junto. |
| Microservicios, completos o híbrida de 5-6 servicios | Incumplen R-6: varios desplegables con contratos propios, consistencia eventual en todas partes, trazabilidad distribuida y, en la versión completa, service mesh, saga y multi-región. Un subconjunto implementado agrava la brecha entre arquitectura documentada y código, y su ventaja en QA-6 no se puede demostrar con un prototipo acotado. Los servicios que la híbrida separa de más (notificaciones, procesador de itinerarios, ingesta de correo y de proveedores) escalan con patrones de carga parecidos y los adaptadores ya aíslan colas, pools y circuit breaker dentro del worker (QA-8), así que separarlos suma costo de integración sin mejorar un QA. |
| Serverless (funciones disparadas por colas y webhooks, API Gateway y NoSQL gestionado) | Incumple R-6 y amenaza QA-2: acopla a un proveedor cloud, exige cuenta cloud para todo el grupo, arrastra riesgo de costos y los arranques en frío afectan la latencia (mitigable con concurrencia provisionada, a mayor costo). Es difícil de ejecutar y demostrar localmente. |

---

## 3. Consecuencias

**Positivas**

- Cobertura defendible de QA-1, QA-5, QA-6 y QA-7: buffer de entrada y de picos con replay, e ingesta escalable por separado.
- El código refleja la documentación casi uno a uno: tres desplegables, capas visibles y adaptadores por proveedor (R-2, R-6).
- Es implementable por un grupo con plazo acotado: tres desplegables y cuatro piezas de apoyo (base, broker, Redis y almacén analítico), ejecutables con docker-compose y proveedores simulados (R-6).
- La evolución a más servicios no es una reescritura (R-6, CRN-1).

**Negativas y riesgos aceptados**

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| ES-1 | Radio de impacto mayor en la ingesta: un adaptador defectuoso puede afectar a los workers. | Puede interrumpir la integración con el resto de los proveedores (QA-8). | Aislamiento por adaptador dentro del worker: colas, pools y circuit breaker por proveedor (ADR-0002). |
| ES-2 | Fugas entre capas: el dominio termina dependiendo de infraestructura. | Se pierde la consistencia entre código y arquitectura documentada (R-6). | Reglas de dependencia verificadas en CI. |
| ES-3 | Base relacional compartida entre Panel API, workers y la copia hacia la analítica. | Cuello de botella de escritura a 15 M de cuentas (QA-7). | Read model separado para las lecturas (ADR-0004). Copia analítica desde una réplica o en ventanas controladas. Motor y particionado: decisión de almacenamiento posterior. |

