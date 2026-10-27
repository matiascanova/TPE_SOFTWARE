# ADR-0008: Resiliencia de la ingesta y aislamiento por proveedor

- **Estado:** Propuesto
- **Drivers de entrada:** Analisis-Inicial-y-Drivers.md, CU-02, CU-03, CRN-N1, QA-1, QA-5, QA-8, R-3, R-4, R-6.
- **ADR relacionados:** ADR-0001, ADR-0002, ADR-0003, ADR-0005, ADR-0006, ADR-0007, ADR-0009.

---

## 1. Contexto

Las fallas o caídas en la API de un proveedor de viajes no deben impactar el procesamiento de los demás proveedores (CRN-N1). Los mensajes que no puedan procesarse por errores de formato o reintentos agotados deben aislarse sin detener la ingesta global (QA-8), manteniendo un registro auditable de los motivos que no confunda problemas de disponibilidad con fallas de calidad de datos, y sin incurrir en costos de desplegables independientes por proveedor (R-6).

**Pregunta:** ¿Cómo aislar el consumo y la ejecución por proveedor, manejar las fallas transitorias de red y estructurar el descarte y registro de mensajes no procesables?

**Fuera de alcance:** Definición de parámetros de almacenamiento y retención en el broker (ADR-0009), Proceso de reconciliación de cambios post-interrupción (Postergado a iteración futura) y lógica interna de validación del adaptador (ADR-0006).

---

## 2. Decisión

**Se elige A2: Aislar la ingesta mediante particiones del stream de trabajo por proveedor con recursos propios y Circuit Breaker, derivando los mensajes no procesables a un stream de apartados y registrando sus metadatos y motivo codificado en BD sin almacenar el cuerpo.**

- Cada proveedor dispone de pools de conexiones, límites de concurrencia e instancias de Circuit Breaker independientes dentro del worker.
- Los reintentos por falla de proveedor se acotan a 3 intentos con espera creciente; las fallas de validez de contenido se separan de inmediato a apartados.
- La tabla de registros de apartados almacena `idMensaje`, `origen`, `claveDeOrden`, `correlacion`, motivo codificado e intentos, omitiendo datos de la reserva.

**Incorpora:** Patrones *Bulkhead* y *Circuit Breaker*, tácticas *Exception Detection*, *Retry*, *Removal from Service*, *Limit Event Response*, *Sanity Checking*, *Exception Handling* y *Monitor*. **Difiere:** Despliegue de servicios independientes por proveedor.

### Alternativas descartadas

| Alternativa | Motivo |
|---|---|
| A1.Pool compartido con reintento ilimitado | Permite que un proveedor lento agote los recursos globales de ingesta, violando CRN-N1 y QA-1. |
| A3.Un servicio desplegable por proveedor | Incrementa los costos operativos y la cantidad de componentes, violando R-6. |
| A4.Descartar mensajes e informar solo en log | Impide el reprocesamiento posterior y dificulta la auditoría objetiva del 99% de QA-8. |
| A5.Almacenar el cuerpo completo del apartado en BD | Aumenta la exposición de datos personales (R-4) e incrementa el volumen de escritura en BD (CRN-N2). |

---

## 3. Consecuencias

**Positivas**

- Garantiza el aislamiento de fallas evitando que un proveedor afecte el procesamiento de los demás (CRN-N1).
- Proporciona un registro estructurado para la medición objetiva de calidad de mensajes según QA-8.
- Protege la privacidad al evitar persistir datos personales en las tablas de auditoría de apartados (R-4).

**Negativas y riesgos aceptados**

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| RS-1 | Clasificación incorrecta de excepciones de red | Apertura tardía del circuito o conteo erroneo de QA-8 | Emplear bibliotecas probadas (ej. Resilience4j) y pruebas con stubs. |
| RS-2 | Retraso en clave por reintentos de mensajes con fallo | Incrementa la latencia en la partición afectada (QA-1) | Acotar los reintentos a un máximo de 3 con backoff corto. |
