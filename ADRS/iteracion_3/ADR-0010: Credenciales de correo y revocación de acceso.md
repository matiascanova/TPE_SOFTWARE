# ADR-0010: Credenciales de correo y revocación de acceso

- **Estado:** Propuesto
- **Decisores:** Equipo de diseño (grupo TPE)
- **Drivers de entrada:** Análisis inicial y drivers, Iteración 1 e Iteración 2. CU-03. Otros drivers: QA-7, QA-8, QA-10, R-3, R-4, R-6, CRN-2 y CRN-N6.
- **ADR relacionados:** ADR-0005 (motor de la BD), ADR-0006 (modelo canónico y adaptador), ADR-0007 (contratos de mensajes), ADR-0008 (resiliencia de la ingesta por proveedor), ADR-0009 (tecnología del broker y retención). ADR-0011 (extracción de reservas) y ADR-0012 (sondeo de correo), de la Iteración 3, dependen de este.

---

## 1. Contexto y problema

Para incorporar reservas desde el correo (CU-03), el sistema lee la casilla de cada usuario en segundo plano. Esa lectura usa dos credenciales que el proveedor de correo (Gmail, Outlook) entrega al usuario cuando autoriza el acceso: el **token de acceso**, de vida corta, y el **token de renovación**, que permite obtener tokens de acceso nuevos sin intervención del usuario. El ADR-0005 dejó fuera de su alcance dónde se guardan, y el ADR-0007 fijó que el log nunca los lleva. Ningún ADR define todavía cómo se guardan ni qué pasa cuando el usuario revoca el acceso. Esta decisión va primero en la Iteración 3 porque condiciona qué lee la extracción de reservas (ADR-0011) y qué cuentas puede sondear el planificador (ADR-0012).

QA-10 exige que, al revocar el usuario su acceso, el sistema deje de leer ese correo en menos de 1 minuto\* y no realice ninguna lectura posterior con el token revocado (0 accesos).

Este documento responde dos preguntas:

1. **¿Dónde y cómo se guardan los tokens de acceso al correo?**
2. **¿Cómo se corta la lectura al revocar el acceso y cómo se verifica que no hay lecturas posteriores?**

Se deciden juntas porque el lugar donde viven los tokens determina qué se puede consultar antes de cada lectura y qué se puede borrar al revocar.

**Tensiones:**

- **Cortar de inmediato frente a cargar la base (QA-10, QA-7).** La forma más estricta de asegurar cero lecturas posteriores es verificar el estado de la cuenta antes de cada lectura. Eso suma una consulta a la base compartida por cada trabajo de correo, que debe procesarse en 15 minutos\* para el 95%\* de los usuarios con viajes próximos.
- **Cero accesos frente a lo que el sistema puede saber (QA-10, R-3).** El sistema solo sabe que una cuenta fue revocada si se lo dicen o si una llamada falla. Un usuario que revoca desde la cuenta del proveedor de correo no avisa a Trotamundos, y las lecturas ya iniciadas no se pueden interrumpir.
- **Guardar credenciales frente a minimizar su exposición (R-4, CRN-N6).** La lectura en segundo plano exige conservar los tokens, pero no deben salir del almacén de credenciales: ni al log en memoria, ni a los apartados, ni a la copia analítica.
- **Seguridad frente a viabilidad (R-6, CRN-2).** Un almacén de secretos dedicado es la opción más robusta, pero suma una tecnología que el grupo no conoce, con un plazo acotado.
- **Revocación frente a falla (QA-8, R-3).** Un acceso denegado por token revocado no es un mensaje inválido ni una caída del proveedor. Si se clasifica mal, distorsiona la medida de QA-8 o abre el circuito de todo el proveedor (ADR-0008).

**Fuera de alcance:**

- Extracción de reservas desde el texto del correo y filtros de remitentes (ADR-0011).
- Planificación del sondeo, notificaciones push del proveedor de correo y capacidad (ADR-0012). El planificador consulta el estado definido acá para no encolar cuentas revocadas.
- Pantalla y flujo de autorización OAuth para conectar o desconectar el correo, y autenticación del usuario en Trotamundos (Panel API, Iteración 4).
- Rotación de la clave de cifrado y su gestión en producción (ADR de despliegue, Iteración 5).
- Residencia de los tokens por región (multi-región, R-4, iteración posterior).
- Revocación del acceso de personas designadas a un itinerario (CU-06).

---

## 2. Criterios de decisión

| Criterio | Qué mide en esta decisión | Se usa en |
|---|---|---|
| QA-10 | Que la lectura se corte en menos de 1 minuto\* y que ninguna lectura empiece después de la revocación (0 accesos). | A / B |
| QA-7 | Que verificar o descifrar el estado por trabajo no impida procesar el correo de viajes próximos en 15 minutos\*. | A / B |
| QA-8 | Que una revocación no se cuente como mensaje inválido ni como caída del proveedor. | B |
| R-3 | Que funcione con proveedores que no avisan las revocaciones y cuyos tokens vencen y se renuevan con sus propias reglas. | A / B |
| R-4 | Que los tokens se guarden solo mientras hacen falta, se borren al revocar y no salgan del almacén de credenciales. | A / B |
| R-6 | Esfuerzo de implementación para el grupo y piezas nuevas que operar. | A / B |
| CRN-2 | Que use tecnologías que el grupo domina. | A |
| CRN-N6 | Que el diseño no sume datos sensibles ni trabajo de purga al log en memoria. | A / B |

---

## 3. Decisión A: almacenamiento de los tokens

### 3.1 Alternativas consideradas

| ID | Alternativa | Resumen |
|---|---|---|
| A1 | **Tokens cifrados en una tabla propia de la BD operacional** | Los dos tokens se guardan cifrados en una tabla aislada de PostgreSQL, con el estado de cada cuenta, detrás de un módulo de credenciales. |
| A2 | Almacén de secretos dedicado | Los tokens viven en un gestor de secretos (por ejemplo Vault o el de la plataforma cloud). La BD guarda solo una referencia y el estado. |
| A3 | Tokens en Redis | Los tokens se guardan en una de las instancias de Redis (broker o read model). |
| A4 | No guardar tokens | El usuario vuelve a autorizar en cada lectura, o el cliente conserva el token. |

### 3.2 Decisión

**Se elige A1: los tokens de acceso y de renovación se guardan cifrados en una tabla propia de la BD operacional, con un estado por cuenta (activa o revocada), y solo un módulo de credenciales accede a ella.**

**Descripción de la decisión.** Cada cuenta de correo conectada tiene una fila con su proveedor, su estado y sus dos tokens cifrados. El módulo de credenciales expone operaciones mínimas (entregar la credencial de una cuenta activa, renovarla y revocarla) y es la única parte del código que conoce el almacenamiento. Aplica las tácticas de seguridad *Encrypt Data*, *Limit Exposure* y *Separate Entities*.

- **Tabla y rol propios.** La tabla de credenciales tiene un rol de base de datos propio, que solo usa el módulo de credenciales. Quienes necesitan una credencial la piden a través de sus operaciones. El planificador de sondeo solo consulta el estado de la cuenta (activa o revocada) y nunca recibe tokens. Ningún otro módulo accede a la tabla.
- **Cifrado de aplicación.** Los tokens se cifran antes de guardarse, con una clave que no está en la BD. En el prototipo la clave está en la configuración. En producción vive en el gestor de claves de la plataforma.
- **Sin salida del almacén.** Los tokens no viajan en mensajes del log, en los apartados ni en el outbox, no se escriben en los registros de la aplicación y la copia periódica hacia el almacén analítico excluye la tabla.
- **Permiso mínimo.** La autorización que se pide al usuario es de solo lectura del correo.
- **Detrás de un puerto.** Si el almacenamiento cambia, solo cambia el módulo de credenciales.

### 3.4 Por qué no las otras alternativas

- **A2. Almacén de secretos dedicado.** Es la opción más robusta, pero suma una tecnología para aprender y operar con un plazo acotado (R-6, CRN-2), cuando PostgreSQL ya está en el sistema y el equipo lo domina. Queda como evolución prevista: como solo el módulo de credenciales conoce el almacenamiento, pasar a A2 no exige reescribir el resto.
- **A3. Tokens en Redis.** La instancia del read model expulsa datos cuando se llena (ADR-0004), y perder un token obligaría al usuario a volver a autorizar. La del broker recorta por tiempo (ADR-0009) y sumaría datos sensibles al log en memoria (CRN-N6, R-4). Además, el estado de revocación debe sobrevivir a una caída, y la BD lo garantiza mejor.
- **A4. No guardar tokens.** Sin un token disponible en segundo plano, el sistema no puede sondear la casilla, y no se cumple el procesamiento del correo en 15 minutos\* (QA-7).

---

## 4. Decisión B: mecanismo de revocación y corte de la lectura

### 4.1 Alternativas consideradas

| ID | Alternativa | Resumen |
|---|---|---|
| B1 | **Verificación del estado antes de cada lectura, revocación atómica y descarte del trabajo pendiente al consumirlo** | La revocación es un cambio de estado confirmado en la BD. Cada worker consulta ese estado justo antes de llamar al proveedor y descarta el trabajo si la cuenta no está activa. |
| B2 | Purgar del log los trabajos pendientes de la cuenta | Al revocar se buscan y se borran del log los trabajos encolados de esa cuenta. |
| B3 | Esperar a que venza el token de acceso | No se hace nada al revocar. El corte llega cuando el token de acceso expira y el proveedor rechaza la renovación. |
| B4 | Caché de estado en cada worker con aviso de invalidación | Los workers guardan el estado en memoria y reciben un aviso al revocarse. |

### 4.2 Decisión

**Se elige B1: revocar es una transición de estado confirmada en la BD, y todo worker verifica ese estado inmediatamente antes de cada llamada al proveedor de correo, descartando el trabajo si la cuenta no está activa.**

**Descripción de la decisión.** Una **lectura posterior** es una llamada al proveedor de correo que empieza después de que se confirma (`COMMIT`) la transacción de revocación. Con B1 el corte es inmediato, porque la verificación se hace contra la fuente de verdad y no contra una copia. El trabajo ya encolado no se borra del log: se consume y se descarta sin llamar al proveedor.

- **Revocación atómica.** El Panel API pasa la cuenta a revocada en una sola transacción y registra la hora en la auditoría. Desde ese momento el módulo de credenciales deja de entregar los tokens a los workers y solo los entrega a la tarea de limpieza. Esta cancela primero la suscripción de notificaciones de la cuenta, si la tiene (ADR-0012), porque necesita un token válido para hacerlo. Si la cancelación falla, sigue con la revocación: los avisos que lleguen se descartan al consumirlos. Después pide al proveedor que revoque el token (si ofrece un endpoint para eso), con reintentos acotados, y por último borra los tokens.”
- **Verificación antes de cada lectura.** Es una consulta por clave primaria, sin caché. Una lectura que ya empezó antes del `COMMIT` no cuenta como posterior y su duración está acotada por el tiempo de espera.
- **Trabajo pendiente descartado al consumirlo.** El worker confirma el trabajo sin leer el correo. No va al stream de apartados y no cuenta como inválido (QA-8).
- **Revocación desde el proveedor.** Si el usuario revoca desde la cuenta del proveedor, el sistema lo detecta en la siguiente llamada: una respuesta de acceso denegado por token revocado, o una renovación rechazada, marca la cuenta como revocada. No se reintenta, no cuenta como falla del proveedor para el circuit breaker y no cuenta como mensaje inválido. Se distingue de un tiempo de espera, un 5xx o un 429, que siguen las reglas del ADR-0008. Esto amplía los resultados del adaptador de correo (ADR-0006) y la clasificación de fallas (ADR-0008) con un caso que no es inválido ni falla del proveedor: contarlo como inválido falsearía QA-8, y contarlo como falla abriría el circuito de todo un proveedor por la decisión de un solo usuario.
- **Evidencia medible.** Cada llamada al proveedor registra la cuenta y su hora de inicio, y la revocación registra la hora de su `COMMIT`. La prueba de QA-10 usa un proveedor de correo simulado y exige cero llamadas con inicio posterior al `COMMIT`.

### 4.3 Cómo funciona

Un usuario desconecta su Gmail desde Trotamundos. Su cuenta de correo es la 789, y hay un trabajo de sondeo `correo:789` esperando en el log.

1. El Panel API pasa la cuenta 789 a revocada y registra la hora en la auditoría, todo en una transacción.
2. Una tarea de limpieza cancela la suscripción de notificaciones de la cuenta, pide a Gmail que revoque el token y después borra los tokens de la fila.
3. Un worker toma el trabajo encolado, consulta el estado de la cuenta 789, ve que está revocada, confirma el trabajo y no llama a Gmail.
4. El planificador de sondeo (ADR-0012) consulta el estado y no vuelve a encolar la cuenta.

| Qué falla | Qué pasa | Resultado |
|---|---|---|
| Había trabajos de la cuenta en el log al revocar. | Se descartan al consumirlos, sin llamar al proveedor. | Ninguna lectura posterior. |
| Una lectura ya estaba en curso al revocar. | Termina o falla si el proveedor ya revocó el token. | No cuenta como posterior. Queda acotada por el tiempo de espera. |
| Falla el pedido de revocación al proveedor. | La tarea de limpieza reintenta. Mientras tanto el módulo no entrega los tokens a ningún worker. | Sin lecturas. El token queda vigente en el proveedor hasta que se revoque o venza. |
| El usuario revoca desde el proveedor. | La siguiente llamada recibe acceso denegado y marca la cuenta como revocada. | La detección tarda hasta el siguiente sondeo de la cuenta. |
| El proveedor responde 5xx o tarda de más. | No se trata como revocación. Se aplican los reintentos y el circuit breaker del ADR-0008. | La caída no marca cuentas como revocadas. |
| La BD no responde al verificar el estado. | No se llama al proveedor. Se pausa el consumo sin apartar el trabajo (ADR-0008). | Ante la duda no se lee. |

### 4.4 Por qué no las otras alternativas

- **B2. Purgar el log.** El log no se indexa por cuenta, así que habría que recorrer la partición que le corresponde para encontrar sus mensajes (ADR-0008, ADR-0009). Borrar entradas con consumidores activos y pendientes de reentrega es frágil y suma código propio (R-6). Tampoco cubre el trabajo que un worker ya tomó, así que no asegura cero lecturas posteriores (QA-10). Suma además trabajo de borrado sobre un log en memoria cuyo único recorte previsto es por tiempo (ADR-0009, CRN-N6).
- **B3. Esperar el vencimiento.** La vida del token de acceso la fija el proveedor (R-3), así que el corte no depende del sistema y no se puede asegurar el minuto\* de QA-10.
- **B4. Caché con invalidación.** Reduce la carga sobre la base, pero un aviso perdido o demorado deja una ventana con lecturas sobre un token revocado, y la medida de cero accesos deja de poder garantizarse (QA-10). Además suma un mecanismo de avisos entre procesos (R-6). Queda como evolución solo si la prueba de carga muestra que la consulta por trabajo es un cuello de botella (QA-7). En ese caso hay que redefinir la medida de QA-10 antes de adoptarla.

---

## 5. Consecuencias

### Positivas

- La revocación hecha desde Trotamundos corta la lectura al confirmarse la transacción, y la medida de cero lecturas posteriores es verificable con un proveedor simulado (QA-10).
- Una cuenta revocada no abre circuitos ni se cuenta como mensaje inválido, así que QA-8 sigue midiendo la calidad de los mensajes y no la voluntad de los usuarios.
- Los tokens se borran al completarse la revocación y no salen de su tabla: no pasan por el log, los apartados ni el almacén analítico (R-4, CRN-N6).
- No suma tecnologías nuevas: usa PostgreSQL, que el grupo domina (R-6, CRN-2). La única pieza nueva es la tarea de limpieza.
- Cada trabajo de correo cuesta una consulta por clave primaria, que es barata y no depende de purgar el log (QA-7, CRN-N6).

### Negativas y riesgos aceptados

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| CC-1 | Una revocación hecha en el proveedor se detecta recién en la siguiente llamada. | El plazo de 1 minuto\* de QA-10 solo se garantiza para revocaciones hechas desde Trotamundos (R-3). | El sondeo prioriza las cuentas con viajes próximos (ADR-0012). Se aprovechan las notificaciones del proveedor si las ofrece. Se mide el tiempo de detección. |
| CC-2 | Una lectura iniciada antes del `COMMIT` de la revocación puede completarse después. | Una lectura en vuelo puede terminar con el token ya revocado (QA-10). | Tiempo de espera acotado por llamada. Pedido de revocación al proveedor tras el `COMMIT`. La medida cuenta solo lecturas con inicio posterior. |
| CC-3 | La verificación antes de cada lectura suma una consulta por trabajo a la base compartida. | Presión sobre la base con volumen de correo alto (QA-7). | Consulta por clave primaria indexada. Medición en la prueba de carga. Evolución a B4 con redefinición de la medida si hace falta. |
| CC-4 | Las credenciales viven en la base operacional. | Si se compromete la base o la clave, quedan expuestos tokens con acceso de lectura al correo (R-4). | Cifrado de aplicación con clave fuera de la BD, rol propio, exclusión de las copias y del log, permiso mínimo de solo lectura. Evolución a A2 detrás del puerto. |
| CC-5 | Se agrega una clasificación nueva (acceso revocado) a lo que el adaptador de correo y los workers distinguen de una falla o de un inválido. | Una respuesta mal clasificada abre un circuito de más o aparta mensajes de más, y distorsiona QA-8 (R-3). | Pruebas con un proveedor de correo simulado que responde revocación y caídas. Actualizar el vocabulario de resultados de los ADR-0006 y ADR-0008 al aceptar este ADR. |
