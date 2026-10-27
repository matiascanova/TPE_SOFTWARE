# Plantilla de ADR (Architecture Decision Record)

Sirve para redactar ADR nuevos y corregir existentes. Las líneas con `>` son instrucciones y se borran en el ADR final. Lo que está entre `<...>` es un campo a completar.

---

## Instrucciones para la IA

### Cómo corregir un ADR existente

1. Leer el ADR completo y esta plantilla.
2. Conservar el fondo (decisión, alternativas, motivos, riesgos, cifras) y reubicar cada pieza en su ítem. No cambiar lo decidido.
3. Aplicar las reglas. Si cumplirlas obliga a recortar o agrupar, hacerlo sin perder lo esencial.
4. Si falta contenido obligatorio, dejar `<PENDIENTE: qué falta>`. No inventar.
5. Entregar el ADR corregido y, aparte, una lista breve de lo que se movió, se recortó o quedó pendiente.

### Reglas obligatorias

1. **Todo se completa.** Solo son opcionales «ADR relacionados», las reglas y las tácticas. Lo opcional que no aplica se omite entero.
2. **Una decisión por ADR.** Un paquete cuyas partes no tienen sentido por separado (por ejemplo, workers con outbox) es una sola decisión y sus alternativas se comparan como paquetes completos.
3. **Máximo 10 drivers que no sean CU** entre el encabezado y la sección 1. Los CU no tienen límite.
4. **Máximo 5 consecuencias negativas.** Si hay más, se agrupan por causa común.
5. **Toda alternativa descartada se justifica** con un motivo concreto atado a un driver. No se aceptan «es peor» ni «no conviene».
6. **Sin pesos.** No se escriben puntajes, ponderaciones ni rankings. Alta/Media/Baja solo si aclara algo, nunca por criterio. Los valores medibles del driver («en 5 minutos») sí se escriben.
7. **Corto y concreto.** Una idea por frase, en afirmativo. No se explican conceptos conocidos: se nombran y, si hace falta, se enlaza una referencia. El ADR cabe en una página, se entiende sin leer los demás y enlaza los relacionados.
8. **Todo ADR indica qué queda fuera de alcance.**

---

## Plantilla

# ADR-<NNNN>: <Título corto que nombre la decisión, no el tema>

- **Estado:** <Propuesto | Aceptado | Rechazado | Reemplazado por ADR-NNNN>
- **Decisores:** <Quiénes toman la decisión>
- **Drivers de entrada:** <Enlace al documento de drivers. CU sin límite y hasta 10 más, solo los que condicionan esta decisión>
- **ADR relacionados:** <ADR de los que depende o que dependen de este. Omitir si no hay>

---

## 1. Contexto

<Un párrafo: qué se necesita decidir y por qué ahora.>

**Pregunta:** <¿...?>

**Tensiones:**

- **<Nombre> (<driver>).** <Una línea: qué hace difícil la decisión y qué se verifica en las alternativas.>
- **<Nombre> (<driver>).** <...>

**Fuera de alcance:** <Qué NO se decide acá y en qué ADR o iteración se trata.>

> Qué va: el problema, no la solución. Hay una sola pregunta. Cada tensión sale de un driver del encabezado y su verificación es el criterio con que se comparan las alternativas. No hay tabla de criterios.

---

## 2. Decisión

**Se elige <ID>: <enunciado completo y autosuficiente, en una o dos frases>.**

- <Regla: una frase sobre qué se debe cumplir al implementarla.>
- <...>

**Incorpora:** <Solo en híbridos: tácticas tomadas de alternativas más pesadas.> **Difiere:** <Solo en híbridos: qué se posterga a propósito como evolución prevista.>

### Alternativas consideradas

| ID | Alternativa | Motivo |
|---|---|---|
| <A1> | <Nombre> | <Qué driver incumple o qué costo no compensa. Si queda como evolución, decir por qué pasar a ella no exige una reescritura.> |
| <A2> | **<Nombre de la elegida>** | <Qué drivers cumple.> |
| <A3> | <Nombre> | <...> |

> Qué va: la decisión en negrita y, si impone restricciones verificables, hasta 3 reglas de una frase. Sin restricciones, solo el enunciado. Las alternativas incluyen la elegida (en negrita), mínimo 3 cuando sea posible, y cada descartada tiene su motivo, sin excepción. Incorpora/Difiere se usan solo en híbridos y listan únicamente lo que distingue la elección de las descartadas.

---

## 3. Consecuencias

**Positivas**

- <Beneficio concreto (driver que mejora).>

**Negativas y riesgos aceptados (máximo 5)**

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| <XX-1> | <Qué puede salir mal> | <Qué driver afecta> | <Qué se hace o «se acepta»> |

> Qué va: una línea por celda. El ID lleva un prefijo propio del ADR (por ejemplo W-1, BD-1) para referenciarlo desde otros documentos.

---

## Lista de control

- [ ] ¿El título nombra una decisión y no un tema?
- [ ] ¿La pregunta tiene su decisión en la sección 2?
- [ ] ¿Hay como máximo 10 drivers que no son CU?
- [ ] ¿Cada tensión nombra su driver y qué se verifica?
- [ ] ¿Cada alternativa descartada tiene motivo atado a un driver?
- [ ] ¿Sin pesos ni puntajes?
- [ ] ¿Las reglas, si hay, son 3 o menos y de una frase?
- [ ] ¿Si es híbrido, indica qué incorpora y qué difiere?
- [ ] ¿Las consecuencias negativas son 5 o menos, con mitigación?
- [ ] ¿Indica qué queda fuera de alcance?
- [ ] ¿Cabe en una página y se entiende sin leer los otros?
