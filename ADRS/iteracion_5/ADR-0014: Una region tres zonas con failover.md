# ADR-0014: Una región con tres zonas, almacenes con réplica sincrónica y failover automático

- **Estado:** Propuesto
- **Drivers de entrada:** Analisis-Inicial-y-Drivers.md. CU-01, CU-02, CU-03. Otros: QA-4, QA-1, QA-5, R-4, R-6, CRN-6, CRN-9, CRN-14.

---

## 1. Contexto

El sistema debe tolerar la caída de una instancia o de una zona con 5 minutos mensuales de inactividad no planificada como máximo (QA-4). Los desplegables no guardan estado y pueden correr en varias zonas, pero la base relacional, el read model y el broker son instancias únicas. Si la base cae, el panel queda degradado (CRN-14), y si se pierde la instancia del broker se pierde lo confirmado (CRN-6). Replicar cada escritura en otra zona antes de confirmarla evita perder datos (QA-5), pero suma latencia a la ingesta (QA-1). Extender el sistema a varias regiones cubre fallas más grandes, pero exige decidir qué datos personales cruzan de región (R-4) y operar replicación entre regiones en un plazo acotado (R-6), y dos regiones que escriben a la vez rompen la versión por viaje (CRN-9).

**Pregunta:** ¿Cómo se distribuyen los desplegables y los almacenes para que la caída de una instancia o de una zona no supere 5 minutos de inactividad mensual?

---

## 2. Decisión

**Se elige A1: una región con tres zonas; cada desplegable corre en al menos dos zonas detrás de un balanceador, y la base relacional, el read model y el broker tienen un primario y réplicas en otras zonas, con confirmación sincrónica en al menos una réplica y failover automático.**

- Ninguna escritura en la base relacional ni en el broker se confirma antes de llegar a otra zona.
- El failover automático de cada almacén se completa en menos de 1 minuto.
- Desplegables y workers se conectan al primario por un nombre estable, sin redespliegue tras el failover.

### Alternativas descartadas

| Alternativa | Motivo |
|---|---|
| A2. Una sola zona con reinicio automático de instancias | La caída de la zona deja todo el sistema fuera de servicio (QA-4). |
| A3. Réplicas asincrónicas con failover | Un failover pierde escrituras ya confirmadas que el proveedor no vuelve a enviar (QA-5, CRN-6). |
| A4. Dos regiones, activa y pasiva | Exige replicación entre regiones y decidir qué datos personales cruzan (R-4) en un plazo acotado (R-6). Queda como evolución: la topología por zonas se repite en otra región sin rehacerla. |
| A5. Dos regiones activas | Dos primarios que escriben a la vez rompen la versión por viaje (CRN-9) y mueven datos personales entre regiones (R-4). |

---

## 3. Consecuencias

**Positivas**

- La caída de una instancia o de una zona se resuelve con un failover de menos de 1 minuto, dentro de los 5 minutos mensuales (QA-4).
- El modo degradado del panel dura lo que dura el failover de la base (CRN-14).
- Lo que el broker y la base confirmaron no se pierde al caer una zona (CRN-6, QA-5).
- Se usan las mismas tecnologías, sin piezas nuevas (R-6).

**Negativas y riesgos aceptados (máximo 5)**

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| TP-1 | La confirmación sincrónica suma la latencia entre zonas a cada escritura | Consume parte de los 5 minutos de QA-1 | Zonas de una misma región y medición en la prueba de carga |
| TP-2 | Si cae la única réplica sincrónica, el primario no puede confirmar | Se pausa la ingesta (QA-4) | Dos réplicas en zonas distintas, y alcanza con que una confirme |
| TP-3 | Una falla de toda la región deja el sistema fuera de servicio | Inactividad mayor a la de QA-4 | Se acepta: QA-4 pide tolerar una instancia o una zona; dos regiones quedan como evolución (A4) |
| TP-4 | Failovers frecuentes o lentos agotan el margen mensual | Incumple QA-4 | Alerta por cada failover y prueba periódica de caída de zona |
