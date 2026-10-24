# ADR-0011: Filtrado de remitentes y extracción de reservas desde el correo

- **Estado:** Propuesto
- **Decisores:** Equipo de diseño (grupo TPE)
- **Drivers de entrada:** Análisis inicial y drivers, Iteración 1 e Iteración 2. CU-03 y CU-08. Otros drivers: QA-8, QA-9, QA-12, R-4, R-6, CRN-2 y CRN-N5.
- **ADR relacionados:** ADR-0005 (motor de la BD), ADR-0006 (modelo canónico y adaptador), ADR-0007 (contratos de mensajes y semántica de entrega), ADR-0008 (resiliencia de la ingesta por proveedor), ADR-0010 (credenciales de correo y revocación de acceso). ADR-0012 (sondeo de correo), de la Iteración 3, depende de este.

---

## 1. Contexto y problema

El correo es el diferencial del producto (CU-03): el sistema lee la casilla del usuario y convierte los correos de confirmación, cambio y cancelación en reservas. El ADR-0006 definió que el adaptador de correo cumple el mismo contrato que los de proveedores de viaje (devolver *Válido* con una reserva canónica o *Inválido* con un motivo), pero dejó fuera el pipeline que lee el texto del correo. El ADR-0007 fijó que el trabajo de sondeo solo identifica la cuenta y que el log nunca lleva contenido de correo. El ADR-0010 resolvió cómo se guardan los tokens y cómo se corta la lectura al revocar. Falta decidir qué correos se procesan, qué se conserva de ellos y cómo se obtiene la reserva del texto. Esta decisión va antes que el sondeo (ADR-0012), que necesita saber cuánto cuesta procesar cada correo para dimensionarse.

QA-9 pide extraer correctamente la reserva del 95%\* de los correos de remitentes en la *whitelist* (lista de remitentes autorizados), dentro de los formatos soportados y en menos de 15 segundos.

Este documento responde dos preguntas:

1. **¿Qué correos se procesan y qué se conserva de ellos?**
2. **¿Cómo se convierte un correo de la *whitelist* en una reserva canónica?**

Se deciden juntas porque el filtro define qué llega al extractor, y la lista de formatos soportados es la misma que define la *whitelist*.

**Tensiones:**

- **Leer lo mínimo frente a poder reprocesar (R-4, QA-9).** Los trabajos de proveedores se reprocesan desde el log (ADR-0007), pero el contenido del correo no puede estar en el log. Conservarlo en otro lado permite reprocesar, pero retiene datos personales de la casilla del usuario.
- **Flexibilidad frente a medibilidad (QA-9, R-6).** Un extractor que entiende cualquier formato cubre más remitentes, pero es difícil demostrar el 95%\* de forma repetible. Uno con formatos explícitos cubre menos y exige trabajo por cada remitente.
- **Un correo no es una notificación (QA-8).** Un proveedor de viaje publica un contrato y un correo es texto libre. El criterio de válido del ADR-0006 hay que interpretarlo para este caso, y distinguir un correo con un formato que todavía no se soporta de uno mal formado.
- **Una reserva, dos vías (CRN-N5, QA-12).** La misma reserva puede llegar por la notificación del proveedor y por un correo. Si las dos vías no producen la misma clave (proveedor e identificador externo), la reserva se duplica. Además, las reglas de extracción son otro contrato que mantener alineado con los esquemas y los adaptadores.

**Fuera de alcance:**

- Cómo se detecta el correo nuevo, la marca de sincronización de la casilla, las notificaciones push del proveedor de correo, la prioridad de cuentas y la capacidad (ADR-0012).
- Credenciales y revocación de acceso (ADR-0010).
- Contenido de la *whitelist* y plantillas campo por campo de cada remitente.
- Filtros personalizados por usuario. Queda como evolución de CU-08.
- Adjuntos e imágenes de los correos. Si se necesitan, se agregan como formatos nuevos.
- Almacenamiento de correos o de sus textos.

---

## 2. Criterios de decisión

| Criterio | Qué mide en esta decisión | Se usa en |
|---|---|---|
| QA-7 | Que leer correos que no son de viajes no consuma capacidad que el correo de viajes próximos necesita (ADR-0012). | A |
| R-3 | Que la extracción no dependa de lo que cada remitente incluya en el correo, que no se controla. | B |
| QA-9 | Que el 95%\* de los correos de la *whitelist*, dentro de los formatos soportados, se extraiga correctamente en menos de 15 s, y que el porcentaje se pueda medir. | A / B |
| QA-8 | Que lo que no se puede procesar se identifique y se registre con motivo, sin frenar el resto. | A / B |
| QA-12 | Que un remitente, un formato o un tipo de reserva nuevo se agregue sin modificar el núcleo ni lo existente, con un esfuerzo máximo de 2 días-persona\*. | A / B |
| R-4 | Que se lea y se conserve del correo solo lo necesario. | A / B |
| R-6 | Esfuerzo de implementación para el grupo y piezas nuevas que operar. | A / B |
| CRN-2 | Que use tecnologías que el grupo domina. | B |
| CRN-N5 | Que la *whitelist*, los extractores, los esquemas y los adaptadores no se desalineen, y que una reserva por correo y por proveedor converja en la misma clave. | A / B |

---

## 3. Decisión A: filtrado de remitentes y contenido que se conserva

### 3.1 Alternativas consideradas

| ID | Alternativa | Resumen |
|---|---|---|
| A1 | **Whitelist global por configuración, aplicada antes de leer el cuerpo, sin conservar el correo** | Solo se descarga el cuerpo de los correos de remitentes de la lista. El correo se procesa en memoria y se descarta. |
| A2 | Leer todo y filtrar por contenido | Se descarga cada correo nuevo y se decide por su contenido si trae una reserva. |
| A3 | Whitelist por usuario | Cada usuario elige de qué remitentes se leen reservas. |
| A4 | Conservar el correo | El texto de los correos de la *whitelist* se guarda para poder reprocesarlo. |

### 3.2 Decisión

**Se elige A1: una *whitelist* global por configuración decide, por remitente y antes de descargar el cuerpo, qué correos se procesan, y el correo no se conserva después de procesarlo.**

**Descripción de la decisión.** Cada entrada de la *whitelist* nombra un remitente (una dirección o un dominio), el proveedor canónico al que corresponde y los formatos para los que existe extractor (Decisión B de este ADR). Un remitente solo entra a la lista si tiene al menos un extractor, así que la lista y los formatos soportados son lo mismo. Aplica las tácticas de seguridad *Limit Exposure* y *Limit Access*, y la de integrabilidad *Configuration-Time Binding* ya usada para los adaptadores (ADR-0006).

- **Lista global por configuración.** La lista vive en la configuración, junto al registro de adaptadores, y se versiona con el código. Su entrada usa el mismo identificador de proveedor que el adaptador de ese proveedor de viaje (por ejemplo `aerolinea-x`), así una reserva por correo y por notificación comparten la clave proveedor e identificador externo (ADR-0005).
- **Filtro antes del cuerpo.** La decisión se toma por remitente. Si la API del proveedor de correo permite filtrar por remitente al listar, se usa. Si no, se obtiene solo el encabezado. El cuerpo de un correo que no está en la lista no se descarga.
- **Sin conservar el correo.** El correo se procesa en memoria y se descarta. Ni el cuerpo ni el asunto quedan en una tabla, en el log, en los apartados ni en los registros de la aplicación.
- **Registro mínimo del resultado.** Cada correo de la lista que no se puede extraer deja una fila sin cuerpo en el registro del ADR-0008: id del correo en el proveedor, cuenta, remitente, motivo y hora. Los correos extraídos se cuentan por remitente, sin una fila por correo.
- **Reproceso releyendo la casilla.** Al corregir un extractor, los correos rechazados se vuelven a leer por su id mientras la cuenta siga activa (ADR-0010).

### 3.4 Por qué no las otras alternativas

- **A2. Leer todo y filtrar por contenido.** Descarga el cuerpo de correos personales que no tienen que ver con viajes, en 15 M de cuentas, lo que va contra la mínima exposición (R-4) y suma costo de lectura que el correo de viajes próximos no puede pagar (QA-7, ADR-0012). Tampoco define qué formatos están soportados, así que QA-9 no tendría dominio medible.
- **A3. Whitelist por usuario.** Un remitente sin extractor entraría a la lista y produciría rechazos que no son errores del sistema, lo que distorsiona QA-9. Cada remitente nuevo del usuario exigiría construir un extractor en el momento (QA-12, R-6). Queda como evolución en una forma acotada: una lista de exclusión por usuario sobre la lista global, que no obliga a cambiar el resto.
- **A4. Conservar el correo.** Retiene el texto de correos con datos personales sin un plazo de borrado definido (R-4) y repite el problema que el ADR-0007 evitó al no poner contenido de correo en el log. Con A1 el reproceso se resuelve releyendo la casilla, así que la retención no es necesaria mientras la cuenta siga activa.

---

## 4. Decisión B: extracción de la reserva desde el correo

### 4.1 Alternativas consideradas

| ID | Alternativa | Resumen |
|---|---|---|
| B1 | **Extractores declarativos por remitente y formato** | Cada formato tiene una plantilla que indica dónde está cada campo del correo. El extractor aplica la plantilla y devuelve la reserva canónica. |
| B2 | Datos estructurados incluidos por el remitente | Se leen los datos de reserva que algunos remitentes incrustan en el correo, sin interpretar el texto. |
| B3 | Extracción con un modelo de lenguaje | Un modelo lee el texto del correo y devuelve los campos de la reserva. |
| B4 | Cascada de las tres | Primero los datos estructurados, después las plantillas y por último el modelo. |

### 4.2 Decisión

**Se elige B1: cada formato de cada remitente tiene un extractor declarativo que se registra por configuración, y el resultado pasa por el mismo contrato y criterio de validez del adaptador (ADR-0006).**

**Descripción de la decisión.** Un **formato** es un tipo de correo de un remitente (por ejemplo, la confirmación de un vuelo de `aerolinea-x`) en una versión. Su plantilla dice dónde están el código de reserva, las fechas, el estado y los campos del detalle del tipo. Es una extensión del adaptador de correo: el adaptador de cada proveedor de correo entrega el correo normalizado, y el extractor del remitente lo convierte en reserva.

- **Un extractor por formato.** Cada formato (confirmación, cambio o cancelación de un tipo de reserva) se registra por configuración con su plantilla y su versión. Un formato nuevo, o un tipo nuevo como el tren, se agrega con una plantilla y una entrada en la *whitelist*, sin cambiar el núcleo (QA-12).
- **Correo normalizado.** El adaptador de cada proveedor de correo (`gmail/correo`, `outlook/correo`) entrega remitente, fecha, asunto y cuerpo en texto. Los extractores no dependen del proveedor de correo.
- **Mismo criterio de validez.** El resultado es *Válido*, con la reserva canónica y una hora del hecho (la fecha de recepción del correo), o *Inválido*, con un motivo. Se aplican igual la validación del detalle por esquema del tipo y las invariantes del ADR-0006. El identificador externo es el código de reserva extraído: si falta, el correo es inválido por campo obligatorio. Se agrega el motivo «formato no soportado» para un correo de un remitente de la lista sin extractor que lo reconozca.
- **Medida con un corpus.** Cada formato se entrega con correos de muestra y la reserva esperada. QA-9 se mide como la proporción de extracciones correctas sobre los formatos soportados. Los rechazos por formato no soportado no entran en esa proporción, pero quedan registrados.
- **Tiempo medido en el extractor.** Los 15 s se miden desde que el correo normalizado llega al extractor hasta que se devuelve el resultado. La lectura del proveedor de correo no se controla (R-3) y se mide aparte (ADR-0012).

### 4.3 Cómo funciona

`aerolinea-x` envía a un usuario la confirmación de un vuelo. La cuenta de correo del usuario es la 789 y el trabajo de sondeo `correo:789` ya está en el log (ADR-0007).

1. El worker toma el trabajo, verifica que la cuenta esté activa (ADR-0010) y obtiene los correos nuevos de la casilla (ADR-0012).
2. El filtro encuentra uno cuyo remitente figura en la *whitelist* y descarga su cuerpo. Los demás se ignoran sin descargarlos.
3. El adaptador de correo normaliza el correo y el extractor de `aerolinea-x` (vuelo, confirmación) aplica la plantilla. Devuelve una reserva canónica con proveedor `aerolinea-x`, identificador externo `ABC123`, tipo vuelo y estado confirmada.
4. El esquema del tipo vuelo y las invariantes validan la reserva. El worker compara con lo guardado y, si hay diferencia, guarda el cambio y el evento en una sola transacción (ADR-0007). Si la aerolínea ya había notificado la misma reserva por su API, la clave coincide y no hay diferencia.
5. El correo se descarta de la memoria.

| Qué falla | Qué pasa | Resultado |
|---|---|---|
| El remitente no está en la *whitelist*. | No se descarga el cuerpo. | Ninguna lectura de contenido. |
| El remitente está en la lista pero ningún extractor reconoce el formato. | Inválido por «formato no soportado». Queda una fila sin cuerpo en el registro. | No cambia la BD. Se ve qué formato conviene agregar. |
| Falta el código de reserva. | Inválido por campo obligatorio. | No cambia la BD. |
| El remitente cambia su plantilla y los campos ya no se encuentran. | Los correos de ese remitente pasan a inválidos. | Suben los rechazos de un solo remitente y se corrige su extractor sin tocar el resto. |
| El worker se cae después de extraer. | Se reentrega el trabajo. La extracción da la misma reserva y la comparación no encuentra diferencia. | Sin cambio ni evento duplicado. |
| La cuenta se revoca mientras se procesa. | Se aplica el ADR-0010. | Ninguna lectura posterior. |

### 4.4 Por qué no las otras alternativas

- **B2. Datos estructurados.** No todos los remitentes los incluyen y no se controla que lo hagan (R-3), así que no cubre toda la *whitelist* y QA-9 quedaría fuera de alcance para el resto. Queda como el primer paso de una cascada (B4).
- **B3. Modelo de lenguaje.** El mismo correo puede producir resultados distintos entre una lectura y otra, y la idempotencia del ADR-0007 depende de que un correo repetido dé la misma reserva: de lo contrario la comparación con lo guardado registraría cambios que no existen. También dificulta demostrar el 95%\* de forma repetible (QA-9). Usarlo sacaría contenido del correo del sistema o sumaría un componente para alojar el modelo (R-4, R-6). Su ventaja, cubrir formatos no previstos, no se necesita porque QA-9 se limita a los formatos soportados.
- **B4. Cascada.** Reúne los costos de las tres alternativas, y los casos que B2 y B3 cubrirían quedan fuera de los formatos soportados de QA-9 (R-6). Pasar a B4 después no exige reescribir: el extractor devuelve el mismo resultado del ADR-0006 y los pasos nuevos se agregan antes o después de la plantilla.

---

## 5. Consecuencias

### Positivas

- El cuerpo de los correos fuera de la lista nunca se descarga y ningún correo se conserva, lo que limita la exposición de datos personales (R-4).
- El 95%\* de QA-9 se mide de forma repetible con un corpus de muestra, y lo que queda fuera de los formatos soportados se registra con su motivo (QA-9, QA-8).
- Un remitente, un formato o un tipo de reserva nuevo se agrega con una plantilla y una entrada de configuración, sin tocar el núcleo (QA-12).
- La extracción es determinista, así que la idempotencia del ADR-0007 sigue valiendo y un correo repetido no produce eventos de más (QA-8).
- Una reserva por correo y por notificación comparte la misma clave, porque la *whitelist* usa el identificador de proveedor de los adaptadores (CRN-N5).
- No suma tecnologías ni desplegables nuevos y se implementa con lo que el grupo ya usa (R-6, CRN-2).

### Negativas y riesgos aceptados

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| EX-1 | Un remitente cambia su plantilla y el extractor falla, o extrae mal sin que la validación lo detecte. | Baja el porcentaje de extracciones correctas (QA-9) y puede entrar una reserva con datos errados (CRN-N5). | Validación por esquema e invariantes. Alerta por salto de rechazos por remitente. Pruebas de contrato con el corpus. Versión del formato. |
| EX-2 | Cada remitente y formato exige una plantilla y mantenimiento propios. | Cobertura limitada a los remitentes soportados y esfuerzo por cada uno (QA-12, R-6). | Plantillas declarativas. Remitentes priorizados por volumen de reservas. Evolución a cascada (B4). |
| EX-3 | Al reprocesar un correo rechazado, puede aplicarse un estado más viejo que el guardado. | Una reserva puede quedar con un estado desactualizado (CRN-N5). | El reproceso solo toma correos rechazados, en orden de fecha dentro de la cuenta. Se acepta como el riesgo MS-6 del ADR-0007. |
| EX-4 | No se conserva el correo. | No se puede auditar ni reprocesar un correo que el usuario borró o cuya cuenta se revocó (R-4, QA-9). | Se acepta a cambio de no retener datos personales. Para los correos rechazados, la fila del registro permite ubicar el correo en el proveedor. El usuario puede cargar la reserva manualmente (CU-04). |
| EX-5 | Se agrega un motivo («formato no soportado») y una fila del registro por correo, no por mensaje del log. | Cambia el vocabulario cerrado del ADR-0006 y el uso del registro del ADR-0008 (QA-8). | Actualizar el vocabulario de motivos y el registro al aceptar este ADR. El correo no entra al stream de apartados porque el cuerpo no se conserva (R-4). |
