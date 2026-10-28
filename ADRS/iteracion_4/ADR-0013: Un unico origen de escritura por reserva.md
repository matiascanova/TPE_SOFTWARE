# ADR-0013: Un único origen de escritura por reserva, con desvinculación para editar una reserva de proveedor

- **Estado:** Propuesto
- **Drivers de entrada:** Analisis-Inicial-y-Drivers.md. CU-02, CU-03, CU-04. Otros: QA-1, QA-9, R-6, CRN-9.

---

## 1. Contexto

El viajero puede dar de alta, modificar y dar de baja reservas (CU-04), y las mismas reservas pueden llegar de un proveedor (CU-02) o del correo (CU-03). Si el usuario corrige una reserva de proveedor y después llega una actualización, alguna de las dos escrituras se pierde: o se pisa la corrección, o se ignora una cancelación que debe verse en menos de 5 minutos (QA-1). El usuario necesita corregir, porque hasta el 5 % de los correos se extrae mal (QA-9). Fusionar ambas fuentes campo por campo evita perder datos, pero exige lógica de conflictos en un plazo acotado (R-6), y dos escritores sobre la misma reserva complican la idempotencia de los workers (CRN-9). El ADR-0004 ya resuelve cómo llegan al read model los cambios manuales; falta decidir quién puede escribir cada reserva en la base.

**Pregunta:** ¿Qué pasa cuando el usuario y un proveedor modifican la misma reserva?
---

## 2. Decisión

**Se elige A1: cada reserva tiene un único origen que escribe sus datos, el usuario en las manuales y el adaptador en las de proveedor; para modificar o dar de baja una reserva de proveedor, el usuario la desvincula y pasa a ser manual, sin recibir más actualizaciones de ese proveedor.**

- El Panel API rechaza la modificación o la baja de una reserva de proveedor no desvinculada.
- Desvincular cambia el origen y sube la versión del viaje en una sola transacción.
- El worker descarta las notificaciones de una reserva desvinculada sin volver a crearla.

### Alternativas descartadas

| Alternativa | Motivo |
|---|---|
| A2. Gana la última escritura, por versión | Una actualización del proveedor pisa en silencio la corrección del usuario, o al revés, una edición oculta una cancelación (QA-1). |
| A3. Correcciones del usuario por campo sobre la reserva del proveedor, con aviso de conflicto | Exige guardar y fusionar dos versiones por campo y resolver conflictos en el panel (R-6). Queda como evolución, y la desvinculación sigue valiendo para quien prefiere cortar. |
| A4. El proveedor siempre gana y las reservas de proveedor no se editan | El usuario no puede corregir una reserva mal extraída del correo (QA-9, CU-04). |

---

## 3. Consecuencias

**Positivas**

- No hay dos escritores sobre la misma reserva, así que no hay conflicto que resolver (CRN-9, R-6).
- Las reservas vinculadas siguen recibiendo actualizaciones del proveedor dentro de los 5 minutos (QA-1).
- El usuario puede corregir cualquier reserva, incluidas las mal extraídas del correo (QA-9, CU-04).

**Negativas y riesgos aceptados (máximo 5)**

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| MR-1 | Una reserva desvinculada no recibe cancelaciones ni cambios del proveedor | Panel desactualizado para esa reserva (QA-1, CU-02) | Aviso al usuario antes de desvincular |
| MR-2 | Corregir un solo campo obliga a desvincular la reserva entera | Se pierden actualizaciones que el usuario sí quería (CU-04) | Evolución a correcciones por campo (A3) |
| MR-3 | El usuario desvincula mientras un worker guarda una actualización | Una escritura llega con versión vieja (CRN-9) | Escritura condicional por versión y reintento (ADR-0002) |
| MR-4 | El usuario carga a mano una reserva que después llega del proveedor | Reserva duplicada en el panel (CU-01) | Se acepta en el prototipo |
