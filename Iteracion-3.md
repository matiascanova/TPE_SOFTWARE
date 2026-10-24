# Iteración 3 de ADD: ingesta de correo de Trotamundos

- **Estado:** Borrador para revisión
- **Fecha:** 24/10/2026
- **Equipo:** grupo TPE

---

## 1. Resumen ejecutivo

La Iteración 2 cerró la ingesta de proveedores de viaje y dejó fuera el pipeline de correo (CU-03, QA-7, QA-9, QA-10). Esta iteración lo diseña de punta a punta: cómo se guardan las credenciales y se corta el acceso al revocar, qué correos se leen y cómo se convierten en reservas, y cómo se detecta el correo nuevo con cuánta capacidad (ADR-0010 a 0012).

Las decisiones principales son:

- **Credenciales:** tokens cifrados en una tabla propia de la BD operacional, detrás de un módulo de credenciales, con un estado por cuenta (activa o revocada).
- **Revocación:** cambio de estado confirmado en la BD. Cada worker verifica ese estado justo antes de llamar al proveedor y descarta el trabajo si la cuenta no está activa.
- **Filtro de correos:** *whitelist* global por configuración, aplicada antes de descargar el cuerpo. El correo se procesa en memoria y no se conserva.
- **Extracción:** un extractor declarativo por remitente y formato, que devuelve el mismo resultado *Válido*/*Inválido* del adaptador.
- **Detección y planificación:** notificaciones push como vía principal, sondeo de respaldo siempre activo y marca de sincronización por cuenta. El planificador agrupa las cuentas en dos clases según la cercanía del viaje y emite un solo trabajo por cuenta.

Con esto **CU-03** queda diseñado de punta a punta y **QA-9** tiene un camino de cumplimiento verificable con un proveedor de correo simulado. **QA-10** también lo tiene para las revocaciones hechas desde Trotamundos. Las hechas desde el proveedor de correo se detectan recién en la siguiente llamada (CRN-N7). **QA-7** queda con un presupuesto de tiempo por etapa que falta medir. La iteración no suma desplegables ni tecnologías. Los 15 riesgos aceptados en los ADR se consolidan en **una concern nueva** (sección 5) y el resto se asigna a concerns y drivers existentes.

## 2. Objetivo de la iteración y drivers seleccionados

**Objetivo.** Definir cómo Trotamundos lee el correo de los usuarios, convierte los correos de las agencias y aerolíneas en reservas y corta el acceso al revocarse, de modo que QA-7, QA-9 y QA-10 tengan un diseño verificable con proveedores de correo simulados y el grupo pueda implementarlo en el plazo (R-6).

Los elementos a refinar son el **planificador de sondeo** y los **workers con el adaptador de correo**, que la Iteración 2 dejó sin diseño, y la **BD**, que suma las tablas de credenciales y de estado de sondeo. El refinamiento es por descomposición de responsabilidades.

| Driver | Motivo de la selección |
|---|---|
| CU-03 | Es el diferencial del producto y el caso de uso que esta iteración cierra a nivel de diseño. |
| QA-7, QA-9, QA-10 | Los tres atributos que la Iteración 2 dejó fuera por depender del pipeline de correo: demora de 15 minutos\*, extracción correcta del 95%\* y corte de la lectura al revocar. |
| QA-5, QA-6, QA-8, QA-12 | QA-5: recuperar el correo llegado durante una caída del proveedor. QA-6: el costo de detección debe seguir al correo que llega y no a las 15 M de cuentas. QA-8: la revocación y los formatos no soportados no deben falsear la medida. QA-12: un remitente o formato nuevo se agrega por configuración. |
| CU-08 | La *whitelist* global resuelve la base. Los filtros por usuario quedan como evolución. Se aborda de forma parcial. |
| R-3, R-4 | R-3: los proveedores de correo no avisan las revocaciones, imponen cuotas y vencen las suscripciones. R-4: tokens y contenido de correo son datos sensibles. |
| R-6, CRN-2 | Viabilidad con las tecnologías que el grupo domina (Java, PostgreSQL). |
| CRN-N2, CRN-N3, CRN-N5, CRN-N6 | Concerns que el pipeline toca: tablas nuevas en la base compartida, piezas nuevas a monitorear, extractores por remitente y un log en memoria que no debe retener datos de correo. |

## 3. Conceptos de diseño

Se documentan los conceptos que se desprenden de los ADR-0010 a 0012. Las tácticas de detalle (por ejemplo *Encrypt Data*, *Heartbeat*, *Prioritize Events*) están en cada ADR y no se repiten acá.

| Concepto | Drivers | ADR | Alternativas descartadas |
|---|---|---|---|
| Tokens cifrados en una tabla propia de la BD operacional, detrás de un módulo de credenciales | QA-10, QA-7, R-4, R-6, CRN-2, CRN-N6 | 0010 (A) | Almacén de secretos dedicado, tokens en Redis, no guardar tokens |
| Revocación como cambio de estado confirmado, con verificación antes de cada lectura y descarte del trabajo al consumirlo | QA-10, QA-7, QA-8, R-3 | 0010 (B) | Purgar el log, esperar el vencimiento del token, caché de estado con invalidación |
| *Whitelist* global por configuración antes de leer el cuerpo, sin conservar el correo | QA-9, QA-8, QA-12, R-4, CU-08, CRN-N5 | 0011 (A) | Leer todo y filtrar por contenido, *whitelist* por usuario, conservar el correo |
| Extractores declarativos por remitente y formato, con el contrato del adaptador | QA-9, QA-12, R-6, CRN-2, CRN-N5 | 0011 (B) | Datos estructurados del remitente, modelo de lenguaje, cascada de las tres |
| Notificaciones push como vía principal, sondeo de respaldo y marca de sincronización incremental | QA-7, QA-6, QA-5, R-3, R-6 | 0012 (A) | Solo sondeo, solo push, relectura de una ventana reciente |
| Planificador por clases, con un trabajo por cuenta y emisión limitada por el atraso | QA-7, QA-6, R-3, R-6, CRN-N3, CRN-N6 | 0012 (B) | Sondeo uniforme, streams separados por prioridad, solo escalar workers |

**Tecnologías.** No se suman tecnologías ni desplegables: el pipeline usa PostgreSQL, Redis Streams y los workers ya definidos. Los tokens se cifran con una clave que no está en la BD: en el prototipo vive en la configuración y en producción en el gestor de claves de la plataforma. Un almacén de secretos dedicado queda como evolución, sin reescribir el resto, porque solo el módulo de credenciales conoce el almacenamiento. La tecnología del almacén analítico sigue sin definirse.

**Costo explícito.** El diseño suma piezas propias: el módulo de credenciales, una tarea de limpieza, una tarea de suscripciones, el planificador y un extractor por cada formato de cada remitente. Suma además estado por cuenta en la base compartida (15 M de filas). Ese costo se refleja en las concerns de la sección 5.

## 4. Vista modular y elementos instanciados

### 4.1 Vista modular

La estructura de tres desplegables no cambia. El pipeline de correo reutiliza el receptor, el broker, los workers y el flujo de guardado de la Iteración 2. El flujo de punta a punta de un correo nuevo es:

1. **Detección.** Llega un aviso push del proveedor de correo y el receptor encola `correo:<id de la cuenta>` sin interpretarlo. Si no llega, el planificador encola la cuenta cuando vence su sondeo de respaldo. Los dos producen el mismo trabajo.
2. **Verificación.** El worker consulta el estado de la cuenta en el módulo de credenciales, por clave primaria. Si no está activa, confirma el trabajo sin leer el correo.
3. **Lectura.** El worker pide al proveedor los cambios posteriores a la marca de sincronización de la cuenta. Solo descarga el cuerpo de los correos de remitentes de la *whitelist*.
4. **Extracción.** El adaptador de correo normaliza el correo (remitente, fecha, asunto, cuerpo) y el extractor del remitente y formato aplica su plantilla. El resultado es *Válido* con una reserva canónica o *Inválido* con un motivo, con el mismo contrato que un proveedor de viaje.
5. **Guardado.** Igual que en la Iteración 2: compara con lo guardado y, si hay diferencia, guarda el cambio y el evento en una sola transacción. La clave (proveedor e identificador externo) es la misma que la de la notificación del proveedor de viaje, así que no se duplica.
6. **Cierre.** El worker descarta el correo de la memoria, avanza la marca, recalcula la clase de la cuenta, fija la hora del próximo sondeo y confirma el trabajo.
7. **Revocación.** Desde Trotamundos, el Panel API pasa la cuenta a revocada en una transacción. Una tarea de limpieza cancela la suscripción de avisos, pide al proveedor que revoque el token y borra los tokens. Desde el proveedor, la siguiente llamada recibe acceso denegado y marca la cuenta como revocada.

**Cambios sobre la vista modular inicial.** Donde la vista nombraba una «bóveda» para los tokens, ahora hay un módulo de credenciales sobre una tabla de la BD (ADR-0010). El correo extraído no pasa por el stream de apartados: los rechazos dejan una fila sin cuerpo en el registro del ADR-0008.

### 4.2 Elementos y responsabilidades

| Elemento | Responsabilidades (refinadas en esta iteración) | Comunicación |
|---|---|---|
| **Planificador de sondeo** | Agrupa las cuentas activas en clase 1 (viaje próximo o en curso) y clase 2 (el resto), emite primero las más urgentes y mantiene un solo trabajo pendiente por cuenta. Deja de emitir para un proveedor si su consumo se atrasa, si se limita el uso o si su circuito se abre. Consulta el estado de la cuenta (activa o revocada) en el módulo de credenciales y nunca recibe tokens. | Lee la tabla de estado de sondeo, consulta el estado de la cuenta en el módulo de credenciales y publica en el stream de trabajo de correo. |
| **Tarea de suscripciones** | Corre junto al planificador (ADR-0012). Crea y renueva las suscripciones de avisos push de cada cuenta activa, si el proveedor las ofrece, y no renueva las de cuentas revocadas. La cancelación al revocar la hace la tarea de limpieza. Si falla o vence, la cuenta queda solo con el sondeo y se marca para el monitoreo. | Pide credenciales al módulo de credenciales. Llama al proveedor de correo. |
| **Receptor** | Sin cambios de responsabilidad. Obtiene el id de la cuenta del identificador de la suscripción y encola `correo:<id>` sin interpretar el aviso. | Recibe del proveedor de correo. Publica en el stream de trabajo. |
| **Workers de correo** | Verifican el estado antes de cada llamada, leen los cambios desde la marca, aplican la *whitelist*, invocan al extractor, guardan el cambio y el evento, y avanzan la marca. Renuevan tokens y marcan la cuenta como revocada si el proveedor lo informa, siempre a través de las operaciones del módulo de credenciales. | Toman trabajo del broker. Piden credenciales al módulo. Leen el correo. Escriben en la BD. |
| **Adaptador de correo y extractores** | Un adaptador por proveedor de correo (`gmail/correo`, `outlook/correo`) entrega el correo normalizado. Un extractor por formato y remitente, registrado por configuración con su versión, lo convierte en reserva canónica. Devuelven datos: no escriben, no publican y no reintentan. | Los invocan los workers. |
| ***Whitelist* de remitentes** | Lista global en la configuración, versionada con el código. Cada entrada nombra un remitente, su identificador de proveedor canónico y los formatos con extractor. Un remitente solo entra si tiene al menos un extractor. | La lee el worker antes de descargar el cuerpo. |
| **Módulo de credenciales** | Única parte que conoce el almacenamiento de tokens. Entrega la credencial de una cuenta activa, la renueva, la revoca e informa el estado de una cuenta sin entregar tokens. Los tokens no salen de su tabla: ni al log, ni a los apartados, ni a la copia analítica. | Lo usan los workers (entregar, renovar y marcar como revocada), el Panel API (alta y revocación), la tarea de limpieza (borrar tokens), la tarea de suscripciones (entregar) y el planificador (solo el estado). Solo el módulo accede a la tabla, con un rol de base de datos propio. |
| **Tarea de limpieza** | Tras una revocación, y en este orden: cancela la suscripción de avisos, pide al proveedor que revoque el token (si ofrece el endpoint), con reintentos acotados, y borra los tokens. Es la única pieza que cancela suscripciones al revocar. | Módulo de credenciales y proveedor de correo. |
| **BD (PostgreSQL)** | Dos tablas nuevas: credenciales (proveedor, estado y tokens cifrados) y estado de sondeo (marca, clase, próximo sondeo y trabajo en curso). Usa el registro de apartados de la Iteración 2 para los correos rechazados, sin cuerpo. | Módulo de credenciales, planificador, workers y Panel API. |
| **Panel API** | Da de alta y revoca cuentas de correo (el flujo de conexión queda para la Iteración 4). | Módulo de credenciales. |

**Stream de trabajo de correo.** Contiene el trabajo de sondeo con el sobre, sin contenido de correo ni tokens. La retención pasa de 24 h\* (provisional en la Iteración 2) a 1 h\*, porque un trabajo recortado se regenera desde el estado de la cuenta y la marca conserva lo no procesado.

### 4.3 Parámetros del prototipo

Los valores con \* son propuestas que se ajustan con proveedores de correo simulados que apliquen cuotas.

| Parámetro | Valor |
|---|---|
| Corte tras revocar desde Trotamundos | Menos de 1 minuto\*, con 0 lecturas con inicio posterior al `COMMIT` de la revocación |
| Extracción de un correo | Menos de 15 s\*, medidos desde que el extractor recibe el correo normalizado |
| Ventana de viaje próximo | 7 días\* |
| Intervalo de respaldo | Clase 1: 10 minutos\*. Clase 2: 6 horas\* |
| Umbral de atraso para frenar la emisión | 2 minutos\* |
| Reemplazo de un trabajo que se considera perdido | 30 minutos\* |
| Presupuesto de QA-7 (15 minutos\*) | Hasta 10 minutos\* de espera del próximo sondeo, si el aviso no llegó, y hasta 5 minutos\* de log y procesamiento |

### 4.4 Qué pasa cuando algo falla

| Qué falla | Qué pasa | Resultado |
|---|---|---|
| Había trabajos de una cuenta en el log al revocarla. | Se consumen y se descartan sin llamar al proveedor. El planificador no la vuelve a encolar. | Ninguna lectura posterior. |
| El usuario revoca desde el proveedor. | La siguiente llamada recibe acceso denegado y marca la cuenta como revocada, sin contar como falla del proveedor ni como inválido. | La detección tarda hasta el siguiente sondeo de la cuenta. |
| El aviso se pierde o la suscripción venció. | El sondeo de respaldo encola la cuenta en su vencimiento. | El correo se procesa dentro del presupuesto. La cuenta queda marcada. |
| Un remitente cambia su plantilla. | Sus correos pasan a inválidos. | Suben los rechazos de un solo remitente y se corrige su extractor sin tocar el resto. |
| Cae la API del proveedor de correo o limita el uso. | El circuito se abre y el planificador deja de emitir para ese proveedor. Al recuperarse emite primero la clase 1. | La marca recupera lo llegado durante la caída. No se descartan cuentas. |
| La BD no responde al verificar el estado. | No se llama al proveedor y se pausa el consumo sin apartar el trabajo. | Ante la duda no se lee. |
| El proveedor invalida la marca. | Se vuelve a listar la casilla filtrando por la *whitelist* desde el último sondeo exitoso. | No se pierden correos. El costo extra queda acotado a esa cuenta. |

## 5. Consecuencias negativas y nuevos drivers

Los ADR-0010 a 0012 registran 15 riesgos aceptados: CC-1 a CC-5 (ADR-0010), EX-1 a EX-5 (ADR-0011) y SC-1 a SC-5 (ADR-0012). Para no cerrar la iteración con una lista inmanejable, 3 se consolidan en **una concern nueva** y los 12 restantes se asignan a concerns existentes o a drivers, como indica la sección 5.2. El ID de la concern nueva es provisional hasta incorporarla al catálogo de drivers.

### 5.1 Concern nueva

| ID | Concern | Riesgos de origen | Mitigación ya prevista en los ADR |
|---|---|---|---|
| CRN-N7 | Depender de avisos, cuotas y revocaciones de proveedores de correo que el sistema no controla | CC-1, SC-1, SC-2 | Sondeo de respaldo siempre activo, priorizado por clase. Limitador por proveedor (ADR-0008). Aprovechar las notificaciones del proveedor de correo si las ofrece. Alertas por renovaciones fallidas y cuentas sin aviso. Intervalos ajustables por configuración. Prueba con un proveedor simulado que aplique cuotas. Medir el tiempo de detección de revocaciones. |

### 5.2 Riesgos asignados a concerns existentes o a drivers

| Riesgo | Dónde se resuelve |
|---|---|
| CC-3 (consulta de estado por trabajo) y SC-3 (estado por cuenta en la base compartida) | CRN-N2, Iteración 5: consulta por clave primaria, tabla propia con índice mínimo, actualizaciones por lote y prueba de carga. |
| CC-5 (resultado «acceso revocado»), EX-1 (plantilla desactualizada o extracción errónea), EX-2 (un extractor por remitente y formato) y EX-5 (motivo «formato no soportado» y fila por correo rechazado) | CRN-N5. Validación por esquema e invariantes, pruebas de contrato con corpus, alerta por salto de rechazos por remitente, versión del formato y actualización del vocabulario de los ADR-0006 y 0008. |
| EX-3 (el reproceso puede aplicar un estado más viejo) | CRN-N4: como MS-6, es un riesgo de orden en el reproceso y se acepta como ese residual (el ADR-0011 lo liga a CRN-N5 y se ajusta al aceptarlo). Reproceso solo de correos rechazados y en orden de fecha. |
| CC-2 (una lectura en vuelo puede terminar tras el `COMMIT`) | QA-10. Tiempo de espera acotado por llamada y pedido de revocación al proveedor. La medida cuenta solo lecturas con inicio posterior. |
| CC-4 (credenciales en la base operacional) y EX-4 (el correo no se conserva) | R-4. Cifrado de aplicación con clave fuera de la BD, rol propio, permiso de solo lectura del correo y exclusión de las copias y del log. Se acepta a cambio de no retener datos personales. Para los correos rechazados, la fila del registro permite ubicarlos. |
| SC-4 (la clase se recalcula al terminar cada trabajo) | QA-7. La ventana de viaje próximo supera el intervalo de la clase 2. Se acepta para viajes cargados con poca anticipación. |
| SC-5 (la retención corta recorta trabajos y los avisos pueden duplicarlos) | CRN-N6 y QA-5. Reemplazo del trabajo perdido a los 30 minutos\*, marca contra pérdida de correo y alerta de memoria. |

### 5.3 Concerns existentes: qué cambió

| Concern | Cambio |
|---|---|
| CRN-N2 | Se suman dos tablas por cuenta (credenciales y estado de sondeo), con escritura en cada sondeo. Siguen abiertos la partición, la réplica de lectura y el dueño de las tablas. |
| CRN-N3 | Se suman el planificador y las tareas de suscripciones y de limpieza, con alertas simples previstas. Siguen abiertos el despliegue, la alta disponibilidad y el monitoreo. |
| CRN-N5 | Se suman los extractores y la *whitelist* como contratos a mantener alineados con los esquemas y los adaptadores. |
| CRN-N6 | La retención del trabajo de correo baja a 1 h\* y el log nunca lleva contenido de correo ni tokens. |

**ADR anteriores a actualizar al aceptar esta iteración.** ADR-0006 y ADR-0008 (el resultado «acceso revocado», el motivo «formato no soportado» y el registro por correo) y ADR-0009 (retención del trabajo de correo).

**ADR de esta iteración a ajustar antes de aceptarlos.** Para que coincidan con lo que describe este documento:

- **ADR-0010:** el párrafo «Tabla y rol propios» lista solo a los workers (lectura) y al Panel API. Debe listar todos los usuarios del módulo de credenciales con su operación, como en la tabla 4.2, incluido el planificador, que solo consulta el estado. La tarea de limpieza también cancela la suscripción de avisos, antes de pedir la revocación del token.
- **ADR-0011:** los 15 s de QA-9 se escriben como valor propuesto (15 s\*), y EX-3 se vincula con CRN-N4.
- **ADR-0012:** la cancelación de la suscripción al revocar la hace la tarea de limpieza (ADR-0010), no la tarea de suscripciones. El receptor encola el trabajo `correo:<id de la cuenta>` con el sobre del ADR-0007, no el aviso tal como llegó.

## 6. Estado de los drivers al cierre

Estados: no abordado, parcialmente abordado y abordado. «Abordado a nivel de diseño» significa que el diseño está completo pero falta medirlo con proveedores simulados y prueba de carga. Se listan los drivers cuyo estado cambió. Siguen sin cambios respecto de la Iteración 2: CU-01, CU-02, CU-04, CU-06, QA-1, QA-2, QA-3, QA-4, QA-11, R-2, CRN-N1 y CRN-2 (la iteración no suma tecnologías).

| Driver | Estado | Fundamento | Pendiente |
|---|---|---|---|
| CU-03 | Abordado a nivel de diseño | Credenciales, revocación, filtro, extracción, detección y planificación (ADR-0010 a 0012), con el mismo contrato y flujo de guardado que CU-02. | Importar o no los correos anteriores a la conexión y con qué ventana. Flujo de conexión de la cuenta (Iteración 4). Prototipo con proveedor de correo simulado. |
| CU-08 | Parcial | *Whitelist* global por configuración. | Contenido de la *whitelist* y filtros por usuario (lista de exclusión sobre la global). |
| QA-7 | Parcial | Push más sondeo de respaldo, clases por cercanía del viaje y presupuesto repartido por etapa. | Verificar el margen del presupuesto (10 + 5 minutos\*, sin holgura). Dimensionar los workers con el tiempo medio por trabajo y la tasa de trabajos. Prueba con cuotas simuladas. |
| QA-9 | Abordado a nivel de diseño | Extractores por formato con corpus de muestra, y medida repetible sobre los formatos soportados. | Corpus por remitente y medición del 95%\* y de los 15 s\*. |
| QA-10 | Parcial | Estado confirmado en la BD, verificación antes de cada lectura y evidencia por llamada. Cubre las revocaciones hechas desde Trotamundos. | Prueba con proveedor simulado (0 llamadas posteriores). Las revocaciones hechas desde el proveedor se detectan en la siguiente llamada, sin garantía del minuto\* (CC-1, CRN-N7). |
| QA-8 | Abordado a nivel de diseño | La revocación no cuenta como inválido ni como falla del proveedor. Los correos rechazados dejan una fila con motivo. | Definir cómo cuenta el motivo «formato no soportado» para QA-8. |
| QA-5 | Parcial | La marca de sincronización recupera lo llegado durante una caída del proveedor de correo. | Medir la recuperación. RS-3 de la Iteración 2 sigue abierto. |
| QA-6 | Parcial | El costo de detección sigue al correo que llega y a las cuentas con viajes próximos. | Prueba de carga con 15 M de filas de estado (SC-3). |
| QA-12 | Abordado a nivel de diseño | Un remitente, formato o tipo nuevo se agrega con una plantilla y una entrada de configuración. | Probar con el tren contra los 2 días-persona\*. |
| R-3 | Parcial | Respaldo siempre activo, limitador por proveedor y revocación distinguida de la falla. | Cuotas reales de cada proveedor de correo (SC-2). |
| R-4 | Parcial | Tokens cifrados y borrados al revocar, correo no conservado y sin contenido en el log. | Gestor de claves en producción (Iteración 5) y protección por región. |
| R-6 | Parcial | Sin tecnologías ni desplegables nuevos. | Reverificar el esfuerzo de las plantillas por remitente (EX-2) y de las piezas nuevas. |
| CRN-N2, CRN-N3 | Parcial | Tablas con índice mínimo y alertas simples previstas. | Partición, réplica, despliegue y monitoreo. |
| CRN-N5, CRN-N6 | Parcial | Extractores con pruebas de contrato y retención de 1 h\* sin contenido de correo. | Corpus de pruebas y medición de memoria en la prueba de carga. |
| CRN-N4 | Parcial | Se suma la extracción determinista, que mantiene la idempotencia. | MS-3, MS-5 y MS-6, y EX-3. |
| CRN-N7 | No abordado | Concern nueva de esta iteración. | Se incorpora al catálogo de drivers. |

### Drivers no abordados

- **Funcionalidad secundaria:** CU-05, CU-07, CU-09.
- **Restricciones:** R-1 y R-5.
- **Concerns:** CRN-3 (aislamiento de la analítica y de los casos de uso secundarios) y CRN-N7.

**Próximas iteraciones.** La Iteración 4 cubre el Panel API (incluido el flujo de conexión del correo). La Iteración 5 cubre la partición de la base, la réplica de lectura, el despliegue, la alta disponibilidad, el autoescalado y la gestión de la clave de cifrado.

**Replanificación respecto de la Iteración 2.** La Iteración 2 preveía la partición de la base, la réplica de lectura y el despliegue para la Iteración 4 (secciones 2 y 5.2). Esos temas pasan a la Iteración 5, en línea con los ADR-0010 y 0012, para que la Iteración 4 se concentre en el Panel API y en el flujo de conexión del correo.
