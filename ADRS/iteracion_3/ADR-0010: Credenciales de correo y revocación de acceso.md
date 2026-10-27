# ADR-0010: Revocación del acceso al correo como cambio de estado verificado antes de cada lectura

- **Estado:** Propuesto
- **Drivers de entrada:** Analisis-Inicial-y-Drivers.md e Iteracion-2.md. CU-03. Otros: QA-10, QA-8, R-4, R-6, CRN-5.

---

## 1. Contexto

El usuario puede revocar el acceso de Trotamundos a su casilla desde el panel o desde el proveedor de correo (QA-10). Cuando lo hace pueden quedar trabajos de sondeo encolados, un aviso en vuelo o un worker por leer, y ninguno debe volver a leer la casilla (R-4). La revocación hecha desde el proveedor solo se descubre al intentar leer. Contarla como mensaje inválido falsea la medida de QA-8, y contarla como falla del proveedor abre el circuit breaker de todas las cuentas de ese proveedor (ADR-0008, CRN-5). El plazo es acotado (R-6). El corte y la clasificación se deciden juntos porque la clasificación es la forma en que se detecta la revocación hecha desde el proveedor.

**Pregunta:** ¿Cómo se corta la lectura de una casilla cuando el usuario revoca el acceso, desde el panel o desde el proveedor de correo?

**Fuera de alcance:** almacenamiento de los tokens, filtrado y extracción del correo (ADR-0011), detección del correo nuevo y planificación (ADR-0012) e importación de correos anteriores a la conexión.

---

## 2. Decisión

**Se elige A1: revocar es un cambio de estado atómico del acceso de la cuenta, que cada worker verifica antes de cada lectura de la casilla, y la revocación hecha desde el proveedor se detecta como un resultado «acceso revocado», distinto del inválido y de la falla del proveedor.**

- **(a) Corte:** el cambio de estado se hace en una transacción. El worker que consume un trabajo de una cuenta revocada lo descarta y lo confirma, sin leer la casilla ni apartarlo.
- **(b) Clasificación:** el adaptador de correo devuelve «acceso revocado» cuando el proveedor rechaza el acceso por revocación. El worker aplica el mismo cambio de estado de (a), y el resultado no cuenta para QA-8 ni para el circuit breaker.
- Amplía los resultados del adaptador (ADR-0006) y la clasificación de fallas (ADR-0008) con un tercer caso.

### Alternativas descartadas

| Alternativa | Motivo |
|---|---|
| A2. Purgar del log el trabajo pendiente de la cuenta | No alcanza al trabajo ya entregado a un worker ni a un aviso en vuelo (QA-10). |
| A3. Esperar el vencimiento del token | La casilla se sigue leyendo después de la revocación (QA-10, R-4). |
| A4. Estado de revocación en una caché con invalidación | Suma una pieza y una ventana de invalidación en la que todavía se lee (R-6, QA-10). |
| A5. Contar la revocación como inválido | Falsea la medida de QA-8, que es sobre mensajes y no sobre permisos. |
| A6. Contar la revocación como falla del proveedor | Abre el circuit breaker y pausa todas las cuentas del proveedor (CRN-5). |

---

## 3. Consecuencias

**Positivas**

- Después de confirmada la revocación no se lee la casilla, aunque haya trabajos ya encolados (QA-10, R-4).
- El corte no suma piezas: el estado vive en la base y el descarte usa el consumo normal (R-6).
- La medida de QA-8 y el circuit breaker del proveedor de correo no se alteran por revocaciones (CRN-5).

**Negativas y riesgos aceptados**

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| RV-1 | Una lectura que ya empezó cuando se revoca termina igual | Se procesa un correo después de la revocación (QA-10, R-4) | Verificar el estado también antes de guardar el resultado y descartarlo si cambió |
| RV-2 | Una consulta del estado antes de cada lectura, con millones de cuentas | Presión de lectura sobre la base compartida (QA-6, CRN-5) | Lectura por clave de la cuenta y medición en la prueba de carga |
| RV-3 | El adaptador confunde un acceso vencido con uno revocado, o al revés | Se corta una cuenta válida o se reintenta sobre una revocada (QA-10, QA-8) | Pruebas de contrato con respuestas de vencimiento y de revocación de cada proveedor |
| RV-4 | Los trabajos de una cuenta revocada quedan en el log hasta su retención | Referencias a la cuenta retenidas (R-4) | El trabajo de correo no lleva contenido ni tokens (ADR-0007) y la retención es acotada |
