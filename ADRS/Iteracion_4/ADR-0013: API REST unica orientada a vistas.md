# ADR-0013: API REST única para web y móvil, con un recurso por vista del panel

- **Estado:** Propuesto
- **Drivers de entrada:** Analisis-Inicial-y-Drivers.md. CU-01, CU-04, CU-06. Otros: QA-2, QA-3, QA-6, R-1, R-5, R-6, CRN-2.

---

## 1. Contexto

El Panel API atiende navegadores y aplicaciones móviles (R-1) y todavía no tiene contrato externo. Cada vista del panel debe responder en menos de 800 ms con carga normal (QA-2) y el primer despliegue debe ocurrir en menos de 1,4 s (QA-3), lo que pide pocas idas y vueltas por pantalla, sobre todo en redes móviles (R-5). El read model ya guarda un documento por viaje y un índice por usuario (ADR-0004). Un contrato a medida de cada cliente ajusta mejor las respuestas, pero duplica código y despliegues en un plazo acotado (R-6), con un equipo que conoce Java y no herramientas de API específicas (CRN-2).

**Pregunta:** ¿Qué estilo de API expone el Panel API a los clientes web y móvil?

---

## 2. Decisión

**Se elige A1: una única API REST compartida por web y móvil, en la que cada vista del panel es un recurso que se resuelve con una lectura del read model, y la gestión manual y el compartir son recursos de escritura.**

- Una vista del panel nunca se arma componiendo varias llamadas desde el cliente.
- Las diferencias de presentación entre web y móvil se resuelven en el cliente, no en el contrato.

### Alternativas descartadas

| Alternativa | Motivo |
|---|---|
| A2. Un backend por cliente (BFF web y BFF móvil) | Duplica contratos y desplegables para dos clientes que muestran las mismas vistas (R-6). |
| A3. GraphQL | Suma esquema, resolvers y límites de costo de consulta que el equipo no conoce (R-6, CRN-2), para vistas fijas que no necesitan consultas a medida. |
| A4. REST por recurso fino, con la vista compuesta en el cliente | Multiplica las idas y vueltas por pantalla, sobre todo en móvil (QA-2, QA-3). |
| A5. gRPC | Los navegadores no lo usan sin un proxy gRPC-Web (R-1). |

---

## 3. Consecuencias

**Positivas**

- Un solo contrato y un solo desplegable para web y móvil (R-1, R-6).
- Cada vista cuesta una lectura indexada del read model, lo que permite razonar QA-2 sin medir.
- Menos idas y vueltas por pantalla en el primer despliegue (QA-3).

**Negativas y riesgos aceptados (máximo 5)**

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| AP-1 | Los recursos por vista acoplan la API a las pantallas | Cambiar una pantalla cambia el contrato de ambos clientes (R-1) | Campos opcionales y versión nueva que convive con la anterior |
| AP-2 | Móvil recibe la misma respuesta que web, con datos que no usa | Más bytes en el primer despliegue (QA-3) | Compresión y paginación |
| AP-3 | La vista con todos los viajes del usuario no cabe en un documento | Varias lecturas en una vista (QA-2, QA-6) | Índice por usuario del read model (ADR-0004) y paginación |
