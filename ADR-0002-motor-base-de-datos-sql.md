# ADR-0002: Motor de base de datos operacional (SQL / PostgreSQL)

- **Estado:** Propuesto
- **Fecha:** 14/10/2026
- **Decisores:** Equipo de diseño (grupo TPE)
- **Depende de:** [ADR-0001: Estilo arquitectónico](ADR-0001-estilo-arquitectonico.md) (C-lite)
- **Drivers de entrada:** [Análisis inicial y drivers](Analisis-Inicial-y-Drivers.md) (QA-1, QA-2, QA-4, QA-5, QA-6, QA-12, R-4)

---

## 1. Contexto y problema

C-lite tiene tres desplegables (Panel API, workers de ingesta y consumidor de analítica) y una base relacional compartida como almacén del **modelo de escritura**. Hay que decidir qué motor la implementa.

**Alcance de esta decisión.** Solo el almacenamiento operacional: usuarios, viajes, reservas, permisos de compartir, whitelists, auditoría y la tabla de outbox. Quedan fuera, y tienen su propio ADR:

- El **read model** del panel (caché o base documental, ADR de CQRS).
- El **almacén OLAP** de analítica (CU-05).
- El **broker** con log persistente.
- Los **tokens de correo**, que van en una bóveda aislada (QA-10) y no en esta base.

**Factores que el equipo propone como razones a favor de SQL:**

1. El equipo ya conoce SQL.
2. La velocidad del motor.
3. Que todos los datos tendrán el mismo formato y estructura.

Antes de decidir, el ADR evalúa si cada uno es una razón válida (sección 2), porque el tercero no se sostiene tal como está formulado.

## 2. Validez de los factores de decisión

### 2.1 Conocimiento previo del equipo: válido y de peso alto

Es una razón legítima en este contexto. El TPE es grupal, de plazo acotado, y evalúa que el prototipo sea trazable y consistente con la arquitectura documentada. Esa es la lógica del filtro de viabilidad del ADR-0001: lo que el equipo no domina se paga en tiempo de aprendizaje, bugs y deriva entre documentación y código.

Limitación: es una razón de **riesgo de ejecución**, no de adecuación técnica. Solo es suficiente si ninguna alternativa conocida falla en un QA crítico. Acá no ocurre: SQL cubre los QA Alta con tácticas conocidas (sección 5). También conviene saber cuánto pesa: sin este factor, las cuatro familias evaluadas quedan a menos de 5 puntos entre sí (ver 4.2). El conocimiento es lo que desempata, y hay que escribirlo como tal.

### 2.2 Velocidad: válido solo como "suficiente", no como "más rápida"

SQL no es intrínsecamente más rápido que las alternativas. Un almacén clave-valor distribuido responde mejor a lecturas por clave a escala masiva. Lo correcto es afirmar que PostgreSQL es **suficientemente rápido** para este volumen, y por tres razones:

- **El panel no lee de la base operacional.** El QA-2 (800 ms) y el QA-3 (FCP de 1,4 s) los sostiene el read model en caché del panel. La base operacional atiende escrituras, lecturas de gestión manual y reconstrucción del read model.
- **El volumen es moderado para un motor relacional.** Estimación del equipo, a verificar en el prototipo: decenas de millones de reservas en total y, en pico ×10, del orden de miles de escrituras por segundo. Eso está dentro del rango de un primario PostgreSQL bien indexado y con tablas particionadas.
- **Las consultas críticas son por clave** (usuario, viaje, proveedor + identificador externo) y se resuelven con índices.

Dónde sí limita: la escritura está en un único primario. Es el techo de QA-6 y se trata como riesgo (sección 6).

### 2.3 "Todos los datos tienen un formato y estructura similar": parcialmente válido
Los datos no van a ser todos exactamente iguales y no van a tener la misma estructura, va a haber una parte uniforme y una parte no uniforme:

| Dato | ¿Uniforme? | Tratamiento |
|---|---|---|
| Usuarios, cuentas, permisos de compartir, whitelists, auditoría, outbox | Sí | Tablas relacionales tradicionales |
| Viaje y campos comunes de una reserva (id, viaje, tipo, proveedor, estado, código de confirmación, inicio y fin en UTC con zona, precio y moneda) | Sí | Columnas tipadas con restricciones |
| Atributos propios del tipo: vuelo (número, terminal, asiento), hotel (dirección, habitación), auto (punto de retiro), tren (andén, coche) | **No** | Columna `detalle` en JSONB, validada por esquema según el tipo |
| Mensajes originales de proveedores y texto extraído de correos | **No** | JSONB, o almacenamiento de objetos si el volumen lo exige |

Consideraciones:

- La **uniformidad del modelo canónico** (ADR de adaptadores) es lo que permite que el núcleo sea relacional. Esa uniformidad no existe en el mundo externo, la crean los adaptadores al traducir (R-2).
- Además, **la uniformidad de la estructura es un argumento sobre el esquema, no sobre el motor**. Un modelo uniforme se puede guardar en un motor documental, y un modelo heterogéneo se puede guardar en uno relacional con columnas JSON. Pesa a favor de SQL solo en combinación con las razones de transaccionalidad (outbox) y de relaciones entre entidades (usuario, viaje, reserva, permisos).
- La heterogeneidad **choca con QA-12** (sumar trenes con un esfuerzo máximo de 2 días-persona sin modificar el núcleo). Si cada tipo de reserva fuera una tabla propia, agregar trenes exigiría una migración de esquema. Por eso el diseño usa núcleo tipado más detalle en JSONB.

**Conclusión:** la razón es válida para el núcleo del modelo y falsa para los atributos por tipo y los datos crudos. Se reformula como: *"el núcleo del dominio es relacional y uniforme; la variabilidad por tipo se aísla en un campo de detalle validado"*.

## 3. Alternativas consideradas

| ID | Alternativa | Resumen |
|---|---|---|
| 1 | **PostgreSQL** | Relacional con JSONB, particionado nativo, row-level security. |
| 2 | MySQL / MariaDB | Relacional de la misma familia, soporte JSON más limitado. |
| 3 | MongoDB | Base documental con esquema flexible y transacciones multi-documento. |
| 4 | DynamoDB / Cassandra | Clave-valor o wide-column distribuido, diseño orientado a patrones de acceso. |

## 4. Evaluación

Puntaje de 1 (débil) a 5 (fuerte). Son juicios del equipo, no mediciones. Se usa el mismo criterio que en el ADR-0001: pesos por prioridad del QA y un filtro de viabilidad.

| Criterio (peso) | PostgreSQL | MySQL | MongoDB | DynamoDB / Cassandra |
|---|---|---|---|---|
| Conocimiento del equipo y facilidad de implementar (3) | 5 | 5 | 2 | 1 |
| Garantías transaccionales para outbox y consistencia (3) | 5 | 5 | 3 | 2 |
| Rendimiento para el volumen estimado, QA-2/3 (2) | 4 | 4 | 4 | 5 |
| Ajuste al modelo de datos (2) | 5 | 4 | 4 | 2 |
| Evolucionabilidad, QA-12 (2) | 4 | 4 | 5 | 4 |
| Disponibilidad, QA-4 (3) | 4 | 4 | 4 | 5 |
| Escalabilidad de escritura, QA-6 (3) | 3 | 3 | 4 | 5 |

Supuesto de la columna 1: el equipo tiene experiencia en SQL y poca o ninguna en NoSQL.

## 5. Decisión

**Se elige PostgreSQL como motor de la base operacional, con un núcleo relacional tipado y un campo `detalle` en JSONB validado por esquema para los atributos propios de cada tipo de reserva.**

### 5.1 Por qué PostgreSQL

- **Outbox transaccional.** El ADR-0001 exige outbox para no perder eventos entre guardar el cambio y publicarlo en el broker. Eso requiere una transacción ACID que cubra la tabla de negocio y la tabla de outbox. Es la razón arquitectónica más fuerte para un motor relacional, más que la velocidad o la forma de los datos.
- **Relaciones entre entidades.** Usuario, viaje, reserva y permisos de compartir son relacionales, y el QA-11 (solo personas designadas ven un itinerario) se puede reforzar con restricciones de integridad y *row-level security*.
- **JSONB indexable.** Permite el campo `detalle` con índices solo donde se consulta y absorbe nuevos tipos de transporte sin migrar (QA-12).
- **Particionado nativo** por región o por usuario, que sirve a la escala (QA-6) y a la residencia de datos (R-4, una instancia por región).
- **Fechas.** `timestamptz` junto con la zona de origen resuelve el almacenamiento en UTC con zona del viaje.
- **Replicación y failover** maduros, con servicio gestionado o herramientas conocidas, para la disponibilidad (QA-4).
- **Prototipo.** Se levanta con una imagen en docker-compose y es reproducible por todo el grupo.

### 5.2 Por qué no MySQL

Cubre casi todo igual y también está en la familia que el equipo conoce. Pierde en rasgos que este diseño usa: indexación JSON menos directa, particionado y seguridad por fila menos completos. Si la experiencia del equipo fuera principalmente en MySQL, **sería una elección razonable** y el resto de la decisión (SQL, núcleo tipado más detalle JSON) se mantiene sin cambios.

### 5.3 Por qué no MongoDB

Se ajusta bien a la variabilidad por tipo y a QA-12, pero el núcleo es relacional y uniforme, de modo que esa flexibilidad se usaría poco. Las garantías transaccionales para el outbox son más débiles, y el equipo no tiene la experiencia, lo que lo saca por el filtro de viabilidad.

### 5.4 Por qué no DynamoDB / Cassandra

Es la mejor opción en escalabilidad de escritura y disponibilidad, pero exige diseñar el modelo a partir de los patrones de acceso y tiene transacciones limitadas, lo que complica el outbox y la gestión manual de reservas. Es difícil de reproducir localmente y añade un acoplamiento a un proveedor cloud. Falla el filtro de viabilidad y su ventaja (escala de escritura) no es necesaria con el volumen estimado.

## 6. Consecuencias

### Positivas

- Outbox transaccional simple y verificable, que sostiene la entrega confiable de eventos (QA-1, QA-5).
- Menor riesgo de ejecución: el equipo trabaja con una tecnología que domina, lo que ayuda a la trazabilidad del prototipo.
- Modelo claro, con integridad referencial, y extensible a trenes sin migraciones gracias al campo `detalle`.
- Camino directo a particionado por región (R-4).

### Negativas y riesgos aceptados

| Riesgo | Efecto | Mitigación |
|---|---|---|
| Un único primario de escritura | Techo de escritura en picos ×10 (QA-6) y punto único de falla ante QA-4. | Particionado, índices ajustados, outbox con lotes. Si el volumen real supera el estimado: particionar por región o usuario con herramientas de sharding, o mover tablas de alto volumen a otro almacén. |
| Failover de la base | Las escrituras fallan durante unos segundos a minutos, y el presupuesto mensual de QA-4 es de unos 5 minutos. | Standby sincrónico multi-AZ con failover automático, reintentos con backoff en los clientes. El panel sigue sirviendo el read model en caché (QA-5). |
| Mal uso de JSONB | Se pierden restricciones y la base se convierte en un depósito sin esquema. | Los campos que se consultan o filtran van como columnas tipadas. Cada tipo tiene un esquema de validación en su adaptador, parte del contrato (spec-driven). |
| Base compartida entre Panel API y workers | Acoplamiento por esquema, que ya se registró en el ADR-0001. | Cada módulo es dueño de sus tablas. El acceso cruzado va por vistas o por eventos. Migraciones versionadas (Flyway, Liquibase o similar). |
| Residencia de datos entre regiones | El compartir itinerarios entre regiones se complica con instancias por región. | Se resuelve en el ADR de multi-región. |
