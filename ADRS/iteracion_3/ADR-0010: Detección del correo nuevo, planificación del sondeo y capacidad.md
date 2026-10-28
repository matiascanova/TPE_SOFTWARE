# ADR-0010: Aviso del proveedor con sondeo de respaldo planificado por cercanía del viaje

- **Estado:** Propuesto
- **Drivers de entrada:** Analisis-Inicial-y-Drivers.md e Iteracion-2.md. CU-03. Otros: QA-7, QA-6, R-2, R-3, R-6, CRN-9.

---

## 1. Contexto

Trotamundos lee 15 M de casillas (QA-6) y debe procesar el correo de los usuarios con viajes próximos en 15 minutos o menos para el 95 % (QA-7). Sondear todas las cuentas con esa frecuencia excede los límites de uso de los proveedores de correo (R-3) y la capacidad de los workers. Los avisos que ofrecen los proveedores pueden perderse o dejar de llegar, y Trotamundos no puede cambiar ese mecanismo (R-3). Cada lectura debe retomar donde quedó la anterior sin perder ni duplicar correos (CRN-9). El Planificador de sondeo y el trabajo de correo con clave `correo:<id de la cuenta>` ya existen. Detección y planificación se deciden juntas porque el sondeo de respaldo lo emite el planificador, y el aviso y el sondeo leen desde la misma marca.

**Pregunta:** ¿Cómo se entera Trotamundos de que llegó correo nuevo a una casilla y con qué frecuencia se sondea cada cuenta?

---

## 2. Decisión

**Se elige A1: el aviso push del proveedor de correo es la vía principal, un sondeo de respaldo siempre activo y planificado por clases de cercanía del viaje recupera lo que el aviso no trajo, y ambos leen desde una marca de sincronización incremental por cuenta.**

- **(a) Aviso y respaldo:** el aviso y el sondeo encolan el mismo trabajo de la cuenta, y el worker lee desde la marca.
- **(b) Marca:** avanza solo después de procesar lo leído, así un trabajo repetido o interrumpido vuelve a leer desde el mismo punto.
- **(c) Planificación:** cada cuenta pertenece a una clase según la cercanía de su próximo viaje, tiene como máximo un trabajo pendiente y la emisión se frena cuando crece el atraso del stream de trabajo.

### Alternativas descartadas

| Alternativa | Motivo |
|---|---|
| A2. Solo sondeo | Cumplir QA-7 sondeando excede los límites de uso de los proveedores (R-3) y la capacidad de los workers (QA-6). |
| A3. Solo aviso push | Un aviso perdido deja la cuenta sin leer hasta el próximo aviso (QA-7, R-2). |
| A4. Releer una ventana reciente en lugar de una marca | Relee lo ya procesado en cada pasada y pierde lo que queda fuera de la ventana tras una interrupción larga (CRN-9). |
| A5. Sondeo uniforme, escalando workers si no alcanza | Gasta cuota y capacidad en cuentas sin viajes próximos, que QA-7 no mide (R-3, QA-6). |
| A6. Streams separados por prioridad | Suma streams y consumidores sin evitar la acumulación, que se controla frenando la emisión (R-6). |

---

## 3. Consecuencias

**Positivas**

- El correo de una cuenta con viaje próximo se detecta por el aviso o, si este falla, por el sondeo de su clase (QA-7).
- Un trabajo repetido no pierde ni duplica correos, gracias a la marca y a la idempotencia del worker (CRN-9).
- La carga sobre los proveedores de correo y sobre los workers queda acotada (R-3, QA-6).
- No suma desplegables: usa el Receptor y el Planificador de sondeo existentes (R-6).

**Negativas y riesgos aceptados**

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| SO-1 | La clase de una cuenta queda desactualizada, por ejemplo con un viaje recién cargado | La cuenta se sondea con menos frecuencia de la necesaria (QA-7) | Recalcular la clase cuando cambia un viaje de la cuenta |
| SO-2 | El freno por atraso demora a todas las clases | QA-7 se incumple en picos | Emitir primero las clases más cercanas y alertar por atraso |
| SO-3 | La marca de una cuenta queda incorrecta | Se saltean correos o se relee de más (CRN-9) | Avanzar la marca en la misma transacción que el resultado del procesamiento y relectura idempotente |
| SO-4 | Los avisos de un proveedor dejan de llegar sin error visible | La detección cae al ritmo del respaldo (QA-7) | Alerta por cuentas que dejan de recibir avisos |
| SO-5 | Los correos anteriores a la conexión no se leen | Reservas existentes que no aparecen en el panel (CU-03) | Decisión pendiente: importar correos anteriores a la conexión |
