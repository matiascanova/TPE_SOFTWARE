# ADR-0007: Contratos de mensajes y semántica de entrega

- **Estado:** Propuesto
- **Drivers de entrada:** Analisis-Inicial-y-Drivers.md, CU-01, CU-02, CU-03, QA-1, QA-5, QA-8, QA-12, R-2, R-3, R-4, R-6, CRN-N4.
- **ADR relacionados:** ADR-0002, ADR-0003, ADR-0004, ADR-0005, ADR-0006, ADR-0008, ADR-0009.

---

## 1. Contexto

El sistema utiliza un stream de trabajo y un stream de eventos bajo entrega al menos una vez (ADR-0002, ADR-0003). Se requiere definir la estructura de las cargas útiles para permitir la trazabilidad completa dentro del presupuesto de 5 minutos (QA-1), sostener la evolución sin romper consumidores (QA-12) y garantizar que la reentrega de mensajes o duplicados no genere efectos inconsistentes en el dominio (CRN-N4, QA-5).

**Pregunta:** ¿Qué estructura deben tener los mensajes y cómo logran idempotencia los consumidores frente a la reentrega de mensajes duplicados o desordenados?

**fuera de alcance:** Configuración de retención de mensajes y parámetros del broker (ADR-0009), Estrategia de reintentos, circuitos deshabilitados y apartados (ADR-0008) y Mecanismo de captura outbox/relay (ADR-0002).

---

## 2. Decisión

**Se elige A2/B1: Utilizar un sobre común de seis campos (`idMensaje`, `versionContrato`, `claveDeOrden`, `horaDelHecho`, `correlacion`, `origen`) con notificación cruda en el stream de trabajo y avisos mínimos con referencias en el de eventos, asegurando idempotencia mediante restricciones únicas en BD y comparación de versiones del viaje.**

- Confirmación tardía: Todo consumidor confirma el mensaje en el broker únicamente tras la ejecución exitosa de su transacción o escritura.
- Los workers validan si la traducción modifica el estado antes de incrementar la versión del viaje o emitir eventos.
- La Panel API procesa eventos únicamente si la versión informada es mayor o igual a la existente en el read model.

**Incorpora:** Tácticas *Timestamp*, *Adhere to Standards*, *Use an Intermediary* y *Transactions*. **Difiere:** Inclusión de estado completo dentro del payload del evento.

### Alternativas descartadas

| Alternativa | Motivo |
|---|---|
| Sin contrato común | Impide medir tiempos por etapa (QA-1), no permite trazar hechos ni detectar contratos inválidos (QA-8). |
| Evento con estado completo | Incrementa la retención de datos personales en el log (R-4) y viola el diseño de releer el estado actual (ADR-0004). |
| Esquemas binarios con Schema Registry | Introduce infraestructura y tecnología adicional innecesaria en el prototipo, afectando R-6. |
| Notificación interpretada en el receptor | Impide el reprocesamiento desde el log ante fallas en los adaptadores (QA-5) y ralentiza la recepción (QA-1). |
| Tabla de mensajes procesados | Agrega escrituras a la BD compartida (QA-1) y no detecta reintentos del proveedor con distinto `idMensaje`. |
| Entrega como máximo una vez | Riesgo de pérdida de mensajes ante caídas del consumidor, violando QA-1 y QA-5. |
| Exactamente una vez (End-to-End) | Requiere transacciones distribuidas no soportadas por la infraestructura y descartadas en ADR-0002. |

---

## 3. Consecuencias

**Positivas**

- Permite medir la latencia por etapa y trazar hechos de punta a punta (QA-1).
- Evita la duplicación de efectos ante reentregas o mensajes desordenados (CRN-N4, QA-5).
- Desacopla los eventos del modelo de dominio completo, minimizando la retención de datos personales (R-4).

**Negativas y riesgos aceptados**

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| MS-1 | El evento mínimo exige lectura a la BD | Aumenta la carga de lectura sobre la base (QA-6) | Agrupar lecturas por viaje y evaluar inclusión condicional de estado a futuro. |
| MS-2 | Notificaciones crudas en log contienen datos personales | Exposición durante el periodo de retención (R-4) | Definir políticas de retención acotadas en el broker (ADR-0009). |
| MS-3 | Reordenamiento de notificaciones de un mismo proveedor | Procesamiento de un estado anterior (CRN-N4) | Forzar lectura de estado actual via *Consultar* en proveedores sin garantía de orden. |
