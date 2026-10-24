# ADR-0004: Read model del panel en almacén clave-valor en memoria (CQRS)

- **Estado:** Propuesto
- **Decisores:** Equipo de diseño (grupo TPE)
- **Drivers de entrada:** [Análisis inicial y drivers](Analisis-Inicial-y-Drivers.md) (CU-01, CU-04, CU-06; QA-1, QA-2, QA-3, QA-4, QA-6, QA-11; R-6; CRN-2)
- **ADR relacionados:** ADR-0001 (estilo C-lite), ADR-0002 (workers y outbox), ADR-0003 (broker)

---

## 1. Contexto y problema

El ADR-0001 usa la separación del read model como argumento frente a la alternativa E (capas / N-tier) y remite a este documento. Los ADR-0002 y ADR-0003 asumen que el Panel API consume el stream de eventos para mantenerlo al día. Este documento justifica el read model y explica cómo se mantiene. Responde dos preguntas:

1. **¿Desde dónde lee el panel:** del modelo de escritura o de una copia derivada?
2. **¿Cómo se mantiene esa copia al día** con los eventos de la ingesta y con los cambios propios del Panel API?

**Tensiones:**

- **Latencia y picos de lectura.** El panel concentra la mayor parte del tráfico: 2 M de usuarios activos semanales, con picos ×10, 800 ms de respuesta (QA-2) y 1,4 s hasta el primer contenido (QA-3). Las lecturas del panel (CU-01) son las que tienen que escalar (QA-6).
- **Frescura.** Los cambios tienen que llegar al panel dentro del presupuesto de 5 minutos (QA-1), y el stream de eventos (ADR-0003) necesita un uso concreto en el Panel API.
- **Permisos y continuidad.** Los itinerarios compartidos (CU-06) deben respetar la lista de personas autorizadas (QA-11), y el panel debe seguir sirviendo si la base o el broker fallan (QA-4).
- **Conocimiento del equipo.** El equipo domina Java, PostgreSQL, C++ y Python (CRN-2). Con plazo acotado, la viabilidad pesa más que la novedad tecnológica (R-6).

**Fuera de alcance:** el motor de la base operacional y su partición (decisión de almacenamiento posterior), el mecanismo de workers y outbox (ADR-0002), el broker y sus streams (ADR-0003) y la resolución del compartir entre regiones (ADR de multi-región).

---

## 2. Criterios de decisión

| Criterio | Qué mide en esta decisión |
|---|---|
| QA-2 / QA-3 | Que el panel responda dentro de los tiempos objetivo, también en picos. |
| QA-6 | Escalar las lecturas sin competir con las escrituras de la ingesta. |
| QA-1 | Que un cambio guardado llegue al panel con poca demora. |
| QA-4 | Que el panel siga sirviendo si fallan la base o el broker. |
| QA-11 | Que una persona no autorizada no vea un itinerario compartido. |
| R-6 | Que el equipo pueda implementarlo y demostrarlo en el plazo. |
| CRN-2 | Que se apoye en tecnologías que el equipo conoce, o que su curva de aprendizaje sea corta. |

---

## 3. Decisión

### 3.1 Alternativas consideradas

| ID | Alternativa | Resumen |
|---|---|---|
| M1 | Sin read model | El panel lee directo de la base operacional, con índices. |
| M2 | Tablas de lectura en la base relacional | Tablas desnormalizadas en la misma base (o en una réplica), mantenidas por el Panel API a partir de eventos. |
| **M3** | **Almacén clave-valor en memoria** | Un documento por viaje y un índice por usuario en Redis, mantenidos por el Panel API a partir de eventos y reconstruibles desde la base. |
| M4 | Base documental propia | MongoDB con persistencia propia, mantenida a partir de eventos. |

### 3.2 Decisión

**Se elige M3: el read model es un almacén clave-valor en memoria (Redis), con un documento por viaje y un índice por usuario, que el Panel API mantiene a partir de los eventos de la ingesta y de sus propios cambios manuales. El panel lee solo del read model.**

**Descripción de la decisión.** El read model es una copia derivada y desnormalizada (con datos repetidos para leer sin joins) de lo que el panel necesita mostrar. La base guarda el modelo de escritura y el panel lee del read model, al que el dominio accede por un puerto: pasar a M2 o a M4 no toca el núcleo (ADR-0001).

- **Fuente de verdad y lecturas.** La base es la fuente de verdad: toda entrada del read model se puede reconstruir desde ella. Si falta la entrada (primer acceso, expulsión o pérdida), el panel la reconstruye desde la base y la guarda, incrementando consistencia.
- **Actualización por eventos.** Como siempre lee el estado actual desde la base, reprocesar el mismo evento o recibirlo fuera de orden no tiene efecto. Los eventos de un mismo viaje que llegan en una ventana corta se resuelven con una sola lectura de la base.
- **Escritura condicional por versión y cambios manuales.** Un documento solo se reemplaza si la versión nueva es mayor o igual a la guardada, con una operación atómica, así los dos escritores no se pisan. Los escritores son los eventos y los cambios manuales (CU-04): estos últimos el Panel API los guarda en la base y después actualiza el read model directamente, para que el viajero vea su propio cambio sin esperar.
- **Permisos de compartir.** El Panel API verifica en cada acceso a un itinerario compartido que la persona esté en la lista del documento. Ante un cambio de permisos, **borra** las entradas afectadas en lugar de actualizarlas: se reconstruyen desde la base en la próxima lectura.
- **Memoria y recuperación.** La memoria es acotada, con expulsión por uso (LRU: se descartan primero las entradas menos usadas recientemente): los usuarios inactivos se reconstruyen bajo demanda.

### 3.3 Cómo funciona

**Caso práctico.** Siguiendo el ejemplo del ADR-0002: el worker guardó la reserva del hotel (versión 4 del viaje) y escribió el evento en el outbox, y el relay lo publicó en el stream de eventos.

1. El Panel API recibe el evento `ReservaCreada` del viaje 123, versión 4.
2. Lee el viaje 123 de la base, por clave indexada.
3. Escribe el documento `viaje:123` en el read model, solo si su versión es mayor o igual a la guardada.
4. Actualiza el índice del usuario, que incluye ese viaje.

Cuando el viajero abre el panel, el Panel API lee su índice y los documentos de sus viajes del read model, sin tocar la base.

| Qué falla | Qué pasa | Resultado |
|---|---|---|
| Se pierde el read model (reinicio o falla). | Las lecturas reconstruyen cada entrada desde la base y corre la reconstrucción masiva. | El panel responde más lento un rato. No se pierde información. |
| La base está en failover. | Las entradas presentes se siguen sirviendo. Las ausentes no se pueden reconstruir hasta que vuelva. | El panel sigue disponible para los usuarios con entrada. |
| El broker está caído o el Panel API atrasado. | No llegan eventos nuevos y el read model conserva la última versión. Los eventos esperan en el stream. | Se degrada la frescura, no la disponibilidad. Al volver, se actualiza sin perder eventos (ADR-0003). |
| Falla la actualización del read model tras un cambio manual. | Se reintenta y, si no se logra, se borra la entrada. | La próxima lectura reconstruye desde la base. |
| Llega un evento duplicado o fuera de orden. | Se relee el estado actual y la escritura es condicional por versión. | Sin efecto. |

### 3.4 Por qué no las otras alternativas

- **M1 (sin read model).** Es la más simple, pero incumple QA-6 y QA-2: las lecturas del panel hacen joins sobre el modelo de escritura y compiten con la ingesta en un único primario. No sostiene los picos ×10.
- **M2 (tablas de lectura en la base relacional).** Es la que mejor cumple CRN-2 y R-6, porque se apoya en PostgreSQL y no suma tecnología. Pierde frente a M3 en QA-6 y QA-2: sigue consumiendo el mismo motor y el mismo primario, o una réplica con su propio retraso, y un único motor limita el crecimiento de las lecturas. Es la alternativa de reemplazo **si el equipo prefiere no sumar Redis**: se mantienen las reglas de actualización de la decisión y solo cambia el almacén.
- **M4 (base documental propia).** Es la mejor en QA-4, porque su persistencia propia permite servir el panel aunque la base esté caída. Se descarta por R-6 y CRN-2: es una tecnología nueva para el equipo. Queda como evolución si la dependencia de la base ante un failover prolongado resultara un problema (RM-2).

---

## 4. Consecuencias

### Positivas

- Las lecturas del panel quedan aisladas de las escrituras de la ingesta, con latencia y escalado de lectura propios (QA-2, QA-3, QA-6).
- El stream de eventos tiene un uso concreto, y la reconstrucción del read model desde la base cubre lo que quede fuera de la retención del log (ADR-0003).
- Si el broker o la base fallan, el panel sigue sirviendo la última versión conocida para los usuarios con entrada (QA-4).
- El acceso por puerto permite cambiar de almacén sin tocar el núcleo (ADR-0001, CRN-2).

### Negativas y riesgos aceptados

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| RM-1 | Read model con retraso y una lectura extra de la base por cada evento. | El cambio aparece en el panel después del evento y de la lectura de la base, y consume parte de los 5 minutos de QA-1. Además suma presión de lectura sobre la base compartida. | Medir el retraso de consumo con alertas, agrupar eventos por viaje, leer por clave indexada y repartir el presupuesto por etapa (ADR-0003, BR-1). Réplica de lectura si hace falta. |
| RM-2 | El read model depende de la base para reconstruirse. | Si se pierde o se expulsa una entrada mientras la base está en failover, ese usuario no se puede servir (QA-4). | Persistencia y réplica del read model, memoria dimensionada para los usuarios activos y reconstrucción priorizada. Evolución a M4 si no alcanza. |
| RM-3 | Dos escritores del read model (eventos y cambios manuales). | Pueden pisarse entre sí. | Escritura condicional por versión, con operación atómica. |
| RM-4 | Permisos desactualizados en el read model. | Una persona a la que se quitó el acceso a un itinerario compartido podría verlo si falla la actualización (QA-11). | Borrar la entrada ante un cambio de permisos en lugar de actualizarla, con reintentos. Verificar la lista de personas en cada acceso y registrar los rechazos en la auditoría. |
