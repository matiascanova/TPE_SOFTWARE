# ADR-0002: Workers de ingesta y outbox transaccional

- **Estado:** Propuesto
- **Fecha:** 19/10/2026
- **Decisores:** Equipo de diseño (grupo TPE)
- **Drivers de entrada:** Análisis inicial y drivers (CU-02, CU-03; QA-1, QA-5 a QA-7; R-2, R-6).

---

## 1. Contexto y problema

Trotamundos recibe notificaciones de proveedores de viaje y de correo (el receptor las encola, ADR-0003). Pero una notificación casi nunca trae la reserva completa: hay que consultar al proveedor o leer el correo, interpretar el contenido, traducirlo al modelo canónico y guardarlo. Después hay que avisar al Panel API para que actualice el read model.

El ADR-0001 menciona los workers y el outbox como parte de C-lite sin explicarlos, y la Iteración 1 los registra sin ADR propio. Este documento responde dos preguntas:

1. **¿Por qué existe un rol de workers**, separado del receptor y del Panel API?
2. **¿Cómo se guarda un cambio y se avisa al panel** sin perder ni inventar eventos?

**Tensiones de la pregunta 1 (workers):**

- **Procesar es lento y variable.** Implica llamadas a APIs externas, lectura de correo y extracción de la reserva (CU-02, CU-03). No cabe en la respuesta rápida que el proveedor espera.
- **Otra curva de escala.** El correo de 15 M de casillas (QA-7) escala con un patrón distinto al de las lecturas del panel (QA-6).
- **Un contrato por proveedor.** La integración se adapta a las APIs de cada uno (R-2).

**Tensión de la pregunta 2 (outbox): el problema de la doble escritura.** Cuando llega la confirmación de un hotel, el worker debe hacer dos cosas en sistemas distintos: guardar la reserva en la base y publicar el evento "reserva creada" en el broker. Esos dos sistemas no comparten transacción, así que una operación puede fallar sin la otra:

- Si se guarda y falla la publicación, la reserva existe pero el panel nunca se entera (QA-1, QA-5).
- Si se publica y falla el guardado, el panel muestra una reserva que no existe.

Como en los otros ADR, la viabilidad de implementación es un criterio de primer orden con plazo acotado (R-6).

## 2. Criterios de decisión

| Criterio | Se usa en |
|---|---|
| QA-1 (actualizaciones en 5 min) | Decisiones A y B |
| QA-5 (resiliencia e interoperabilidad) | Decisiones A y B |
| QA-6 / QA-7 (escalabilidad) | Decisiones A y B |

## 3. Decisión A: workers de ingesta

### 3.1 Alternativas consideradas

| ID | Alternativa | Resumen |
|---|---|---|
| A1 | Procesar en el receptor | El receptor consulta al proveedor, interpreta y guarda antes de responder. No hay workers. |
| A2 | **Workers asíncronos dentro del desplegable de ingesta** | El receptor solo encola. Los workers toman el trabajo del stream y lo procesan con un adaptador por proveedor. |
| A3 | Un servicio por proveedor | Cada adaptador es un desplegable propio, con su propio contrato y su propia infraestructura. |
| A4 | Funciones serverless por evento | Una función por notificación, disparada por el stream o por webhooks. |

### 3.2 Decisión

**Se elige A2: el procesamiento de las notificaciones lo hacen workers asíncronos, que toman el trabajo del stream y viven en el mismo desplegable que el receptor, con un adaptador por proveedor y por medio de transporte.**

**Por qué existen los workers.** El receptor tiene que responder rápido al proveedor, y procesar la notificación no se puede hacer en ese tiempo ni a ese ritmo. Los workers son el rol que absorbe esa diferencia: trabajan a un ritmo propio, escalan agregando instancias al mismo grupo de consumo y concentran los reintentos, el aislamiento por proveedor y los adaptadores.

**Qué hace un worker con cada trabajo:**

1. Toma un trabajo del stream de trabajo (ADR-0003).
2. Usa el adaptador del proveedor para consultar los detalles de la reserva o leer el correo (R-2).
3. Traduce al modelo canónico.
4. **En una sola transacción**, guarda el cambio en la base y escribe el evento en el outbox (decisión B).

**Reglas:**

- **Adaptadores por puertos.** Cada proveedor y cada medio de transporte entra como un adaptador que traduce al modelo canónico. Sumar trenes es un adaptador nuevo.
- **Aislamiento por proveedor.** Colas, pools de conexiones y circuit breaker por proveedor dentro del worker (mitigación del riesgo de radio de impacto del ADR-0001).
- **Idempotencia.** Procesar dos veces la misma notificación no duplica la reserva: el guardado compara la versión del viaje.
- **Orden por clave.** Un mismo viaje lo procesa un solo worker a la vez (la clave del stream de trabajo), de modo que sus eventos se escriben en orden.
- **Un solo código.** Los workers comparten el código del dominio con el resto del sistema, con las reglas de dependencia verificadas en CI (ADR-0001).

### 3.3 Por qué no las otras alternativas

- **A1 (procesar en el receptor).** Es la más simple, pero el proveedor espera mientras se consulta a su propio sistema y se interpreta el correo. Ante una ráfaga, el receptor se satura y el proveedor reintenta o descarta. Un proveedor lento bloquea la recepción de todos los demás y el receptor y el procesamiento escalan juntos aunque tengan necesidades distintas.
- **A3 (un servicio por proveedor).** Da el mejor aislamiento, pero exige varios desplegables con contratos propios. El ADR-0001 ya descartó esa granularidad para este plazo (alternativa C). Queda como evolución: como los adaptadores ya son módulos con puertos, separarlos después no es una reescritura.
- **A4 (serverless).** Se descartó por las razones del ADR-0001, 4.4: acoplamiento a un proveedor cloud, riesgo de costos y dificultad para ejecutar y demostrar el prototipo localmente.

## 4. Decisión B: outbox transaccional

### 4.1 Alternativas consideradas

| ID | Alternativa | Resumen |
|---|---|---|
| B1 | Doble escritura directa | El worker guarda en la base y después publica en el broker, sin garantía conjunta. |
| B2 | Publicar primero, guardar después | El worker publica el evento y después guarda el cambio. |
| B3 | **Outbox transaccional con relay por polling** | El cambio y el evento se guardan en la misma transacción. Un relay lee los eventos pendientes y los publica. |
| B4 | Outbox con captura de cambios (CDC) | Un conector lee el log de transacciones de la base y publica los eventos del outbox. |
| B5 | Transacción distribuida (2PC / XA) | La base y el broker participan de una misma transacción. |

### 4.2 Decisión

**Se elige B3: los workers escriben el cambio y el evento en la misma transacción de la base (tabla outbox) y un relay publica los eventos pendientes en el stream de eventos del broker (ADR-0003).**

**La idea en una frase.** El outbox convierte "guardar y avisar" en una sola operación atómica: se guarda el dato y una nota de aviso juntos, y un proceso aparte entrega la nota al broker.

### 4.3 Cómo funciona

**Estructura de la tabla.** El ejemplo es ilustrativo: la sintaxis depende del motor, que todavía no está decidido.

```sql
CREATE TABLE outbox (
  secuencia     BIGSERIAL PRIMARY KEY,   -- orden de creación
  id_evento     UUID NOT NULL UNIQUE,    -- identificador para la idempotencia
  tipo          TEXT NOT NULL,           -- por ejemplo 'ReservaCreada'
  clave         TEXT NOT NULL,           -- identificador de viaje (clave de orden)
  version       INT  NOT NULL,           -- versión del viaje tras el cambio
  payload       JSONB NOT NULL,          -- datos mínimos o referencias
  creado_en     TIMESTAMPTZ NOT NULL DEFAULT now(),
  publicado_en  TIMESTAMPTZ              -- vacío = pendiente de publicar
);
```

**Caso práctico.** Un viajero recibe la confirmación de un hotel. El worker ya interpretó el correo y hace una sola transacción:

```sql
BEGIN;
  -- 1. el cambio (idempotente: solo se aplica si la versión es más nueva)
  INSERT INTO reservas (id, viaje_id, hotel, check_in, version, ...)
  VALUES ('r-55', 'viaje-123', 'Hotel Sol', '2026-12-03', 4, ...)
  ON CONFLICT (id) DO UPDATE ... WHERE reservas.version < 4;

  -- 2. la nota de aviso, en la misma transacción
  INSERT INTO outbox (id_evento, tipo, clave, version, payload)
  VALUES (gen_random_uuid(), 'ReservaCreada', 'viaje-123', 4, '{"reserva":"r-55"}');
COMMIT;
```

O se guardan las dos filas o no se guarda ninguna. Después del `COMMIT`, el worker confirma el trabajo en el stream. La tabla queda así:

| secuencia | id_evento | tipo | clave | version | publicado_en |
|---|---|---|---|---|---|
| 901 | e-4c1… | ReservaCreada | viaje-123 | 4 | *vacío* |

**El relay.** Es un proceso, dentro del desplegable de ingesta, que repite este ciclo cada pocos segundos o menos:

1. Lee un lote de filas con `publicado_en` vacío, en orden de `secuencia`. Si hay varias instancias, usa un bloqueo de filas para no tomar las mismas (por ejemplo `SKIP LOCKED` en PostgreSQL).
2. Publica cada evento en el stream de eventos del broker.
3. Cuando el broker confirma, marca la fila con `publicado_en`.

El Panel API consume el stream, usa `id_evento` y `version` para ignorar duplicados y eventos viejos, y actualiza el read model.

**Qué pasa cuando algo falla:**

| Qué falla | Qué pasa | Resultado |
|---|---|---|
| El worker cae antes del `COMMIT`. | No hay cambio ni evento, y el trabajo no se confirmó. | Se reentrega el trabajo y se procesa de nuevo. |
| El worker cae después del `COMMIT` y antes de confirmar el trabajo. | El cambio y el evento ya están guardados. | Se reentrega el trabajo. El guardado detecta que la versión ya se aplicó y no duplica. |
| El broker está caído. | El relay no puede publicar y las filas quedan pendientes. Los workers siguen guardando. | Al volver el broker, el relay publica todo en orden. No se pierde nada. |
| El relay cae después de publicar y antes de marcar la fila. | Al reiniciar, vuelve a publicar ese evento. | El Panel API lo descarta por `id_evento` o `version`. |
| El Panel API está caído. | Los eventos esperan en el stream (ADR-0003). | Se consumen al volver. |

El outbox garantiza que cada evento llega **al menos una vez**, nunca cero. Evitar el efecto de un duplicado es trabajo de los consumidores.

**Limpieza y monitoreo:**

- Las filas publicadas se conservan una ventana corta y luego se eliminan. Para no cargar a la base con borrados masivos, la tabla se particiona por tiempo y se descartan las particiones viejas.
- Se monitorea la antigüedad de la fila pendiente más vieja. Si crece, el relay o el broker tienen un problema.

**Quién escribe el outbox.** Solo los workers. Los cambios manuales del viajero los guarda el Panel API sin pasar por el outbox. La analítica queda fuera de este mecanismo: su diseño se termina de desarrollar en iteraciones futuras.

### 4.4 Por qué no las otras alternativas

- **B1 (doble escritura).** Es la que produce el problema del contexto: si falla a mitad de camino, la base y el panel quedan inconsistentes y nadie lo detecta. Pone en riesgo QA-1 y QA-5.
- **B2 (publicar primero).** Es peor: el panel puede mostrar una reserva que nunca llegó a guardarse. Además, si la base falla después de publicar, el evento ya salió y no se puede retirar.
- **B4 (CDC).** Es la evolución natural: evita el polling y las escrituras extra, pero suma un componente y depende de una configuración específica del motor de base, que todavía no está decidido. Como el relay ya publica por un puerto, cambiar de polling a CDC no toca a los workers.
- **B5 (transacción distribuida).** Daría atomicidad real, pero los brokers candidatos no participan en transacciones distribuidas. Además acoplaría la disponibilidad de la base y la del broker.

## 5. Consecuencias

### Positivas

- La ingesta absorbe las ráfagas y los límites de los proveedores sin que el receptor se bloquee (QA-1, QA-6, QA-7).
- Ningún cambio se guarda sin su aviso ni se avisa sin guardarse: el panel recibe cada evento al menos una vez (QA-1, QA-5).
- Si el broker se cae, los workers siguen guardando cambios en la base y el relay se pone al día al volver. La recepción de notificaciones nuevas sí se interrumpe (ADR-0003, B-4).
- Los adaptadores por proveedor son módulos con puertos: sumar un transporte no toca el núcleo (R-2).
- Se implementa con una tabla y un proceso, sin depender de un motor de base ni de un broker concretos.

### Negativas y riesgos aceptados

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| W-1 | Un adaptador defectuoso afecta a los workers | Puede interrumpir la integración con otros proveedores (QA-8). | Aislamiento por proveedor dentro del worker: colas, pools y circuit breaker. |
| W-2 | Workers y Panel API comparten la base | Cuello de botella de escritura (QA-6) y acoplamiento por esquema. | Cada módulo es dueño de sus tablas. Read model separado para las lecturas. Medir con prueba de carga. |
| W-3 | La tabla outbox es la más escrita y puede crecer | Cada cambio implica dos escrituras, la tabla genera basura al borrar filas y crece si el broker queda caído mucho tiempo. | Partición por tiempo y descarte de particiones viejas. Alerta de crecimiento y capacidad prevista para la interrupción más larga que se quiera absorber. Evolución a CDC (B4). |
| W-4 | El relay agrega latencia y puede detenerse | El polling suma hasta un intervalo de demora y consume parte de los 5 minutos de QA-1. Si el relay se detiene, los eventos se acumulan y el panel deja de actualizarse sin que falle ningún otro componente. | Intervalo corto y presupuesto de tiempo por etapa (ADR-0003, B-1). Monitorear la antigüedad de la fila pendiente más vieja, con alerta. Reinicio automático. |
| W-5 | Entrega al menos una vez y orden solo por viaje | Eventos duplicados. Dos transacciones concurrentes pueden confirmarse en distinto orden que su `secuencia`. | Idempotencia por `id_evento` y `version` en los consumidores (ADR-0003, B-2). Un solo worker por viaje a la vez (clave del stream de trabajo) y descarte de versiones anteriores en el Panel API. |
