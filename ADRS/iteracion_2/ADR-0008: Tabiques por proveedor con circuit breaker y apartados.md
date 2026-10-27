# ADR-0008: Tabiques por proveedor con circuit breaker y stream de apartados

- **Estado:** Aceptado
- **Drivers de entrada:** Analisis-Inicial-y-Drivers.md e Iteracion-1.md. CU-02, CU-03. Otros: CRN-N1, QA-1, QA-5, QA-8, R-3, R-4, R-6.

---

## 1. Contexto

Los ADR-0002 y 0003 prevén colas, pools y circuit breaker por proveedor y un stream para lo que no se puede procesar, pero no definen cómo se aísla una falla ni qué se reintenta. Un desplegable por proveedor ya se descartó por costo (ADR-0001, ADR-0002), así que el aislamiento se logra dentro del worker (CRN-N1, R-6). Reintentar recupera fallas transitorias, pero consume los 5 minutos (QA-1) y el orden por clave impide saltear un mensaje. Contar una caída del proveedor como inválido falsea la medida de calidad (QA-8), los apartados no deben retener más datos personales de los necesarios (R-4) y los límites de uso del proveedor se respetan (R-3). El circuito y los apartados se deciden juntos porque la clasificación de cada falla decide qué se pausa y qué se aparta.

**Pregunta:** ¿Cómo se evita que la falla de un proveedor afecte a los demás, y adónde va un mensaje que no se puede procesar?

**Fuera de alcance:** reconciliación y retención (ADR-0009), tecnología del broker (ADR-0010), campos de los mensajes (ADR-0007), criterio de validez (ADR-0006), alta disponibilidad y monitoreo (a futuro) y pipeline de correo. Los valores con \* son propuestas para el prototipo y se ajustan con proveedores simulados.

---

## 2. Decisión

**Se elige A1: el stream de trabajo se divide por proveedor, cada uno con recursos propios (pool de conexiones, límite de concurrencia y limitador de tasa) y un circuit breaker que pausa solo su consumo, y todo mensaje inválido o con reintentos agotados va completo a un stream de apartados, con su registro en la BD sin el cuerpo.**

- **Reintento según la falla:** una falla del proveedor se reintenta hasta 3 veces con espera creciente\*, un 429 respeta la espera que pide el proveedor, un inválido nunca se reintenta, un error propio causado por el mensaje se reintenta hasta 5 entregas\* y se aparta como «agotado», y una falla de la BD o del broker pausa el consumo sin apartar nada.
- **Circuito:** se abre si falla la mitad de las últimas 20 llamadas\*, sus mensajes esperan en el log, y prueba con una llamada real cada vez más espaciada hasta un máximo de 60 s\*.
- **Registro:** guarda `idMensaje` (único), `origen`, `claveDeOrden`, `correlacion`, motivo, intentos, hora y estado, con motivos cerrados (los de contenido del ADR-0006, «contrato» del ADR-0007 y «agotado») y nunca una caída del proveedor; el original se confirma solo después de apartarlo y registrarlo, y el reproceso republica con el mismo `idMensaje`.

### Alternativas descartadas

| Alternativa | Motivo |
|---|---|
| A2. Pool compartido con reintento en línea, sin apartar | Un proveedor lento retiene los recursos de todos (CRN-N1) y un mensaje que siempre falla bloquea su partición y nunca queda registrado (QA-8, QA-1). |
| A3. Circuit breaker sobre un stream común, con los mensajes del proveedor caído republicados en un stream de reintentos | Rompe el orden por clave (ADR-0002) y multiplica los mensajes durante una disrupción (QA-1). |
| A4. Mismos tabiques y circuito, con los apartados completos en una tabla de la BD | Guarda datos personales sin plazo de borrado (R-4) y suma escrituras grandes a la base compartida (QA-1). |

---

## 3. Consecuencias

**Positivas**

- Un proveedor caído o defectuoso queda acotado a sus particiones y recursos, y el resto sigue (CRN-N1, QA-8).
- QA-8 se mide sobre un registro con motivo codificado, sin contar caídas de proveedores.
- La vuelta de un proveedor se detecta en 60 s como máximo, lo que deja al menos 4 de los 5 minutos de QA-5 para ponerse al día.
- No suma desplegables y existen bibliotecas para implementarlo, como Resilience4j en Java (R-6).
- Los apartados no guardan datos de reserva en la BD (R-4).

**Negativas y riesgos aceptados**

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| RS-1 | La resiliencia depende de código y parámetros propios, incluida la clasificación de cada falla | Una falla mal clasificada distorsiona QA-8 o deja el circuito sin abrir (CRN-N1, R-6) | Biblioteca estándar, pruebas de contrato con proveedores simulados que fallan y alerta por salto de apartados |
| RS-2 | Un mensaje con errores repetidos bloquea su partición mientras se reintenta | Las otras claves de la partición se demoran hasta unos 50 s (QA-1) | Reintentos acotados y más particiones si la demora se repite |
| RS-3 | Reprocesar un apartado puede devolver una reserva a un estado viejo | Dato desactualizado en el panel (CU-02) | Si la reserva ya existe, el worker usa *Consultar* (ADR-0006) antes de guardar |
