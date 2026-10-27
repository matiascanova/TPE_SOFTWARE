# ADR-0004: Read model del panel en almacén clave-valor en memoria (CQRS)

- **Estado:** Propuesto
- **Drivers de entrada:** [Análisis inicial y drivers](Analisis-Inicial-y-Drivers.md) (CU-01, CU-04, CU-06; QA-1, QA-2, QA-3, QA-4, QA-6, QA-11; R-6; CRN-2)
- **ADR relacionados:** ADR-0001 (estilo C-lite), ADR-0002 (workers y outbox), ADR-0003 (broker)

---

## 1. Contexto

El panel concentra la mayor parte del tráfico: 2 M de usuarios activos semanales, con picos ×10, 800 ms de respuesta (QA-2) y 1,4 s hasta el primer contenido (QA-3). Esas lecturas deben escalar (QA-6) sin competir con las escrituras de la ingesta, y los cambios deben llegar al panel dentro de 5 minutos (QA-1). Los itinerarios compartidos deben respetar la lista de personas autorizadas (QA-11, CU-06) y el panel debe seguir sirviendo si fallan la base o el broker (QA-4). El equipo domina Java, PostgreSQL, C++ y Python (CRN-2) y el plazo es acotado, por lo que la viabilidad pesa más que la novedad tecnológica (R-6). El ADR-0001 usa el read model como argumento frente a capas / N-tier, y los ADR-0002 y ADR-0003 asumen que el Panel API consume el stream de eventos para mantenerlo.

**Pregunta:** ¿Desde dónde lee el panel y cómo se mantiene esa copia al día con los eventos de la ingesta y con los cambios manuales (CU-04)?

**Fuera de alcance:** el motor de la base operacional y su partición, el mecanismo de workers y outbox (ADR-0002), el broker y sus streams (ADR-0003) y el compartir entre regiones (ADR de multi-región).

---

## 2. Decisión

**Se elige un almacén clave-valor en memoria (Redis) como read model, con un documento por viaje y un índice por usuario, que el Panel API mantiene a partir de los eventos de la ingesta y de sus propios cambios manuales, y del que lee el panel (CQRS solo en el panel). El dominio accede por un puerto (ADR-0001).**

- La base es la fuente de verdad: toda entrada del read model se reconstruye desde ella, y el panel la reconstruye en la próxima lectura si falta.
- El Panel API actualiza el read model con cada evento, releyendo el estado actual de la base, y con sus cambios manuales. Reemplaza un documento solo si su versión nueva es mayor o igual a la guardada, con una operación atómica, de modo que duplicados y eventos fuera de orden no tienen efecto.
- En cada acceso a un itinerario compartido, el Panel API verifica que la persona esté en la lista del documento. Ante un cambio de permisos borra las entradas afectadas en lugar de actualizarlas.

### Alternativas descartadas

| Alternativa | Motivo |
|---|---|
| Sin read model (el panel lee de la base operacional) | Incumple QA-6 y QA-2: las lecturas hacen joins sobre el modelo de escritura y compiten con la ingesta en un único primario. No sostiene los picos ×10. |
| Tablas de lectura en la base relacional | Es la que mejor cumple CRN-2 y R-6, porque se apoya en PostgreSQL. Pierde en QA-6 y QA-2: sigue consumiendo el mismo motor y el mismo primario, o una réplica con retraso, y un único motor limita el crecimiento de las lecturas. Es el reemplazo si el equipo prefiere no sumar Redis: se mantienen las reglas de la decisión y cambia solo el almacén. |
| Base documental propia (MongoDB) | Es la mejor en QA-4, porque su persistencia propia permite servir el panel con la base caída. Incumple R-6 y CRN-2: es una tecnología nueva para el equipo. Queda como evolución si la dependencia de la base ante un failover prolongado resultara un problema (RM-2). |

---

## 3. Consecuencias

**Positivas**

- Las lecturas del panel quedan aisladas de las escrituras de la ingesta, con latencia y escalado propios (QA-2, QA-3, QA-6).
- El stream de eventos tiene un uso concreto, y la reconstrucción desde la base cubre lo que quede fuera de la retención del log (QA-1, ADR-0003).
- Si el broker o la base fallan, el panel sigue sirviendo la última versión conocida a los usuarios con entrada (QA-4).
- El acceso por puerto permite cambiar de almacén sin tocar el núcleo (CRN-2, ADR-0001).

**Negativas y riesgos aceptados**

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| RM-1 | Read model con retraso y una lectura extra de la base por cada evento. | El cambio aparece después del evento y de esa lectura, y consume parte de los 5 minutos de QA-1. Suma presión de lectura sobre la base compartida. | Medir el retraso de consumo con alertas, agrupar eventos por viaje, leer por clave indexada y repartir el presupuesto por etapa (ADR-0003, BR-1). Réplica de lectura si hace falta. |
| RM-2 | El read model depende de la base para reconstruirse. | Si se pierde o se expulsa una entrada mientras la base está en failover, ese usuario no se puede servir (QA-4). | Persistencia y réplica del read model, memoria dimensionada para los usuarios activos con expulsión por uso (los inactivos se reconstruyen bajo demanda) y reconstrucción priorizada. Evolución a la base documental si no alcanza. |
| RM-3 | Dos escritores del read model (eventos y cambios manuales). | Pueden pisarse entre sí. | Escritura condicional por versión, con operación atómica. |
| RM-4 | Permisos desactualizados en el read model. | Una persona a la que se quitó el acceso a un itinerario compartido podría verlo si falla la actualización (QA-11). | Borrar la entrada ante un cambio de permisos, con reintentos. Verificar la lista de personas en cada acceso y registrar los rechazos en la auditoría. |
