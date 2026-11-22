# ADR-0018: Derivación a la agencia como caso registrado con outbox y despachador por agencia

- **Estado:** Aprobado
- **Drivers de entrada:** [Análisis inicial y drivers](Analisis-Inicial-y-Drivers.md) (CU-10, CU-09; QA-4, CRN-9, R-4, R-6)

---

## 1. Contexto

Un caso complejo debe llegar a la agencia preferida del usuario (CU-09, CU-10) y no perderse aunque la agencia o el broker estén caídos (QA-4). Entregarlo dentro de la conversación ata la respuesta al usuario a un tercero. El ADR-0002 ya resuelve guardar y avisar sin pérdidas con un outbox, el ADR-0003 permite que un consumidor nuevo lea el log con su propio offset y el ADR-0007 aísla las fallas por proveedor. Una entrega repetida no debe abrir dos casos en la agencia (CRN-9), el caso lleva datos personales (R-4) y las agencias todavía no están definidas (R-6).

**Pregunta:** ¿Cómo llega el caso complejo a la agencia sin perderse ni duplicarse?

---

## 2. Decisión

**Se elige A1: derivar es guardar el caso y su evento `caso.derivado` en una sola transacción de la base (outbox del ADR-0002); el Relay lo publica en el stream de eventos y un despachador, con offset propio, lo entrega a la agencia preferida con el `idCaso` como clave de idempotencia y un circuit breaker por agencia.**

- El evento usa el sobre del ADR-0006 con `claveDeOrden` `viaje:<id>` e `idViaje`, sin contenido del caso; el Panel API lo trata como «el viaje cambió» y no tiene efecto.
- El caso guarda un resumen (reserva y motivo) con retención acotada, y el usuario lo confirma antes de derivar (ADR-0017).
- Una falla de la agencia se reintenta con la política del ADR-0007: el caso sigue «derivando» y nunca se aparta.

### Alternativas descartadas

| Alternativa | Motivo |
|---|---|
| A2. Entrega sincrónica a la agencia al confirmar | La respuesta al usuario depende de un tercero y, si la entrega falla después de guardar, el caso se pierde o se duplica al reintentar (QA-4, CRN-9). |
| A3. Mostrar al usuario el contacto de la agencia | No delega: el usuario debe gestionar el caso por su cuenta y no queda registro (CU-10, CU-09). Queda como modo mínimo si una agencia no tiene canal. |
| A4. Correo directo desde el asistente, sin registrar el caso | Un fallo de envío se pierde y el usuario no puede ver el estado del caso (QA-4, CU-09). |

---

## 3. Consecuencias

**Positivas**

- Ningún caso se pierde: o se guardó con su evento o no se le confirmó al usuario (QA-4, ADR-0002).
- Reutiliza Relay, stream de eventos y sobre, sin piezas nuevas de mensajería (R-6).
- Una reentrega no abre dos casos si la agencia respeta el `idCaso` (CRN-9).
- Una agencia caída solo demora su entrega; la conversación y el panel siguen (QA-4).

**Negativas y riesgos aceptados (máximo 5)**

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| DA-1 | La agencia no respeta la clave de idempotencia | Casos duplicados en la agencia (CRN-9) | Se acepta en el prototipo (agencias simuladas); consultar el estado antes de reintentar si el canal lo permite |
| DA-2 | El Asistente escribe en el outbox que usan los Workers | Acople por esquema y más filas en la base compartida (CRN-5) | Escritura por el puerto del outbox y prueba de carga |
| DA-3 | Los eventos de caso viajan por el stream de eventos de viaje | Relecturas sin efecto en el Panel API (ADR-0006) | Se acepta; si crece, un stream propio de casos detrás del puerto de mensajería |
| DA-4 | La agencia no responde o no tiene canal, y la resolución no vuelve al panel | Caso «derivando» sin cierre (CU-09) | Estado visible al usuario y alerta por antigüedad del caso pendiente; la resolución queda como deuda |
| DA-5 | El resumen del caso llega a un tercero | Exposición de datos personales (R-4) | Solo reserva y motivo, confirmación previa del usuario y retención acotada |
