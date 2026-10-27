# ADR-0004: Read model del panel en una instancia PostgreSQL propia (CQRS)

- **Estado:** Aceptado
- **Drivers de entrada:** [Análisis inicial y drivers](Analisis-Inicial-y-Drivers.md) (CU-01, CU-04, CU-06; QA-1, QA-2, QA-3, QA-4, QA-6, QA-11; R-6; CRN-2)

---

## 1. Contexto

El panel concentra la mayor parte del tráfico: 2 M de usuarios activos semanales, con picos ×10, 800 ms de respuesta (QA-2) y 1,4 s hasta el primer contenido (QA-3). Esas lecturas deben escalar (QA-6) sin competir con las escrituras de la ingesta, y los cambios deben llegar al panel dentro de 5 minutos (QA-1). Los itinerarios compartidos deben respetar la lista de personas autorizadas (QA-11, CU-06) y el panel debe seguir sirviendo si fallan la base o el broker (QA-4). Todo eso pide un almacén de lectura separado, pero el equipo domina Java, PostgreSQL, C++ y Python (CRN-2) y el plazo es acotado (R-6): una tecnología fuera de esa lista solo se justifica si ninguna conocida cubre QA-2 y QA-6, y 800 ms no exige memoria. El ADR-0001 usa el read model como argumento frente a capas / N-tier, y los ADR-0002 y ADR-0003 asumen que el Panel API consume el stream de eventos para mantenerlo.

**Pregunta:** ¿Desde dónde lee el panel y cómo se mantiene esa copia al día con los eventos de la ingesta y con los cambios manuales (CU-04)?

---

## 2. Decisión

**Se elige una instancia PostgreSQL propia, separada de la base operacional (con almacenamiento y réplicas de lectura propios), como read model del panel, con un documento por viaje (columna JSON) y un índice por usuario. El Panel API la mantiene a partir de los eventos de la ingesta y de sus propios cambios manuales, y el panel lee de ella (CQRS solo en el panel). El dominio accede por un puerto (ADR-0001).**

- La base operacional es la fuente de verdad: toda entrada del read model se reconstruye desde ella, y el panel la reconstruye en la próxima lectura si falta.
- El Panel API actualiza el read model con cada evento, releyendo el estado actual de la base, y con sus cambios manuales. Reemplaza un documento solo si su versión nueva es mayor o igual a la guardada, en una sola operación atómica, de modo que duplicados y eventos fuera de orden no tienen efecto.
- En cada acceso a un itinerario compartido, el Panel API verifica que la persona esté en la lista del documento. Ante un cambio de permisos borra las entradas afectadas en lugar de actualizarlas.

**Incorpora:** del almacén clave-valor, el acceso por clave a un documento por viaje, sin joins en la lectura. **Difiere:** una caché en memoria delante del read model, solo si la prueba de carga no cumple QA-2, QA-3 o QA-6. Queda como evolución prevista sin reescritura, porque el puerto aísla el almacén.

### Alternativas descartadas

| Alternativa | Motivo |
|---|---|
| Sin read model (el panel lee de la base operacional) | Incumple QA-6 y QA-2: las lecturas hacen joins sobre el modelo de escritura y compiten con la ingesta en un único primario. No sostiene los picos ×10. |
| Tablas de lectura en la misma base operacional | Incumple QA-6 y QA-4: comparte instancia y primario con la ingesta, y si la base cae el panel cae con ella. Es la que menos suma, pero no aísla las lecturas. |
| Almacén clave-valor en memoria (Redis) | Incumple CRN-2 y R-6: es una tecnología fuera de lo que domina el equipo y exige dimensionar memoria y definir la expulsión de entradas. Una entrada expulsada obliga a reconstruirla desde la base, lo que reabre la dependencia de ella para servir (QA-4). Queda como evolución: caché delante del read model. |
| Base documental propia (MongoDB) | Cubre QA-4 igual que la instancia propia de PostgreSQL, pero incumple CRN-2 y R-6: es una tecnología nueva y un motor persistente más que operar. |

---

## 3. Consecuencias

**Positivas**

- Las lecturas del panel quedan aisladas de las escrituras de la ingesta, con instancia, réplicas y latencia propias (QA-2, QA-3, QA-6).
- No suma tecnologías nuevas para el equipo (CRN-2, R-6).
- Si el broker o la base operacional fallan, el panel sigue sirviendo la última versión conocida: el read model es durable y no depende de memoria ni de expulsión (QA-4).
- El stream de eventos tiene un uso concreto, y la reconstrucción desde la base cubre lo que quede fuera de la retención del log (QA-1, ADR-0003).
- El acceso por puerto permite cambiar de almacén o sumar una caché sin tocar el núcleo (R-6, ADR-0001).

**Negativas y riesgos aceptados**

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| RM-1 | Read model con retraso y una lectura extra de la base por cada evento. | El cambio aparece después del evento y de esa lectura, y consume parte de los 5 minutos de QA-1. Suma presión de lectura sobre la base compartida. | Medir el retraso de consumo con alertas, agrupar eventos por viaje, leer por clave indexada y repartir el presupuesto por etapa (ADR-0003, BR-1). Réplica de lectura de la base operacional si hace falta. |
| RM-2 | Un read model en disco puede no sostener los picos ×10. | Latencia por encima de QA-2 y QA-3, y lecturas que no escalan (QA-6). | Documento por viaje con acceso por clave, réplicas de lectura propias y prueba de carga con picos ×10. Si no alcanza, caché en memoria delante. |
| RM-3 | Dos escritores del read model (eventos y cambios manuales). | Una versión vieja puede pisar a una nueva y mostrar un dato desactualizado (QA-1). | Escritura condicional por versión, en una operación atómica. |
| RM-4 | Permisos desactualizados en el read model. | Una persona a la que se quitó el acceso a un itinerario compartido podría verlo si falla la actualización (QA-11). | Borrar la entrada ante un cambio de permisos, con reintentos. Verificar la lista de personas en cada acceso y registrar los rechazos en la auditoría. |
