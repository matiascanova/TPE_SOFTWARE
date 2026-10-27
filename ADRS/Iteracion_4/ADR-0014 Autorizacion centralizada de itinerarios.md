# ADR-0014: Punto único de autorización contra la base relacional, con denegación por defecto y registro antes de responder

- **Estado:** Propuesto
- **Drivers de entrada:** Analisis-Inicial-y-Drivers.md. CU-01, CU-06. Otros: QA-11, QA-1, QA-2, QA-4, QA-6, R-4, CRN-7.

---

## 1. Contexto

Un viajero comparte su itinerario con personas designadas (CU-06), y el sistema debe rechazar y registrar el 100 % de los accesos de personas no designadas (QA-11), porque el itinerario contiene datos personales (R-4). El ADR-0004 verifica la lista en el documento del read model, que puede quedar desactualizado si falla el borrado ante un cambio de permisos (RM-4, CRN-7), así que no garantiza el 100 %. Verificar contra la base relacional es exacto, pero suma una lectura por pedido (QA-2, QA-6) y ata el panel a la base, cuando el read model existe para seguir sirviendo si la base falla (QA-4). Para el dueño, la disponibilidad pesa más que la consistencia: es preferible que vea una versión atrasada de su viaje a que se quede sin panel. Para un tercero pesa más la consistencia, porque un acceso indebido incumple QA-11.

**Pregunta:** ¿Dónde y contra qué fuente se decide si un pedido puede ver un itinerario, y cómo se asegura que todo rechazo quede registrado?

---

## 2. Decisión

**Se elige A1: todo pedido a un itinerario pasa por un único punto de autorización del Panel API antes de leer el read model; el dueño se verifica con el documento del viaje y cualquier otro acceso, contra la lista de personas designadas de la base relacional, con denegación por defecto y registro del rechazo antes de responder.**

- Ninguna ruta de itinerario existe fuera del punto de autorización, y una prueba en CI recorre todas las rutas.
- Si la consulta de la lista o el registro del rechazo fallan, el pedido de un tercero se rechaza.
- La lista de personas designadas nunca se lee del read model; esto reemplaza la verificación sobre el documento del ADR-0004.

### Alternativas descartadas

| Alternativa | Motivo |
|---|---|
| A2. Lista de personas en el documento del read model (ADR-0004) | Si falla el borrado ante un cambio de permisos, deja pasar un acceso revocado (CRN-7, QA-11). |
| A3. Verificación dentro de cada endpoint | Un endpoint que omite la verificación deja pasar accesos sin registrarlos (QA-11). |
| A4. Enlace secreto para compartir | Quien obtenga el enlace accede sin ser una persona designada identificable (QA-11, R-4). |
| A5. Todo acceso, también el del dueño, contra la base relacional | Con la base caída el dueño se queda sin panel (QA-4). |

---

## 3. Consecuencias

**Positivas**

- QA-11 se cumple por construcción: no hay ruta sin verificar, se deniega por defecto y no hay respuesta de rechazo sin registro.
- Los permisos desactualizados del read model dejan de afectar el acceso de terceros (CRN-7).
- El dueño sigue viendo sus viajes con la base relacional caída (QA-4). Lo que cambia mientras tanto no se pierde: el trabajo espera en el stream de trabajo, los eventos en el de eventos, y el read model se pone al día al volver la base (ADR-0007, ADR-0008).

**Negativas y riesgos aceptados (máximo 5)**

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| AZ-1 | Cada acceso de un tercero suma una lectura a la base compartida | Latencia y carga (QA-2, QA-6) | Lectura por clave indexada y medición en la prueba de carga |
| AZ-2 | Con la base relacional caída, nadie ve itinerarios compartidos | Disponibilidad de CU-06 (QA-4) | Se acepta: en el acceso de terceros la consistencia pesa más que la disponibilidad (QA-11) |
| AZ-3 | El dueño se toma del documento, que podría estar desactualizado | Acceso indebido si el dueño de un viaje cambiara (QA-11) | La propiedad de un viaje no cambia en ningún caso de uso |
| AZ-4 | Intentos masivos de acceso inflan el registro de rechazos | Carga y datos personales retenidos (QA-2, R-4) | Limitador de pedidos por usuario y retención acotada |
| AZ-5 | Con la base caída, el dueño ve una versión atrasada de su viaje | Cambios que no aparecen en el panel hasta que vuelve la base (QA-1) | Se acepta: esperan en los streams y se aplican al volver; si la caída supera la retención, el documento se reconstruye desde la base (ADR-0004) |
