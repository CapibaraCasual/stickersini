# ADR-0001: Desarrollar la aplicación en Kotlin + Jetpack Compose, nativo

- Estado: Aceptado
- Fecha: 2026-09-19

## Contexto

El proyecto es una aplicación Android de un solo desarrollador, sin plazo
comercial, cuyo objetivo doble es ser un producto usable y una pieza de
portafolio.

El grueso de la funcionalidad depende de APIs exclusivas de Android:
`MediaProjection` para la captura de pantalla, un servicio en primer plano con
tipo `mediaProjection`, una ventana superpuesta (`SYSTEM_ALERT_WINDOW`) para la
burbuja, un `ContentProvider` que cumpla el contrato de WAStickerApps, y una
biblioteca nativa en C invocada por JNI para codificar WebP animado.

No hay ninguna intención de publicar en iOS. WhatsApp expone el mecanismo de
packs de forma distinta en cada plataforma, así que incluso un hipotético
puerto a iOS no reutilizaría esa parte.

## Opciones consideradas

1. **Flutter** — descartada: ninguna de las APIs listadas está disponible desde
   Dart. Habría que escribirlas igual en Kotlin y añadir además canales de
   plataforma para exponerlas. Flutter solo cubriría la interfaz del editor,
   aproximadamente el 20% del trabajo, a cambio de duplicar la superficie de
   mantenimiento.
2. **React Native** — descartada por la misma razón que Flutter, agravada por
   peor rendimiento en el manejo de imágenes y buffers.
3. **Kotlin Multiplatform** — descartada: resuelve compartir lógica entre
   plataformas, y aquí solo hay una plataforma.
4. **Kotlin + Jetpack Compose nativo** — elegida.

## Decisión

Kotlin con Jetpack Compose para la interfaz, sin capa multiplataforma.

El criterio decisivo es que el trabajo específico de Android no es un detalle
del proyecto: es el proyecto. Cualquier framework multiplataforma se convierte
en una capa de indirección sobre código que hay que escribir igual.

## Consecuencias

- El código llama a las APIs de Android directamente, sin puentes ni plugins de
  terceros que puedan quedar abandonados.
- No hay camino corto a iOS. Si alguna vez se quisiera, sería una reescritura.
- Se asume la curva de Compose, que es menor que la de mantener un puente
  Flutter para cuatro subsistemas nativos.
- El perfil técnico resultante queda alineado con puestos de Android nativo,
  que es lo que se busca.
