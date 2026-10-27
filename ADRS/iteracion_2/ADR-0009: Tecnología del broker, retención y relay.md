# ADR-0009: Tecnología del broker, retención y relay

- **Estado:** Propuesto
- **Drivers de entrada:** Analisis-Inicial-y-Drivers.md, CU-01, CU-02, CU-03, QA-1, QA-5, QA-6, R-4, R-6, CRN-2, CRN-N3.
- **ADR relacionados:** ADR-0002, ADR-0003, ADR-0004, ADR-0005, ADR-0007, ADR-0008.

---

## 1. Contexto

Se requiere definir la tecnología concreta que soportará el log persistente del broker (ADR-0003), establecer la política de retención de mensajes en cada stream sin agotar la memoria ni exponer excesivamente datos personales (QA-6, R-4), y fijar la frecuencia de sondeo del proceso relay sobre el outbox para minimizar la latencia de publicación hacia la Panel API dentro del presupuesto de 5 minutos (QA-1), evitando sumar múltiples tecnologías desconocidas al equipo (R-6, CRN-2).

**Pregunta:** ¿Qué tecnología debe implementar el broker de mensajería, cuál es su política de retención por stream y con qué estrategia e intervalo debe operar el proceso relay?

**Fuera de alcance:** Configuración de clusters y alta disponibilidad para entornos de producción (Iteración 3), definición de esquemas de datos del payload (ADR-0007) y la estructura de particionamiento lógico de temas (ADR-0008).

---

## 2. Decisión

**Se elige T1/R2/P2: Implementar el broker mediante Redis Streams en una instancia dedicada con persistencia síncrona en disco (`appendfsync always`), políticas de retención recortadas por tiempo según el tipo de stream (`XTRIM`), y un proceso relay de lectura en lotes de hasta 500 filas con espera de 1 s si la lectura no completó el lote.**

- La instancia de Redis del broker opera aislada del read model con política `noeviction` para evitar pérdida silenciosa de mensajes.
- La retención se configura diferenciada: 48 h para trabajo de proveedores, 24 h para eventos y 7 días para apartados.
- El relay utiliza `SELECT ... FOR UPDATE SKIP LOCKED` para permitir la ejecución concurrente de múltiples instancias.

**Incorpora:** Tácticas *Redundant Spare*, *Bound Queue Sizes*, *Heartbeat*, *Encapsulate* y *Manage Work Requests*. **Difiere:** Reemplazo por RabbitMQ Streams en disco si el volumen de memoria excede la capacidad.

### Alternativas descartadas

| Alternativa | Motivo |
|---|---|
| Redis Streams en la misma instancia del read model | La política de expulsión de memoria del read model puede borrar mensajes no confirmados (QA-5). |
| RabbitMQ Streams | Suma una segunda tecnología desconocida a la pila operativa del grupo, violando R-6 y CRN-2. |
| Colas destructivas (RabbitMQ Quorum) | Elimina la capacidad de relectura y replay requerida por el sistema (ADR-0004, ADR-0007). |
| Retención por cantidad de mensajes | En picos de tráfico reduce drásticamente la ventana temporal retenida, violando QA-5 y QA-6. |
| Retención uniforme de 7 días | Aumenta masivamente el uso de memoria (QA-6) y prolonga la exposición de datos personales (R-4). |
| Relay con intervalo fijo largo (5 s) | Consume una porción excesiva del presupuesto temporal de 5 minutos, afectando QA-1. |
| Notificaciones via PostgreSQL `LISTEN/NOTIFY` | Genera bloqueos globales en las escrituras sobre la tabla outbox, degradando la ingesta (QA-1). |
| Captura CDC (Debezium/Kafka Connect) | Agrega complejidad de despliegue e infraestructura no justificada para el prototipo (R-6). |

---

## 3. Consecuencias

**Positivas**

- Mantiene una única tecnología nueva (Redis) para el broker y el read model, reduciendo la curva de aprendizaje (R-6, CRN-2).
- Garantiza la durabilidad de los mensajes confirmados mediante la persistencia síncrona en disco (QA-5).
- Mantiene la latencia del relay por debajo de 1 segundo en escenarios normativos (QA-1).

**Negativas y riesgos aceptados**

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| BK-1 | Almacenamiento en memoria excede la capacidad del servidor | Recorte prematuro de mensajes no procesados (QA-5, QA-6) | Monitoreo con alertas al 70% de memoria y retenciones acotadas por stream. |
| BK-2 | Penalización de latencia por `appendfsync always` | Incremento leve en el tiempo de respuesta del receptor (QA-1) | Validar throughput en pruebas de carga; aislar almacenamiento SSD. |
| BK-3 | Falta de experiencia operativa previa en Redis | Riesgo de mala configuración en producción (CRN-2, R-6) | Encapsular el acceso a Redis tras la interfaz del broker (ADR-0003). |
