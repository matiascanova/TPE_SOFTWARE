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

1. **Todo se completa.** Solo son opcionales «ADR relacionados», las reglas y la línea Incorpora/Difiere. Lo opcional que no aplica se omite entero.
2. **Una decisión por ADR.** Un paquete cuyas partes no tienen sentido por separado (por ejemplo, workers con outbox) es una sola decisión y sus alternativas se comparan como paquetes completos.
3. **Máximo 10 drivers que no sean CU** entre el encabezado y la sección 1. Los CU no tienen límite.
4. **Máximo 5 consecuencias negativas.** Si hay más, se agrupan por causa común.
5. **Máximo 5 alternativas en total, elegida incluida, y todas realistas.** No se listan alternativas sin sentido para el problema ni las que otro ADR ya descartó y deben ser distintas entre si, no sirve si son muy similares. Cada descartada lleva un motivo concreto atado a un driver. No se aceptan «es peor» ni «no conviene».
6. **Sin pesos.** No se escriben puntajes, ponderaciones ni rankings. Alta/Media/Baja solo si aclara algo, nunca por criterio. Los valores medibles del driver («en 5 minutos») sí se escriben.
7. **Corto, concreto y sin repetir.** Una idea por frase, en afirmativo. No se explican conceptos conocidos: se nombran y, si hace falta, se enlaza una referencia. Cada dato vive en un solo ADR y los demás lo enlazan. El ADR cabe en una página y se entiende sin leer los demás.

---

## Plantilla

# ADR-<NNNN>: <Título corto que nombre la decisión, no el tema>

- **Estado:** <Propuesto | Aceptado | Rechazado | Reemplazado por ADR-NNNN>
- **Drivers de entrada:** <Enlace al documento de drivers. CU sin límite y hasta 10 más, solo los que condicionan esta decisión>

---

## 1. Contexto

<Un párrafo: qué se necesita decidir, por qué ahora y qué drivers del encabezado condicionan la decisión (citados por ID) y por qué se oponen.>

**Pregunta:** <¿...?>

> Qué va: el problema, no la solución. Hay una sola pregunta. No hay lista de tensiones ni tabla de criterios: los drivers se citan en el párrafo y el motivo de cada alternativa dice cuál incumple.

---

## 2. Decisión

**Se elige <ID>: <enunciado completo y autosuficiente, en una o dos frases>.**

- <Regla: una frase sobre qué se debe cumplir al implementarla.>

**Incorpora:** <Solo en híbridos: tácticas tomadas de alternativas más pesadas.> **Difiere:** <Solo en híbridos: qué se posterga a propósito como evolución prevista.>

### Alternativas descartadas

| Alternativa | Motivo |
|---|---|
| <Nombre> | <Qué driver incumple o qué costo no compensa. Si queda como evolución, decir por qué pasar a ella no exige una reescritura.> |
| <Nombre> | <...> |

> Qué va:Las reglas son hasta 3, de una frase, y solo si no están ya definidas en otro ADR. Sin restricciones propias, solo el enunciado. La tabla lista solo las descartadas, sin excepción. Incorpora/Difiere se usan solo en híbridos y listan únicamente lo que distingue la elección de las descartadas.

---

## 3. Consecuencias

**Positivas**

- <Beneficio concreto (driver que mejora).>

**Negativas y riesgos aceptados (máximo 5)**

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| <XX-1> | <Qué puede salir mal> | <Qué driver afecta> | <Qué se hace o «se acepta»> |

> Qué va: una línea por celda. El ID lleva un prefijo propio del ADR (por ejemplo W-1, BD-1) para referenciarlo desde otros documentos. Las cuatro celdas se completan siempre. La ultima columna es un primer acercamiento a la idea, esta puede modificarse si por una idea mejor a futuro

---

## Lista de control

- [ ] ¿El título nombra una decisión y no un tema?
- [ ] ¿La pregunta tiene su decisión en la sección 2?
- [ ] ¿Hay como máximo 10 drivers que no son CU?
- [ ] ¿El contexto cita los drivers que condicionan la decisión?
- [ ] ¿Hay 5 alternativas o menos, todas realistas y no descartadas en otro ADR?
- [ ] ¿Cada descartada tiene motivo atado a un driver?
- [ ] ¿Sin pesos ni puntajes?
- [ ] ¿Las reglas, si hay, son 3 o menos, de una frase y no repetidas de otro ADR?
- [ ] ¿Si es híbrido, indica qué incorpora y qué difiere?
- [ ] ¿Las consecuencias negativas son 5 o menos, con las cuatro celdas completas?
- [ ] ¿Cabe en una página, se entiende sin leer los otros y no repite datos de ellos?
