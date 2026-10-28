# ADR-0015: Contenedores en un orquestador gestionado, con los almacenes como servicios gestionados

- **Estado:** Propuesto
- **Drivers de entrada:** Analisis-Inicial-y-Drivers.md. CU-01, CU-02, CU-03. Otros: QA-4, QA-6, QA-7, QA-1, R-6, CRN-3, CRN-6.

---

## 1. Contexto

Trotamundos tiene tres desplegables, y la Ingesta tiene cuatro roles que escalan por separado (ADR-0001). El Panel API debe absorber picos ×10 (QA-6), los workers deben crecer con las 15 M de cuentas de correo (QA-7) y ambos deben escalar a tiempo para no consumir los 5 minutos de QA-1. Cada instancia caída debe reemplazarse sola y repartirse entre las zonas del ADR-0016 (QA-4), y los almacenes necesitan el failover que ese ADR exige. La analítica debe seguir aislada en su propio desplegable (CRN-3), y el plazo es acotado (R-6).

**Pregunta:** ¿Sobre qué plataforma se ejecutan los desplegables y los almacenes, y cómo escala cada uno por separado?

---

## 2. Decisión

**Se elige A1: cada desplegable y cada rol de la Ingesta corre como contenedor en un orquestador gestionado por el proveedor de nube, con escalado automático propio, y la base relacional, el read model y el broker se usan como servicios gestionados con réplica y failover entre zonas.**

- Cada rol de la Ingesta, el Panel API y la Analítica son despliegues separados, con al menos dos instancias en zonas distintas.
- Los workers escalan por el retraso de consumo de su stream, y el Panel API por la carga de pedidos.

### Alternativas descartadas

| Alternativa | Motivo |
|---|---|
| A2. Máquinas virtuales con grupos de autoescalado | Cada rol necesita su imagen, su grupo y su escalado configurados a mano, y el reemplazo de instancias es más lento (R-6, QA-4). |
| A3. Funciones sin servidor para receptor y workers | No sostienen un consumidor activo por partición ni conexiones largas con el broker (ADR-0008, QA-1). |
| A4. Orquestador propio, no gestionado | Operar el plano de control suma trabajo sin mejorar ningún driver (R-6). |
| A5. Almacenes dentro del orquestador, operados por el equipo | La réplica y el failover que exige el ADR-0016 quedan a cargo de configuración y operación propias (R-6, QA-4). |

---

## 3. Consecuencias

**Positivas**

- Cada rol escala según su propia carga, sin arrastrar a los demás (QA-6, QA-7).
- Las instancias caídas se reemplazan solas y se reparten entre zonas (QA-4).
- El failover de los almacenes lo da el servicio, no código propio (R-6).
- La Analítica corre en su propio despliegue, sin compartir recursos con el sistema operativo (CRN-3).

**Negativas y riesgos aceptados (máximo 5)**

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| PL-1 | El servicio gestionado del broker no admite la configuración que el broker necesita | Pierde las garantías de durabilidad acordadas (CRN-6) | Verificar `noeviction` y la persistencia antes de elegir el servicio; si no las admite, correr el broker en el orquestador |
| PL-2 | El escalado por retraso reacciona tarde a un pico | Demora contra QA-1 y QA-7 | Mínimo de instancias holgado y prueba de carga con picos ×10 |
| PL-3 | Los servicios gestionados atan el sistema a un proveedor de nube | Costo de migrar (R-6) | PostgreSQL y Redis estándar, sin extensiones propias del proveedor |
