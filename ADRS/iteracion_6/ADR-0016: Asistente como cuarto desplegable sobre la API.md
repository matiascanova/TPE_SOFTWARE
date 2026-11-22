# ADR-0016: Asistente como cuarto desplegable que opera por la API del Panel API con el token del usuario

- **Estado:** Propuesto
- **Drivers de entrada:** [Análisis inicial y drivers](Analisis-Inicial-y-Drivers.md) (CU-10, CU-04; QA-13, QA-14, QA-11, QA-6, QA-4, R-6, CRN-2)

---

## 1. Contexto

El asistente de primer nivel (CU-10) debe leer viajes y modificar reservas (CU-04) sin saltarse el punto de autorización (QA-11, ADR-0012) ni el origen único de escritura (ADR-0013); si tuviera permisos propios sobre los datos, podría ver o cambiar más que el usuario (QA-14). Sus llamadas al modelo de lenguaje duran segundos (QA-13) y no deben ocupar hilos del Panel API (QA-6) ni arrastrarlo si fallan (QA-4). El ADR-0001 fija tres desplegables y difiere sumar servicios, el plazo es acotado (R-6) y el equipo domina Java (CRN-2).

**Pregunta:** ¿Dónde corre el asistente y con qué permisos accede a los datos del usuario?

---

## 2. Decisión

**Se elige A1: el Asistente es un cuarto desplegable, con escalado propio (ADR-0015), que lee y modifica solo por los recursos REST del Panel API con el token del usuario y es dueño únicamente de sus tablas (conversaciones, acciones pendientes y casos).**

- El Asistente no tiene credenciales ni acceso al read model, a las tablas de reservas ni al broker, salvo el consumo de eventos de caso (ADR-0018).
- Valida el token en cada pedido, lo reenvía solo al Panel API y no lo guarda ni lo registra.
- Su contrato (`/v1/asistente`) comparte autenticación, formato y versión en la ruta con el ADR-0011, detrás del mismo balanceador.

### Alternativas descartadas

| Alternativa | Motivo |
|---|---|
| A2. Módulo dentro del Panel API | Comparte hilos y escalado con las lecturas: las llamadas de segundos al modelo compiten con el panel (QA-6) y una falla del modelo o del módulo lo arrastra (QA-4). |
| A3. Asistente con acceso directo al read model y a la base | Salta el punto de autorización y el origen único de escritura, y duplica sus reglas (QA-11, QA-14). |
| A4. Plataforma de chatbot de terceros que llama a la API | Guarda conversaciones con datos personales en una plataforma ajena (R-4) y no permite imponer la confirmación ni el escalamiento propios (QA-14); suma una pieza que operar (R-6). |

---

## 3. Consecuencias

**Positivas**

- Lo que el asistente puede ver o cambiar es lo que el Panel API permite al usuario, sin tocar ADR-0012 ni ADR-0013 (QA-14, QA-11).
- La lentitud o caída del modelo queda dentro del asistente y el panel no se entera (QA-4, QA-6).
- Escala por separado y corre en al menos dos zonas con el mismo orquestador (QA-13, ADR-0014, ADR-0015).
- El Panel API y la Ingesta no cambian (R-6).

**Negativas y riesgos aceptados (máximo 5)**

| ID | Riesgo | Efecto | Mitigación |
|---|---|---|---|
| AS-1 | Cada acción suma un salto y un pedido al Panel API | Latencia contra QA-13 y más carga sobre el panel (QA-6) | Lecturas por clave ya existentes, límite de mensajes por usuario y prueba de carga |
| AS-2 | Un cuarto desplegable suma imagen, despliegue y monitoreo | Costo operativo (R-6) | Se acepta: misma imagen base, Java y docker-compose |
| AS-3 | Los clientes consumen dos contratos | Acople de los clientes (R-1) | Misma autenticación, formato y versión, y un solo balanceador por ruta |
| AS-4 | El token del usuario pasa por el Asistente | Exposición del token (QA-11, R-4) | Reenviarlo solo al Panel API, sin guardarlo ni registrarlo |
| AS-5 | Con la base relacional caída no hay historial ni acciones pendientes | Asistente degradado (CRN-14) | Responde consultas de estado sin historial y rechaza las acciones |
