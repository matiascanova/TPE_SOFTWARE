# Plantilla de iteración ADD (Attribute-Driven Design 3.0)

Sirve para redactar iteraciones nuevas y corregir existentes. Las líneas con `>` son instrucciones y se borran en el documento final. Lo que está entre `<...>` es un campo a completar.

## Instrucciones para la IA

### Cómo redactar una iteración nueva

1. Leer los drivers, la iteración anterior (o el backlog inicial si es la primera) y esta plantilla.
2. Fijar primero el objetivo (Paso 2) y comprobar que es acotado. Recién después completar el resto en orden.
3. Cada decisión estructural tiene su ADR (con la plantilla de ADR). La iteración lo referencia por ID en el Paso 4 y no repite sus alternativas, motivos ni consecuencias.
4. Si falta un dato, dejar `<PENDIENTE: qué falta>`. No inventar drivers, cifras, IDs de ADR, tecnologías, interfaces ni estados.

### Cómo corregir una iteración existente

1. Leer la iteración completa y esta plantilla.
2. Conservar el fondo (objetivo, decisiones, vistas, riesgos, estados) y reubicar cada pieza en su ítem. No cambiar lo decidido.
3. Aplicar las reglas. Si cumplirlas obliga a recortar, agrupar o mover contenido a un ADR, hacerlo sin perder lo esencial.
4. Si falta contenido obligatorio, dejar `<PENDIENTE: qué falta>`.
5. Entregar la iteración corregida y, aparte, una lista breve de lo que se movió, se recortó o quedó pendiente.

## Reglas obligatorias

1. **Todo se completa.** Los pasos 1 a 7 siempre están. Solo son opcionales los ítems marcados «(opcional)». Lo opcional que no aplica se omite entero.
2. **Un objetivo por iteración, del tamaño justo.** Puede ser un driver importante, un conjunto de drivers similares o un conjunto de relacionados (CU + QA). Se enuncia en una frase verificable. Si hay dos objetivos, son dos iteraciones.
3. **Máximo 10 drivers seleccionados que no sean CU.** Los «Principales» (los que definen el éxito de la iteración) se marcan con `*`; el resto es contexto. Los CU no tienen límite.
4. **No se copia el catálogo de drivers.** La iteración 1 resume las entradas por categoría. Las siguientes parten del Kanban. Solo se citan los drivers que condicionan o se tocan en la iteración.
5. **Cada dato vive en un solo lugar.** El rationale, las alternativas descartadas (con su motivo) y las consecuencias de cada decisión viven en su ADR. La iteración los referencia por ID y solo nombra algunas descartadas, sin justificarlas.
6. **Toda decisión estructural lleva ADR:** un concepto de diseño, una tecnología con alternativa real o un compromiso entre drivers. Una decisión por ADR. Todas las decisiones se registran en la tabla del Paso 4; las menores, sin ADR, llevan una frase de justificación.
7. **Conceptos tipificados y atados a drivers.** Cada concepto es reference architecture, patrón, táctica o componente externo (y cada tecnología se marca como tal), y cita al menos un driver. Si no hay alternativas realistas, se escribe «sin alternativas registradas». No se inventan para llenar la celda.
8. **Sin pesos.** No se escriben puntajes, ponderaciones ni rankings. Los valores medibles del driver («< 100 ms», «99.9 %») sí se escriben.
9. **Estado por evidencia, no por intención.** Un driver es Abordado solo si hay elementos, responsabilidades, interfaces o despliegue que lo satisfacen y su response measure está verificada o razonada con evidencia (test, prototipo, análisis). Si falta parte de eso es Parcial. Si no se tocó, No abordado. Cada cambio de estado cita su evidencia y lo que falta (Paso 7.3).
10. **Vistas simples, con clave y nombres coherentes.** Todo diagrama indica su tipo de vista (módulo, C&C, asignación o secuencia), lleva clave y usa los mismos nombres que la tabla del Paso 5. Es preferible más de una vista simple a una sola abrumadora para los stakeholders.
11. **Interfaces honestas.** Las externas se derivan del diagrama de contexto y las internas de los diagramas de secuencia. Lo que no está detallado se lista como pendiente en el bloque de interfaces del Paso 5, con la iteración donde se define. No se inventan contratos.
12. **Riesgos con destino.** Los riesgos viven en los ADR y no se repiten. Se agrupan en concerns nuevas y solo se registran las aceptadas o no resueltas, listas para atacar en próximas iteraciones (máximo 5). Las concerns nuevas reciben un ID provisional que continúa la numeración del catálogo (CRN-n). Nunca se reutiliza un ID.
13. **Corto, concreto y sin repetir.** Una idea por frase, en afirmativo. No se explican conceptos conocidos (CQRS, Kafka, JWT): se nombran y, si hace falta, se enlaza una referencia. La iteración cabe en dos páginas sin contar los diagramas.
14. **Coherencia con lo anterior.** No contradice decisiones de iteraciones o ADR previos. Si lo hace, indica qué ADR reemplaza.

---

## Plantilla

# Iteración <N> de <Nombre del sistema>: <objetivo en una frase>

- Estado: <Borrador para revisión | Revisado>
- Fecha: <DD/MM/AAAA>

## Paso 1. Revisar las entradas del diseño

### 1.1 Contexto

<Un párrafo: qué sistema se diseña, en qué estado está el desarrollo y qué decisiones previas (ADR o iteraciones, por ID) condicionan esta iteración.>

### 1.2 Entradas

**Iteración 1**

| Categoría | Detalle |
|---|---|
| Propósito del diseño | <Greenfield, brownfield, dominio nuevo o maduro> |
| Primary functionality | <IDs de CU> |
| QA scenarios primarios | <IDs con su response measure> |
| Constraints | <IDs> |
| Concerns | <IDs> |

**Iteración 2 en adelante**

| Not Yet Addressed | Partially Addressed | Completely Addressed |
|---|---|---|
| <IDs> | <IDs> | <IDs> |

Drivers nuevos desde la iteración anterior: <IDs con una línea cada uno, o «ninguno»>

> Qué va: se conserva solo la variante que corresponde. La iteración 1 carga el design backlog entero en «Not Yet Addressed». Las siguientes copian el Kanban de salida de la anterior, sin reevaluarlo.

## Paso 2. Establecer el objetivo de la iteración seleccionando drivers

**Objetivo:** <Una frase verificable: qué drivers quedan abordados y hasta qué nivel al cerrar la iteración.>

**Tipo:** <Un driver importante | Drivers similares | Drivers relacionados (CU + QA)>

| Driver | Motivo por el que condiciona las decisiones |
|---|---|
| <ID>* | <Motivo, con su response measure si es QA> |
| <ID> | <Motivo> |

> Qué va: el objetivo es uno solo. Los drivers con `*` (Principales) definen el éxito de la iteración; los demás solo restringen las decisiones. Si un driver no cambia ninguna decisión, no se lista.

## Paso 3. Elegir uno o más elementos del sistema para refinar

- **Elementos seleccionados:** <Elementos que se refinan. En la iteración 1, indicar si es el sistema completo (contexto).>
- **Tipo de refinamiento:** <Descomposición (top-down) | Combinación (bottom-up) | Mejora>
- **Límite y fuera de alcance:** <Una o dos frases: hasta dónde se refina y qué se deja para después, con la iteración o el ADR donde se trata.>

## Paso 4. Elegir conceptos de diseño y registrar las decisiones

### 4.1 Decisiones

| Decisión | Tipo | Drivers | ADR | Descartadas | Justificación (solo si es menor) |
|---|---|---|---|---|---|
| <Nombre> | <Reference architecture / Patrón / Táctica / Componente externo / Tecnología> | <IDs> | <ADR-NNNN / «menor»> | <Hasta 3, o «sin alternativas registradas»> | <Una frase> |

### 4.2 Cómo se combinan (opcional)

<Una a tres frases sobre cómo interactúan los conceptos y el costo conjunto que ningún ADR individual refleja. Omitir si hay un solo concepto.>

> Qué va: una fila por cada concepto y por cada tecnología elegida en el Paso 5 (se agrega con tipo «Tecnología»). La tabla nombra, no justifica: el motivo de elegir y de descartar vive en el ADR. Solo las decisiones menores se justifican acá, en una frase.

## Paso 5. Instanciar elementos, asignar responsabilidades y definir interfaces

| Elemento | Responsabilidad (una frase) | Tecnología | Se comunica con (mecanismo) |
|---|---|---|---|
| <Nombre> | <Responsabilidad asignada> | <Nombre, o PENDIENTE: concern o iteración donde se elige> | <Elemento y mecanismo: REST, evento, SQL…> |

**Interfaces (opcional):**

- <Elemento> · <Operación, o método y ruta con versión>: <entrada → salida; errores; si es un evento, tópico y clave>
- Pendientes: <Interfaces que por ahora solo existen como comunicación en la tabla, y la iteración donde se definen>

> Qué va: cada elemento nuevo tiene una responsabilidad de alta cohesión. Si no cabe en una frase, el elemento hay que dividirlo. El tipo de elemento va en la clave de la vista, no en la tabla. Las interfaces se listan solo cuando ya se pueden definir (las externas salen del diagrama de contexto; las internas, de los diagramas de secuencia del Paso 6). Si no se puede definir ninguna ni hay pendientes que registrar, se omite el bloque.

## Paso 6. Esbozar vistas

> Las decisiones y su rationale se registran en el Paso 4. Repetir el bloque por cada vista. Los diagramas son sketches; se incluyen como Mermaid, imagen o enlace. Un diagrama de secuencia es una vista más y sirve para derivar las interfaces internas.

**Vista: <Nombre>**

- Tipo de vista: <Módulo | Component & Connector | Asignación | Secuencia>
- Qué muestra: <Una frase.>
- Diagrama: <Insertar el diagrama.>
- Clave: <Símbolos, tipos de elemento, tipos de flecha y colores.>

## Paso 7. Analizar el diseño y revisar el objetivo y el propósito del diseño

### 7.1 Concerns nuevas

| ID | Concern | Riesgos de origen | Mitigación prevista |
|---|---|---|---|
| <CRN-n> | <Descripción> | <IDs de riesgos en los ADR> | <Mitigación o iteración donde se trata> |

> Qué va: los riesgos de los ADR (por ID), agrupados por causa común; solo los aceptados o no resueltos. Si no hay, escribir «ninguna».

### 7.2 Kanban al cierre

| Not Yet Addressed | Partially Addressed | Completely Addressed |
|---|---|---|
| <IDs> | <IDs> | <IDs> |

> Qué va: solo IDs, sin texto. Incluye todos los drivers del backlog. Es la entrada del Paso 1 de la próxima iteración.

### 7.3 Evaluación y resultado

- **¿Se alcanzó el objetivo del Paso 2?** <Sí | Parcial | No>. <Justificación con decisiones (por ADR), vistas y estados.>
- **¿Se cumple el propósito del diseño?** <Sí | Todavía no>. <Una frase.>
- **Drivers que cambiaron de estado:** <ID: estado anterior → estado nuevo. Evidencia y lo que falta.>
- **Resultado:** <Tres a cinco líneas: estructura obtenida, decisiones principales (por ID de ADR) y pendientes que condicionan la siguiente iteración.>
- **Próximo objetivo:** <Propuesta de objetivo para la siguiente iteración, o «cierre del round» con el motivo.>

---

## Lista de control

**Encabezado y Paso 1**

- [ ] ¿Solo está la variante de entradas que corresponde (iteración 1 o posteriores)?
- [ ] ¿Se resumieron las entradas sin copiar el catálogo de drivers?

**Paso 2**

- [ ] ¿El objetivo es uno solo, de una frase y verificable?
- [ ] ¿Hay como máximo 10 drivers que no son CU, con los Principales marcados con `*` y cada uno con su motivo?

**Pasos 3 y 4**

- [ ] ¿Están indicados el tipo de refinamiento, su límite y lo que queda fuera de alcance (con dónde se trata)?
- [ ] ¿Cada concepto y cada tecnología está en la tabla del Paso 4 con tipo, drivers, ADR (o justificación si es menor) y descartadas?
- [ ] ¿Se nombran sin justificarlos ni puntuarlos (eso va en el ADR)?

**Paso 5**

- [ ] ¿Cada elemento tiene responsabilidad de una frase, tecnología (o PENDIENTE con dónde se elige) y mecanismo de comunicación?
- [ ] ¿Las interfaces que no se pueden definir todavía figuran como pendientes con su iteración?

**Paso 6**

- [ ] ¿Cada vista indica su tipo de vista y tiene clave?
- [ ] ¿Los nombres de los diagramas coinciden con los de la tabla del Paso 5?
- [ ] ¿Las vistas son simples (mejor varias chicas que una abrumadora)?

**Paso 7**

- [ ] ¿Cada cambio de estado de 7.3 cita evidencia y respeta la definición de Abordado?
- [ ] ¿Hay 5 concerns nuevas o menos, cada una ligada a riesgos de ADR y con ID provisional que continúa el catálogo?
- [ ] ¿El Kanban de 7.2 incluye todos los drivers del backlog?
- [ ] ¿La evaluación dice si se continúa y propone el próximo objetivo?

**General**

- [ ] ¿Cada decisión estructural tiene su ADR y no se repite su contenido acá?
- [ ] ¿Es coherente con las iteraciones y ADR anteriores?
- [ ] ¿Cabe en dos páginas sin diagramas y se entiende sin leer las otras iteraciones?
