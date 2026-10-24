# Plantilla de ADR (Architecture Decision Record)

Esta plantilla sirve para redactar ADR nuevos y para corregir ADR existentes. Las líneas que empiezan con `>` son instrucciones: explican qué va en cada ítem y se borran al redactar el ADR final. Lo que está entre `<...>` es un campo a completar.

---

## Instrucciones para la IA

### Cómo corregir un ADR existente

1. Leer el ADR completo y esta plantilla.
2. Conservar el fondo: decisiones, alternativas, motivos, riesgos y cifras del ADR original. No cambiar lo que se decidió.
3. Reubicar cada pieza en el ítem que le corresponde (por ejemplo, los motivos que estén dentro de la decisión pasan a 3.4).
4. Aplicar las reglas de abajo. Si cumplirlas obliga a recortar o agrupar contenido, hacerlo sin perder lo esencial y avisarlo al final.
5. Si falta contenido de un ítem obligatorio, dejar `<PENDIENTE: qué falta>`. No inventar información que el ADR no tenga.
6. Entregar el ADR corregido y, aparte, una lista breve de lo que se movió, se recortó o quedó pendiente.

### Reglas obligatorias

1. **Todos los ítems se completan.** Si un ítem opcional no aplica, se omite entero (no se deja vacío). Los ítems obligatorios nunca se omiten.
2. **Una decisión por ADR, o varias si están muy relacionadas.** Se agrupan solo si no tiene sentido decidir una sin la otra. Cada decisión agrupada se llama Decisión A, B, C y repite el bloque completo de la sección 3 (alternativas, decisión, por qué no las otras).
3. **Máximo 10 drivers que no sean casos de uso (CU).** Los CU pueden citarse todos los que hagan falta. Los demás drivers (QA, restricciones, riesgos, etc.) no superan 10 entre el encabezado y los criterios. Si hay más, se eligen los que realmente condicionan esta decisión.
4. **Máximo 5 consecuencias negativas.** Si hay más, se agrupan por causa común en un solo ítem.
5. **Toda alternativa descartada se justifica.** Cada una lleva su propio motivo, concreto y atado a un criterio. No se aceptan frases como «es peor» o «no conviene» sin razón.
6. **No se cuantifica el peso de las decisiones ni de los criterios.** No se escriben puntajes, porcentajes de importancia, ponderaciones ni rankings numéricos. Una etiqueta de prioridad (Alta/Media/Baja) se admite con moderación: solo donde aclara algo que el lector necesita, nunca en cada criterio ni repetida en cada referencia a un driver externo (QA, CU, riesgo). Se puede razonar con pesos internamente, pero no deben quedar registrados. Sí se pueden escribir los valores medibles que pertenecen al driver (por ejemplo, «actualización en 5 minutos») porque son parte del requisito, no un peso.
7. **Se escribe en afirmativo y concreto.** Una idea por frase, sin relleno. Los términos se definen la primera vez que aparecen. Cada ADR se entiende sin leer los demás, pero enlaza los relacionados.
8. **Todo ADR menciona qué queda fuera de alcance**, para evitar solapamientos con otros ADR.

---

## Plantilla

# ADR-<NNNN>: <Título corto que nombre la decisión, no el tema>

- **Estado:** <Propuesto | Aceptado | Rechazado | Reemplazado por ADR-NNNN>
- **Decisores:** <Quiénes toman la decisión>
- **Drivers de entrada:** <Enlace al documento de drivers. CU sin límite y otros drivers hasta 10, solo los que condicionan esta decisión>
- **ADR relacionados:** <ADR de los que depende o que dependen de este. Omitir si no hay>

> Qué va: metadatos para ubicar el ADR. En «Drivers de entrada» va solo lo que de verdad influye en esta decisión, no todos los drivers del proyecto.

---

## 1. Contexto y problema

<Párrafo corto: qué se necesita decidir y por qué ahora. Cómo condiciona o es condicionado por otras decisiones.>

Este documento responde <una pregunta> | estas preguntas:

1. **<Pregunta 1, escrita como pregunta>**
2. **<Pregunta 2, solo si se agrupan decisiones>**

**Tensiones:**

- **<Nombre de la tensión>.** <Qué fuerzas se oponen o qué hace difícil la decisión, y qué driver afecta.>
- **<Nombre de la tensión>.** <...>

**Fuera de alcance:** <Qué NO se decide acá y en qué ADR o iteración se trata.>

> Qué va: el problema, no la solución. Hay una pregunta por decisión. Cada tensión se vincula a un driver y explica por qué la decisión no es obvia. «Fuera de alcance» es obligatorio.

---

## 2. Criterios de decisión

| Criterio | Qué mide en esta decisión |
|---|---|
| <Driver o QA> | <Qué se verifica concretamente en las alternativas> |
| <...> | <...> |

> Qué va: los criterios con los que se comparan las alternativas. Cada criterio sale de un driver de la cabecera (máximo 10 que no sean CU). Sin columna de peso. Si hay decisiones agrupadas, se puede agregar una columna «Se usa en» con las decisiones (A, B).

---

## 3. Decisión <A> | Decisión

> Si el ADR tiene una sola decisión, se usa la sección «3. Decisión» con los subítems 3.1 a 3.4. Si agrupa varias, se repite el bloque completo (3.1 a 3.4) por cada una, como «3. Decisión A» y «4. Decisión B», y las consecuencias pasan a ser la sección siguiente.

### 3.1 Alternativas consideradas

| ID | Alternativa | Resumen |
|---|---|---|
| <A1> | <Nombre> | <Qué es, en una o dos frases> |
| <A2> | **<Nombre de la elegida, en negrita>** | <...> |
| <A3> | <Nombre> | <...> |

> Qué va: todas las opciones realistas, incluida la elegida (en negrita). Mínimo 3 cuando sea posible. El resumen describe la opción, no la juzga.

### 3.2 Decisión

**Se elige <ID>: <enunciado en una o dos frases, completo y autosuficiente>.**

**Descripción de la decisión.** <Una o dos frases que expliquen la solución a alguien que no la conoce.>

- **<Nombre de la regla>.** <Qué se debe cumplir al implementarla.>
- **<Nombre de la regla>.** <...>

**Tácticas que incorpora de otras alternativas:** <Solo en decisiones híbridas. Lista corta.>
**Tácticas que difiere:** <Solo en decisiones híbridas. Qué se posterga a propósito y se documenta como evolución prevista.>

> Qué va: la decisión en negrita y su descripción simple. Si la decisión impone restricciones verificables, hasta 5 reglas en viñetas, cada una con nombre y qué se debe cumplir. Sin restricciones, solo la descripción. El motivo detallado va en 3.4; una frase corta de motivo en una regla es válida si sin ella no se entiende.
>
> Las dos líneas de tácticas se usan solo cuando la decisión es un híbrido que toma ideas de alternativas más pesadas. Se listan únicamente las tácticas que distinguen la elección de las descartadas, no las que cualquier alternativa tendría. En decisiones que no son híbridas se omiten.

### 3.3 Cómo funciona (opcional)

<Caso práctico paso a paso con un ejemplo concreto del dominio.>

| Qué falla | Qué pasa | Resultado |
|---|---|---|
| <Falla> | <Comportamiento del diseño> | <Efecto visible> |

> Qué va: solo si la decisión es difícil de imaginar. Un ejemplo corto y una tabla con las fallas relevantes. Si la decisión se entiende sin esto, se omite.

### 3.4 Por qué no las otras alternativas

- **<ID y nombre de la alternativa descartada>.** <Motivo concreto, atado a un criterio de la sección 2. Si la alternativa queda como evolución futura, decirlo.>
- **<ID y nombre>.** <...>

> Qué va: una entrada por cada alternativa descartada, sin excepción. El motivo dice qué criterio incumple o qué costo no compensa. Si una alternativa queda como evolución prevista, se indica y se explica por qué pasar a ella después no exige una reescritura.

---

## 4. Consecuencias

### Positivas

- <Beneficio concreto, indicando el criterio o driver que mejora.>
- <...>

### Negativas y riesgos aceptados (máximo 5)

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| <XX-1> | <Qué puede salir mal> | <Qué driver o criterio afecta y cómo> | <Qué se hace para reducirlo o aceptarlo> |

> Qué va: beneficios y costos reales de la decisión. Como máximo 5 filas de negativas: si hay más, se agrupan por causa común. Cada fila tiene una mitigación o aclara que se acepta. Los ID llevan un prefijo propio del ADR (por ejemplo W-1, BD-1) para poder referenciarlos desde otros documentos. Con decisiones agrupadas, la tabla es única y cubre todas.

---

## Lista de control antes de entregar un ADR

- [ ] ¿El título nombra una decisión y no un tema?
- [ ] ¿Cada pregunta del contexto tiene su decisión en la sección 3?
- [ ] ¿Hay como máximo 10 drivers que no son CU?
- [ ] ¿Cada criterio proviene de un driver de la cabecera?
- [ ] ¿No hay pesos ni puntajes, y las etiquetas Alta/Media/Baja son pocas y aclaran algo?
- [ ] ¿Cada alternativa descartada tiene su motivo?
- [ ] ¿Las reglas de la descripción son 5 o menos?
- [ ] ¿Si la decisión es un híbrido, indica qué incorpora y qué difiere?
- [ ] ¿Las consecuencias negativas son 5 o menos, con mitigación?
- [ ] ¿Se indica qué queda fuera de alcance?
- [ ] ¿Se entiende el ADR sin leer los otros?
