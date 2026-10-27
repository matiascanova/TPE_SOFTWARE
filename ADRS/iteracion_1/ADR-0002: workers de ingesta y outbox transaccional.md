# ADR-0002: Workers asíncronos de ingesta con outbox transaccional

- **Estado:** Aceptado
- **Drivers de entrada:** [Análisis inicial y drivers](Analisis-Inicial-y-Drivers.md) (CU-02, CU-03; QA-1, QA-5, QA-6, QA-7, QA-8, QA-12; R-2, R-6)

---

## 1. Contexto

Una notificación casi nunca trae la reserva completa: hay que consultar al proveedor o leer el correo, traducir al modelo canónico, guardar y avisar al Panel API (ADR-0004). Hacerlo dentro de la respuesta al proveedor incumple QA-1 (el cambio llega al panel en menos de 5 minutos, sin perderse) y obliga a escalar junto con las lecturas del panel, contra QA-6 y QA-7 (15 M de cuentas). Cada proveedor tiene su API (R-2), uno defectuoso no debe afectar a los demás (QA-8) y sumar trenes no debe tocar el núcleo (QA-12). Además, guardar en la base y publicar en el broker no comparten transacción: si una operación falla sin la otra, el panel no se entera de una reserva existente o muestra una que no existe (QA-1, QA-5). Todo debe poder implementarse en el plazo acotado (R-6). El ADR-0001 incorpora workers y outbox en C-lite y remite a este documento.

**Pregunta:** ¿Cómo se procesa cada notificación y cómo se garantiza que el panel se entere del cambio sin pérdidas ni invenciones?

---

## 2. Decisión

**Se eligen workers asíncronos que toman el trabajo del stream y viven en el artefacto de ingesta, guardan cada cambio y su evento en una sola transacción de la base ([outbox transaccional](https://microservices.io/patterns/data/transactional-outbox.html)), mientras un relay por polling publica los eventos pendientes en el stream de eventos (ADR-0003).**

- Cada proveedor y cada medio de transporte entra como un adaptador por puerto, con colas, pool de conexiones y circuit breaker propios.
- El guardado es idempotente por identificador de notificación o versión del proveedor, y el relay entrega al menos una vez.
- Al restablecerse la API de un proveedor, el adaptador reconcilia consultando lo pendiente o el estado actual.

**Incorpora:** aislamiento por proveedor dentro del worker, sin separar desplegables (ADR-0001). **Difiere:** captura de cambios y un servicio por proveedor, como evolución prevista.

### Alternativas descartadas

| Alternativa | Motivo |
|---|---|
| Procesar en el receptor, sin workers | Incumple QA-1, QA-6 y QA-8: el proveedor espera, ante una ráfaga el receptor se satura y un proveedor lento bloquea a los demás. |
| Workers con doble escritura, sin outbox | Incumple QA-1 y QA-5: si guarda y falla la publicación, el panel no se entera. Si publica y falla el guardado, muestra una reserva inexistente y el evento no se puede retirar. |
| Workers con outbox y captura de cambios (CDC) | Incumple R-6 hoy: suma un componente y depende del motor de base, aún no decidido. Queda como evolución: el relay publica por un puerto, así que pasar a CDC no toca a los workers. |
| Workers con transacción distribuida (2PC / XA) | Incumple QA-5: los brokers candidatos no participan en transacciones distribuidas y se acoplaría la disponibilidad de la base con la del broker. |

---

## 3. Consecuencias

**Positivas**

- La ingesta absorbe ráfagas y límites de los proveedores sin bloquear el receptor (QA-1, QA-6, QA-7).
- Ningún cambio se guarda sin su aviso ni se avisa sin guardarse: el panel recibe cada evento al menos una vez (QA-1, QA-5).
- Si el broker se cae, lo guardado no se pierde y el relay se pone al día al volver (QA-5, ADR-0003).
- La falla de un proveedor se degrada de forma local, y sumar un transporte o separar un proveedor en un servicio propio no toca el núcleo (QA-8, R-2, QA-12).
- Se implementa con una tabla y un proceso, sin depender de un motor de base ni de un broker concretos (R-6).

**Negativas y riesgos aceptados**

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| W-1 | Un adaptador defectuoso sigue afectando a los workers. | Puede interrumpir la integración con otros proveedores (QA-8). | Colas, pools y circuit breaker por proveedor. |
| W-2 | Workers y Panel API comparten la base. | Cuello de botella de escritura (QA-6) y acoplamiento por esquema. | Cada módulo es dueño de sus tablas, read model separado (ADR-0004) y prueba de carga. |
| W-3 | El relay agrega latencia y puede detenerse. | Suma hasta un intervalo de demora a los 5 minutos de QA-1. Si se detiene, el panel deja de actualizarse sin que falle otro componente. | Intervalo corto y presupuesto por etapa (ADR-0003, BR-1), alerta por antigüedad de la fila pendiente más vieja, reinicio automático y outbox particionado por tiempo para descartar filas viejas. |
