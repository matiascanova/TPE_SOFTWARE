# ADR-0007: Contrato común de mensajes con idempotencia en cada consumidor

- **Estado:** Aceptado
- **Drivers de entrada:** Analisis-Inicial-y-Drivers.md e Iteracion-1.md. CU-01, CU-02, CU-03. Otros: QA-1, QA-5, QA-6, QA-8, QA-12, R-2, R-4, R-6, CRN-N4.

---

## 1. Contexto

Los ADR-0002 y 0003 fijaron streams de trabajo y de eventos con entrega al menos una vez, pero no definieron qué lleva cada mensaje ni cómo se evita que una entrega repetida duplique efectos. Los duplicados son inevitables (caída tras procesar, republicación del relay, reintento del proveedor) y deben ser inofensivos (CRN-N4, QA-5). El log solo ordena por clave, y el mensaje debe permitir medir la demora por etapa (QA-1), aislar el mal formado (QA-8), admitir proveedores nuevos sin cambiar el contrato (QA-12, R-2) y retener pocos datos personales (R-4) sin sumar piezas (R-6). Más contenido en el evento ahorra lecturas, pero retiene datos y acopla a los consumidores (QA-6).

**Pregunta:** ¿Qué estructura tienen los mensajes de trabajo y de eventos, y cómo logra cada consumidor que la entrega al menos una vez no duplique efectos?

---

## 2. Decisión

**Se elige A1: ambos streams llevan un sobre versionado de seis campos (`idMensaje`, `versionContrato`, `claveDeOrden`, `horaDelHecho`, `correlacion`, `origen`); el trabajo lleva la notificación cruda y el evento solo referencias; y cada consumidor es idempotente por las claves y versiones del modelo y confirma el mensaje solo después de completar su efecto.**

- La `claveDeOrden` del trabajo es `<proveedor>:<id externo de la reserva>` (correo: `correo:<id de la cuenta>`), la del evento es `viaje:<id del viaje>`, y `origen` es `<proveedor>/<medio>` y elige el adaptador.
- El evento lleva `tipoEvento`, `idViaje`, `idReserva` y `versionViaje`; es JSON con esquema versionado en el repositorio, un `tipoEvento` desconocido se trata como «el viaje cambió», y un sobre incompleto o de versión desconocida se aparta con motivo «contrato» (ADR-0008).
- El worker compara la reserva traducida con la guardada (clave única por proveedor e identificador externo) y no sube la versión si no hay diferencia; el Panel API relee el viaje y escribe solo si la versión es mayor o igual a la guardada (ADR-0004).

### Alternativas descartadas

| Alternativa | Motivo |
|---|---|
| A2. Evento con el estado completo de la reserva | El panel relee la base (ADR-0004) y el log retendría datos de reserva (R-4). Queda como evolución con un campo opcional `estado`, sin romper el contrato. |
| A3. Notificación interpretada en el receptor | El receptor cargaría adaptadores antes de responder al proveedor (QA-1) y se perdería la notificación original para reprocesar (QA-5). |
| A4. Registro de esquemas con Avro o Protobuf | Suma una pieza que aprender y operar (R-6); `versionContrato` ya permite migrar a ella después. |
| A5. Deduplicación por identificador: tabla de `idMensaje` procesados, o id y versión de notificación del proveedor | El `idMensaje` no detecta el reintento del proveedor con otro id, no todos los proveedores informan id o versión (R-2) y la tabla suma una escritura por mensaje a la base compartida (QA-1, R-6). |

---

## 3. Consecuencias

**Positivas**

- Cada hecho se sigue de punta a punta y se mide contra los 5 minutos con `horaDelHecho` y `correlacion` (QA-1).
- Duplicados, reentregas y mensajes fuera de orden no tienen efecto (CRN-N4, QA-5).
- Un proveedor, medio o tipo nuevo no cambia el contrato (QA-12, R-2).
- El log no retiene datos de reserva en los eventos ni contenido de correo o tokens en el trabajo (R-4).

**Negativas y riesgos aceptados**

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| MS-1 | El evento mínimo obliga a cada consumidor a leer la base | Presión de lectura y acople a consumidores futuros (QA-6) | Campo opcional `estado` si hace falta; el Panel API agrupa lecturas por viaje (ADR-0004) |
| MS-2 | La notificación cruda puede contener datos personales y queda en el log | Exposición de datos (R-4) | Retención acotada (ADR-0009) y sin contenido de correo ni tokens |
| MS-3 | La idempotencia depende de la disciplina de cada consumidor | Efectos dobles (CRN-N4) | Pruebas de reentrega por consumidor; el que tenga efectos externos guarda el `idMensaje` |
| MS-4 | El trabajo se ordena por reserva del proveedor y no por viaje, y dos reservas del mismo viaje se procesan en paralelo | Compiten por la versión del viaje y se demora el guardado (QA-1) | La escritura compara la versión y reintenta (ADR-0002) |
| MS-5 | Un proveedor sin versión puede enviar una notificación vieja después de una nueva | Se toma como un cambio (CRN-N4) | Orden por clave y, si el proveedor no la garantiza, el worker usa *Consultar* (ADR-0006) |
