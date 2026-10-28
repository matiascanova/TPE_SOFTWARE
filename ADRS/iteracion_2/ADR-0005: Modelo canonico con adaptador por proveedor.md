# ADR-0005: Modelo canónico de núcleo y detalle versionado con adaptador por proveedor

- **Estado:** Aceptado
- **Drivers de entrada:** Analisis-Inicial-y-Drivers.md e Iteracion-1.md. CU-02, CU-03, CU-04. Otros: R-2, R-3, R-6, QA-8, QA-12, CRN-N1.

---

## 1. Contexto

Cada proveedor de viaje y de correo expone un formato propio que Trotamundos no puede cambiar (R-2, R-3), pero la BD, el outbox y el read model necesitan un único formato de reserva (CU-02, CU-03). Un modelo muy genérico pierde datos que muestra el panel, y uno muy específico obliga a modificar el núcleo con cada medio nuevo (QA-12). Además, cada mensaje debe poder juzgarse como válido o inválido con un criterio objetivo (QA-8), un proveedor defectuoso no debe afectar a los demás (CRN-N1) y el plazo es acotado (R-6). Modelo y adaptador se deciden juntos porque la validez se define sobre los esquemas del modelo.

**Pregunta:** ¿Cómo se representa una reserva para todo el sistema y qué contrato cumple el adaptador que traduce y valida lo que envía cada proveedor?

---

## 2. Decisión

**Se elige A1: toda reserva tiene un núcleo común obligatorio y un detalle por tipo con esquema versionado, y cada proveedor y medio tiene un adaptador de tres operaciones (*Describir*, *Traducir*, *Consultar*) que devuelve *Válido* con la reserva canónica o *Inválido* con un motivo codificado, registrado por configuración en los workers.**

- Un mensaje es válido si respeta el contrato del proveedor, se traduce a un núcleo completo con detalle conforme a su tipo y versión, y cumple las invariantes de dominio (identificador externo no vacío, fin no anterior al inicio, estado reconocido); si no, es inválido con motivo de estructura, campo obligatorio, invariante o tipo no soportado.
- El adaptador devuelve datos y no escribe en la BD, no publica ni reintenta; un error de red, una espera agotada o un error del proveedor es una falla del proveedor, no un inválido.
- Dentro de una versión solo se agregan campos opcionales, y lo que el modelo no representa se conserva en un bloque de extensión que ningún consumidor usa.

### Alternativas descartadas

| Alternativa | Motivo |
|---|---|
| A2. Modelo plano único (superconjunto de campos) | Cada medio nuevo agrega campos al núcleo (QA-12) y no se puede validar qué campos corresponden a cada tipo (QA-8). |
| A3. Sin modelo canónico (un modelo por proveedor, normalización en el núcleo con ramas) | Outbox, read model y panel tendrían que conocer a cada proveedor (CU-02, CU-03) y no habría criterio común de validez (QA-8). |
| A4. Estándar sectorial (OpenTravel, NDC) como modelo interno | Ata el núcleo a un segmento y exige dominar una especificación extensa (R-6). Queda como fuente de los mapeos de cada adaptador. |
| A5. Adaptadores como plug-ins con descubrimiento dinámico | Suma carga en ejecución que el prototipo no necesita, porque los adaptadores se conocen al desplegar (R-6). Pasar a ella no cambia la interfaz de A1. |

---

## 3. Consecuencias

**Positivas**

- Un único formato de reserva en la BD, del que parten outbox, read model y panel sin conocer al proveedor (CU-02, CU-03).
- Un medio nuevo agrega un adaptador y un esquema, sin modificar el núcleo ni lo existente (QA-12).
- Criterio de válido e inválido automatizable y con motivo, que permite medir el 99% de QA-8 con proveedores simulados.
- Un proveedor defectuoso queda acotado a su adaptador (CRN-N1).

**Negativas y riesgos aceptados**

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| MC-1 | Se pierde o deforma información al normalizar | Panel incorrecto (CU-02) y medida de QA-8 | Bloque de extensión y pruebas de cada mapeo con mensajes de muestra |
| MC-2 | Los esquemas por tipo y versión se desalinean de los adaptadores | Costo de mantenimiento (QA-12, R-6) | Pruebas de contrato por adaptador y una sola fuente de esquemas compartida con el Panel API |
| MC-3 | El núcleo resulta insuficiente para un medio nuevo (un tren con muchas escalas) | Obliga a modificar el núcleo (QA-12) | Núcleo reducido a lo común; un cambio incompatible crea una versión que convive con la anterior |
| MC-4 | JSONB no impone el esquema y un detalle inválido entra por el alta manual (CU-04) | Consistencia de datos y QA-8 | El Panel API usa la misma validación y los campos obligatorios llevan restricciones en la BD |
| MC-5 | Un criterio demasiado estricto rechaza desvíos menores del proveedor | Baja el porcentaje de válidos procesados (QA-8) | Lector tolerante con campos desconocidos y revisión periódica de motivos por proveedor |
