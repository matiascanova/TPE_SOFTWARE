# ADR-0006: Modelo canónico de reserva (núcleo común más detalle por tipo) y adaptador con contrato de tres operaciones

- **Estado:** Propuesto
- **Fecha:** 23/10/2026
- **Decisores:** Equipo de diseño (grupo TPE)
- **Drivers de entrada:** Analisis-Inicial-y-Drivers.md y Iteracion-1.md. CU-02, CU-03, CU-04. Otros drivers: R-2, R-3, R-6, QA-8, QA-12 y CRN-N1.
- **ADR relacionados:** ADR-0001 (estructura general), ADR-0002 (workers y outbox), ADR-0005 (motor de BD), ADR-0007 (contratos de mensajes y semántica de entrega)

---

## 1. Contexto y problema

Cada proveedor de viaje (aerolíneas, hoteles, rentadoras) y de correo expone su propio formato, que Trotamundos no puede modificar (R-2, R-3). El worker guarda cada reserva en la BD en un único formato, y el resto del sistema parte de esa BD: el outbox lleva solo avisos con referencias (ADR-0002) y el read model del panel es una copia derivada de ella (ADR-0004). Además, el sistema debe incorporar un medio de transporte nuevo sin tocar el núcleo (QA-12) y debe poder decir, para cada mensaje entrante, si es válido o inválido (QA-8). El modelo canónico, la interfaz del adaptador y el criterio de validez se deciden juntos porque cada uno condiciona a los otros dos. Esta decisión va antes que los contratos de mensajes (ADR-0007) y que la resiliencia (ADR-0008), que usan sus definiciones.

Este documento responde estas preguntas:

**Desicion A:** **¿Qué modelo canónico representa una reserva para todo el sistema?**

**Desicion B:** **¿Qué interfaz implementa cada adaptador y cuándo un mensaje es válido o inválido?**

**Tensiones:**

- **Uniformidad frente a fidelidad.** Un vuelo, un hotel y un auto tienen datos distintos. Un modelo demasiado genérico pierde información útil para el panel (CU-01, CU-02). Un modelo demasiado específico obliga a modificar el núcleo con cada medio nuevo (QA-12).
- **Contratos ajenos.** Los formatos de los proveedores son condiciones dadas (R-2, R-3) y cambian sin aviso. El modelo propio debe absorber esas diferencias sin trasladarlas al resto del sistema.
- **Validez medible.** QA-8 exige procesar el 99% de los mensajes válidos e identificar y registrar el 99% de los inválidos. Eso requiere un criterio de validez objetivo y automatizable, no una apreciación caso por caso.
- **Plazo de implementación.** El grupo implementa un prototipo en tiempo acotado (R-6). La solución no puede requerir infraestructura ni herramientas que sumen más piezas a operar que las ya definidas en la iteración 1.

**Fuera de alcance:**

- Campos de los mensajes del stream de trabajo y del de eventos, y semántica de entrega (ADR-0007).
- Colas, pools y circuit breaker por proveedor, cola de mensajes inválidos y su registro, y reconciliación con el proveedor (ADR-0008).
- Motor de la BD y definición física de tablas y columnas (ADR-0005).
- Verificación de autenticidad de las notificaciones (la hace el receptor, según la iteración 1).
- Extracción de reservas desde el texto de los correos (CU-03, QA-9). El adaptador de correo cumple este mismo contrato, pero su pipeline se decide en otra iteración.
- Mapeos concretos de cada proveedor y esquemas campo por campo.

---

## 2. Criterios de decisión

| Criterio | Qué mide en esta decisión | Se usa en |
| --- | --- | --- |
| QA-12 | Si agregar un medio de transporte exige modificar el núcleo o los tipos existentes, o solo agregar elementos nuevos. | A / B |
| R-2 y R-3 | Si el diseño absorbe formatos de proveedores que no se pueden cambiar ni controlar. | A / B |
| QA-8 | Si existe un criterio objetivo de válido e inválido, y si cada inválido sale con un motivo registrable. | A / B |
| CU-02 y CU-03 | Si la BD guarda un solo formato de reserva y todo lo que se deriva de ella (outbox, read model y panel) parte de él sin conocer al proveedor. | A / B |
| CRN-N1 | Si un proveedor con mensajes defectuosos, o un adaptador roto, afecta a la ingesta de los demás. | B |
| R-6 | Esfuerzo de implementación para el grupo y piezas nuevas que operar. | A / B |

---

## 3. Decisión A: modelo canónico de reserva

### 3.1 Alternativas consideradas

| ID | Alternativa | Resumen |
| --- | --- | --- |
| A1 | Modelo plano único (superconjunto) | Una sola estructura con todos los campos de todos los tipos de reserva. Los campos que no aplican quedan vacíos. |
| A2 | **Núcleo común más detalle por tipo, con esquema versionado** | Una reserva tiene un núcleo obligatorio igual para todos los tipos y un bloque de detalle cuyo esquema depende del tipo (vuelo, hotel, auto) y de su versión. |
| A3 | Sin modelo canónico (un modelo por proveedor) | Cada proveedor conserva su estructura y se traduce recién al leer en el panel. |
| A4 | Estándar externo de la industria como modelo interno | Se adopta un estándar sectorial (por ejemplo OpenTravel o NDC de IATA) como representación interna de la reserva. |

### 3.2 Decisión

**Se elige A2: toda reserva se representa con un núcleo común obligatorio y un detalle por tipo cuyo esquema está versionado.**

**Descripción de la decisión.** El núcleo contiene lo común a todos los tipos y tiene dos partes. Lo que produce el adaptador: proveedor, identificador externo de la reserva, tipo, estado (confirmada, modificada o cancelada), inicio y fin, versión del esquema del detalle y, cuando el proveedor los informa, código de confirmación, precio y moneda. Lo que asigna el sistema al guardar: identificador de la reserva, viaje al que pertenece y versión de la reserva (ADR-0005). Las fechas se guardan como instante más la zona de origen del viaje. El usuario no es un campo de la reserva, porque se alcanza por el viaje. La hora del hecho tampoco: la devuelve el adaptador junto a la reserva y viaja en el sobre del mensaje (ADR-0007). El detalle contiene los datos propios de cada tipo (por ejemplo, segmentos y números de vuelo para un vuelo, o propiedad y fechas de entrada y salida para un hotel) y se guarda como documento JSONB (ADR-0005). Aplica las tácticas de integrabilidad *Abstract Common Services* y *Tailor Interface* de la cátedra, y la táctica de modificabilidad *Polymorphism*: el núcleo es la parte común y cada tipo especializa el detalle.

- **Núcleo mínimo y obligatorio.** Los campos del núcleo que informa el proveedor son obligatorios, salvo el código de confirmación, el precio y la moneda, que pueden faltar si el proveedor no los informa. El núcleo no contiene datos específicos de un tipo.
- **Esquema por tipo y versión.** Cada par tipo y versión tiene su propio esquema de validación. El detalle de una reserva se valida contra el esquema de su tipo y versión.
- **Tipo nuevo sin tocar lo existente.** Incorporar un medio de transporte agrega un esquema de detalle y un adaptador. No modifica el núcleo, los esquemas existentes ni los adaptadores existentes.
- **Evolución compatible.** Dentro de una versión solo se agregan campos opcionales. Un cambio incompatible crea una versión nueva, y las dos conviven mientras haya reservas guardadas con la anterior.
- **Datos sin equivalente no se descartan.** Lo que el proveedor envía y el modelo no representa se conserva en un bloque de extensión del detalle. Ningún consumidor depende de ese bloque.

### 3.4 Por qué no las otras alternativas

- **A1. Modelo plano único.** Cada medio nuevo agrega columnas o campos al modelo común y obliga a modificar el núcleo, lo que incumple QA-12. Además, la mayoría de los campos queda vacía para cada reserva, y el modelo mezcla datos de tipos distintos sin que se pueda validar cuáles corresponden.
- **A3. Sin modelo canónico.** El panel, el outbox y el read model tendrían que conocer a cada proveedor, lo que incumple el criterio de formato único aguas abajo y multiplica el costo de cada proveedor nuevo (QA-12). Tampoco permite un criterio común de validez (QA-8).
- **A4. Estándar externo como modelo interno.** Los estándares sectoriales son extensos y cubren por lo general un segmento (aéreo u hotelero). Adoptar uno como modelo interno ataría el núcleo a ese segmento y obligaría al grupo a dominar una especificación grande, lo que va contra R-6. Los estándares siguen siendo útiles como fuente para los mapeos de cada adaptador, porque R-2 exige usar las APIs que los proveedores ya exponen.

---

## 4. Decisión B: contrato del adaptador y criterio de validez

### 4.1 Alternativas consideradas

| ID | Alternativa | Resumen |
| --- | --- | --- |
| B1 | **Puerto único con tres operaciones, un adaptador por proveedor y medio, registrado por configuración** | Todos los adaptadores implementan la misma interfaz. Los workers cargan los adaptadores que figuran en la configuración. El adaptador solo traduce y valida. |
| B2 | Adaptador con salida libre y normalización en el núcleo | Cada adaptador devuelve lo que quiere y el núcleo contiene la lógica de normalización y validación, con ramas por proveedor. |
| B3 | Plug-in con descubrimiento dinámico en ejecución | Los adaptadores se publican como plug-ins que el sistema descubre y carga mientras corre. |
| B4 | Adaptador como servicio independiente por proveedor | Cada adaptador es un servicio desplegable propio que se invoca por red. |

### 4.2 Decisión

**Se elige B1: cada adaptador implementa una interfaz de tres operaciones, devuelve un resultado explícito de válido o inválido y se registra por configuración en los workers.**

**Descripción de la decisión.** La interfaz (patrón *Wrapper*, con la táctica *Encapsulate*) oculta el formato del proveedor detrás de tres operaciones. **Describir** declara el proveedor, el medio de transporte y las versiones de esquema que produce. **Traducir** recibe un mensaje del proveedor y devuelve *Válido* con la reserva canónica y su hora del hecho, o *Inválido* con un motivo codificado. **Consultar** recibe un identificador externo, obtiene del proveedor los detalles de la reserva y aplica el mismo criterio de validez que Traducir: devuelve Válido con la reserva canónica y su hora del hecho, Inválido con un motivo codificado si la respuesta no cumple el contrato, o una falla del proveedor. Las respuestas del adaptador son datos: el adaptador no escribe en la BD, no publica en el broker y no reintenta. Eso le corresponde al worker y a ADR-0008. La reconciliación con el proveedor al restablecerse su API (consultar lo pendiente o el estado actual, ADR-0002 y ADR-0003) también la define ADR-0008, que puede agregar operaciones a esta interfaz sin cambiar las tres existentes.

- **Válido.** Un mensaje es válido si cumple las tres condiciones. Primera: su estructura y tipos respetan el contrato publicado por el proveedor. Segunda: se traduce a un núcleo con todos sus campos obligatorios y a un detalle conforme al esquema de su tipo y versión. Tercera: cumple las invariantes de dominio (identificador externo no vacío, fin no anterior al inicio, estado reconocido). Es la táctica *Validate Input* aplicada en el borde.
- **Inválido.** Un mensaje que incumple cualquier condición es inválido, incluido el de un tipo de reserva sin adaptador (motivo «tipo no soportado»). El resultado lleva un motivo codificado (estructura, campo obligatorio, invariante o tipo no soportado) y el mensaje no produce ningún cambio en la BD. El registro y la cola de inválidos se definen en ADR-0008.
- **Contenido distinto de comunicación.** Un error de red, un tiempo de espera o una respuesta de error del proveedor al consultar es una *falla del proveedor*, no un mensaje inválido. Así los inválidos miden la calidad de los mensajes y no la disponibilidad del proveedor.
- **Lector tolerante.** Los campos desconocidos agregados por un proveedor no hacen inválido a un mensaje. Se conservan en el bloque de extensión (Decisión A).
- **Registro por configuración y dependencias acotadas.** Los workers cargan los adaptadores declarados en la configuración (*Configuration-Time Binding*). Un adaptador solo depende del contrato de su proveedor y del modelo canónico, y el núcleo no conoce clases de proveedores.

### 4.3 Cómo funciona

Una aerolínea notifica la cancelación de un vuelo. El receptor la encola y un worker la toma. El worker elige el adaptador por proveedor y medio de transporte. Si la notificación no trae la reserva completa, llama a Consultar con el identificador externo; si la trae, llama a *Traducir*. El adaptador devuelve *Válido* con una reserva canónica de tipo vuelo, estado cancelada, y el worker guarda el cambio y el evento en una sola transacción (iteración 1). Si el mensaje trae un campo obligatorio ausente, devuelve *Inválido* con el motivo «campo obligatorio» y el worker no escribe nada.

| Qué falla | Qué pasa | Resultado |
| --- | --- | --- |
| Mensaje sin un campo obligatorio | *Traducir* devuelve *Inválido* con motivo «campo obligatorio». | No hay cambio en la BD. El inválido queda registrado según ADR-0008. |
| Mensaje de un tipo sin adaptador (por ejemplo un tren) | *Traducir* devuelve *Inválido* con motivo «tipo no soportado». | No hay cambio en la BD. Al agregar el adaptador y el esquema, los mensajes siguientes se procesan. |
| El proveedor no responde al llamar a *Consultar* | El adaptador señala falla del proveedor, no inválido. | El worker aplica reintentos y circuit breaker de ADR-0008. Los demás proveedores no se ven afectados. |
| Un proveedor agrega un campo opcional nuevo | Se conserva en el bloque de extensión. | El mensaje sigue siendo válido. |
| Un proveedor cambia un campo de forma incompatible | Sus mensajes pasan a ser inválidos por estructura. | Aumentan los inválidos de ese proveedor únicamente, y se corrige su adaptador sin tocar el resto. |

### 4.4 Por qué no las otras alternativas

- **B2. Salida libre y normalización en el núcleo.** El núcleo acumularía ramas por proveedor y por tipo, lo que incumple QA-12 y no permite aislar el efecto de un proveedor defectuoso (CRN-N1). La validez quedaría repartida entre muchos lugares y sería difícil de verificar (QA-8).
- **B3. Plug-in con descubrimiento dinámico.** Resuelve QA-12 pero agrega un mecanismo de descubrimiento y carga en ejecución que el prototipo no necesita, porque los adaptadores se conocen al desplegar. Suma complejidad y superficie de falla sin mejorar ningún criterio frente a B1, y compromete R-6. Si más adelante se necesita incorporar adaptadores sin redesplegar, se puede pasar a B3 sin cambiar la interfaz de B1.
- **B4. Servicio independiente por proveedor.** Da el mejor aislamiento (CRN-N1), pero multiplica las piezas desplegables y operables, que ADR-0001 y ADR-0002 descartaron por costo de implementación y operación (R-6). El aislamiento por proveedor se obtiene dentro del worker con las colas y los pools de ADR-0008. Como el contrato de B1 es el mismo, extraer un adaptador a servicio propio queda como evolución prevista sin reescribirlo.

---

## 5. Consecuencias

### Positivas

- Un único formato de reserva en la BD, del que parten el outbox (con avisos que solo referencian la reserva), el read model y el panel, que no conocen a ningún proveedor (CU-02, CU-03).
- Agregar un medio de transporte requiere un adaptador y un esquema, sin modificar el núcleo ni lo existente (QA-12).
- Existe un criterio de válido e inválido objetivo y automatizable, con motivo codificado, que permite medir el 99% de QA-8 con proveedores simulados.
- Un adaptador que falla o un proveedor con mensajes defectuosos queda acotado a su propio adaptador (CRN-N1).
- Los adaptadores son fáciles de probar de forma aislada con mensajes válidos e inválidos, lo que favorece la implementación en plazo (R-6).

### Negativas y riesgos aceptados (máximo 5)

| ID | Riesgo | Efecto | Mitigación |
| --- | --- | --- | --- |
| MC-1 | Se pierde o deforma información al normalizar el formato del proveedor. | Afecta la corrección de lo que muestra el panel (CU-02) y la medida de QA-8. | Bloque de extensión para datos sin equivalente. Pruebas de cada mapeo con mensajes de muestra del proveedor o simulados. |
| MC-2 | Los esquemas por tipo y versión se mantienen a mano y pueden desalinearse del código de los adaptadores. | Aumenta el costo de mantenimiento (QA-12, R-6). | Pruebas de contrato por adaptador con mensajes válidos e inválidos. Una sola fuente de esquemas compartida por adaptadores y Panel API. |
| MC-3 | El núcleo mínimo puede resultar insuficiente para un medio nuevo (por ejemplo un tren con muchas escalas). | Obliga a modificar el núcleo y contradice QA-12. | Núcleo reducido a lo común. Un cambio incompatible del núcleo crea una versión nueva del esquema y convive con la anterior. |
| MC-4 | JSONB no impone el esquema por sí solo, así que un detalle inválido puede entrar si se escribe sin pasar por la validación (alta manual, CU-04). | Afecta la consistencia de los datos y de QA-8. | El Panel API usa la misma validación del modelo canónico que los adaptadores. Los campos obligatorios del núcleo van con restricciones de obligatoriedad en la BD (ADR-0005). |
| MC-5 | Un criterio de validez demasiado estricto rechaza mensajes que los proveedores envían con pequeños desvíos. | Baja el porcentaje de mensajes válidos procesados (QA-8). | Lector tolerante con campos desconocidos. Revisión periódica de los motivos de inválidos por proveedor y ajuste del adaptador. |
