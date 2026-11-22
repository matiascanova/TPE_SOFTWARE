# ADR-0017: Modelo de lenguaje limitado a un catálogo cerrado de herramientas, con confirmación previa a toda escritura

- **Estado:** Propuesto
- **Drivers de entrada:** [Análisis inicial y drivers](Analisis-Inicial-y-Drivers.md) (CU-10, CU-04, CU-09; QA-14, QA-13, QA-4, R-4, R-6, CRN-2)

---

## 1. Contexto

El usuario escribe en lenguaje libre (reprogramar, consultar el estado, un problema menor) y entenderlo exige interpretación (CU-10). Pero lo que el asistente puede hacer debe ser acotado y verificable: ninguna escritura sin confirmación explícita (QA-14). El ADR-0009 descartó el modelo de lenguaje para extraer reservas porque su resultado no es reproducible, y aquí también puede equivocarse o ser manipulado por el texto de una reserva extraída del correo. Además el itinerario es dato personal (R-4), el modelo puede tardar o no responder (QA-13, QA-4) y el plazo es acotado (R-6, CRN-2).

**Pregunta:** ¿Cómo interpreta el asistente al usuario sin que un error del modelo cambie datos o derive un caso por su cuenta?

---

## 2. Decisión

**Se elige A1: un modelo de lenguaje por API solo decide cuál de un catálogo cerrado de cinco herramientas usa y con qué parámetros; el código del asistente valida y ejecuta, toda escritura se muestra como acción pendiente y exige la confirmación del usuario, y el escalamiento a la agencia lo deciden reglas fijas.**

- **Catálogo y confirmación:** consultar viajes, consultar viaje, modificar reserva, desvincular reserva y derivar a la agencia; las tres últimas escriben. La acción pendiente se arma con valores validados según el ADR-0005, se muestra tal cual, vence a los 10 min† y se ejecuta una sola vez por `idAccion`.
- **Escalamiento:** se deriva si la intención no está en el catálogo, si se pide cambiar una reserva de proveedor (el Panel API la rechaza, ADR-0013, y Trotamundos no escribe hacia proveedores, R-2), si el usuario lo pide o si el modelo no logra interpretar dos veces seguidas†. Una falla de infraestructura no se deriva.
- **Datos mínimos:** al modelo solo se envía el viaje consultado y los últimos mensajes†, sin personas designadas ni identificadores del usuario.

### Alternativas descartadas

| Alternativa | Motivo |
|---|---|
| A2. Árbol de intenciones y menús, sin modelo | Es reproducible y sin dependencia externa, pero no entiende consultas libres («¿mi vuelo sigue en hora?»), que es lo que CU-10 pide. Queda como modo degradado cuando el modelo no responde. |
| A3. NLU clásico entrenado | Evita enviar datos a un tercero (R-4), pero exige corpus de entrenamiento, reentrenamiento y operar otro runtime (R-6). |
| A4. Agente con acceso libre a la API | El modelo arma y ejecuta los pedidos: un error o una instrucción inyectada en el texto de una reserva puede escribir o derivar sin confirmación (QA-14). |

---

## 3. Consecuencias

**Positivas**

- Entiende texto libre y el error del modelo se limita a elegir mal una herramienta: no puede escribir ni derivar sin confirmación (QA-14, CU-10).
- Los parámetros pasan por la validación de las reservas manuales (ADR-0005), y las pruebas usan un modelo simulado con respuestas fijas (QA-14).
- Sin modelo, el asistente sigue con menú fijo y consulta de estado (QA-4).
- Las reglas de escalamiento se prueban y auditan, porque no dependen del criterio del modelo (CU-09).

**Negativas y riesgos aceptados (máximo 5)**

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| LM-1 | El modelo elige mal la herramienta o los parámetros | Propuesta equivocada; si el usuario confirma sin leer, se guarda un dato malo (QA-14) | Resumen con los valores finales, validación del ADR-0005 y pruebas con frases de muestra |
| LM-2 | El texto de una reserva (extraída del correo, QA-9) o del usuario da instrucciones al modelo | Propuestas manipuladas (QA-14) | Resultados de herramientas como datos, catálogo cerrado y confirmación con valores armados por el código |
| LM-3 | Los datos del viaje salen a un modelo externo | Exposición de datos personales (R-4) | Contexto mínimo; condiciones de retención del proveedor: PENDIENTE |
| LM-4 | La llamada al modelo es lenta o falla | La primera respuesta supera los 5 s (QA-13) o el chat no responde (QA-4) | Espera acotada, un reintento y menú fijo |
| LM-5 | Costo y carga por mensaje con 2 M de usuarios semanales | Presión sobre QA-6 y el presupuesto (R-6) | Límite de mensajes por usuario y contexto acotado |
