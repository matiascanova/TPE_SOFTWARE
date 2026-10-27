# ADR-0011: Lectura acotada a remitentes conocidos y extracción declarativa en memoria

- **Estado:** Propuesto
- **Drivers de entrada:** Analisis-Inicial-y-Drivers.md e Iteracion-2.md. CU-03. Otros: QA-9, QA-8, QA-7, QA-12, R-4, R-6.

---

## 1. Contexto

Una casilla contiene sobre todo correos personales que no son de viaje, y leerlos expone datos que Trotamundos no necesita (R-4). La reserva debe extraerse del texto con un resultado verificable (QA-9), y lo que no se puede extraer debe registrarse como inválido con motivo (QA-8). Descargar casillas completas suma volumen contra los 15 minutos de QA-7, y el plazo es acotado (R-6). El adaptador de correo cumple el contrato del ADR-0006. Filtro, conservación y extracción se deciden juntos porque filtro y extractores se identifican por el mismo remitente, y el reproceso depende de qué se filtra y de qué se conserva.

**Pregunta:** ¿Qué correos lee Trotamundos de una casilla, qué conserva y cómo extrae la reserva?

---

## 2. Decisión

**Se elige A1: una whitelist global de remitentes por configuración, aplicada antes de descargar el cuerpo; el correo se procesa en memoria y no se conserva; y la reserva se obtiene con un extractor declarativo por remitente y formato.**

- **(a) Filtro:** la whitelist usa el mismo identificador de proveedor que los adaptadores (ADR-0006). El cuerpo de un correo de un remitente fuera de ella no se descarga.
- **(b) Sin conservación:** el cuerpo no se escribe en la base, el log ni los apartados. El reproceso se hace releyendo la casilla.
- **(c) Extracción:** un correo de un remitente admitido cuyo formato no tiene extractor es *Inválido* con motivo «formato no soportado», que se agrega a los motivos del ADR-0006.

### Alternativas descartadas

| Alternativa | Motivo |
|---|---|
| A2. Leer todo el correo y filtrar por contenido | Descarga y expone correos personales (R-4) y multiplica el volumen leído (QA-7). |
| A3. Whitelist por usuario | Obliga a mantener una lista por cuenta sin agregar remitentes de viaje (R-6). |
| A4. Conservar el correo para reprocesar | Retiene datos personales sin necesidad (R-4). |
| A5. Extracción con un modelo de lenguaje | El resultado no es reproducible ni verificable con un criterio objetivo (QA-8, QA-9) y suma una pieza que operar (R-6). |
| A6. Solo datos estructurados incluidos en el correo | No todos los remitentes los incluyen (QA-9). |

---

## 3. Consecuencias

**Positivas**

- Solo se descargan correos de remitentes de viaje y ninguno queda guardado (R-4).
- Cada extractor se prueba con correos de muestra, su resultado es reproducible y un fallo queda registrado con motivo (QA-8, QA-9).
- Un remitente nuevo agrega una entrada en la whitelist y un extractor, sin modificar el núcleo (QA-12).
- El volumen descargado por cuenta queda acotado a los remitentes admitidos (QA-7).

**Negativas y riesgos aceptados**

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| EX-1 | Un remitente cambia el formato de sus correos | Inválidos «formato no soportado» hasta actualizar el extractor (QA-8, QA-9) | Alerta por salto de ese motivo por remitente y pruebas con correos de muestra |
| EX-2 | Una reserva llega de un remitente fuera de la whitelist | No aparece en el panel (CU-03, QA-9) | Revisión periódica de la whitelist y alta manual (CU-04) |
| EX-3 | El reproceso depende de que el correo siga en la casilla | Si el usuario lo borró, la reserva no se recupera (CU-03) | Se acepta; el usuario puede cargarla a mano (CU-04) |
| EX-4 | La cantidad de extractores crece con los remitentes y formatos | Costo de mantenimiento (R-6) | Formato declarativo común, pruebas por extractor y prioridad por volumen de remitente |
| EX-5 | La whitelist y los adaptadores se desalinean en el identificador de proveedor | Correos admitidos sin adaptador, o al revés (QA-8) | Una sola fuente de identificadores compartida por la whitelist y los adaptadores |
