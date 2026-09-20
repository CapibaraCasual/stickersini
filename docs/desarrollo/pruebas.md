# Registro de pruebas manuales

Este documento registra en qué dispositivos y entornos se ha validado
manualmente cada hito del proyecto. No sustituye a los tests automatizados
(`./gradlew test` y `./gradlew connectedAndroidTest`): los complementa donde
el test automatizado no llega, como la confirmación real de WhatsApp al
añadir un pack.

Una fila por prueba. No se borran filas antiguas aunque el resultado quede
obsoleto por un cambio posterior: si algo deja de funcionar, se añade una fila
nueva con el resultado actual, no se edita la vieja.

## Cómo añadir una fila

Copia la plantilla, complétala y añádela al final de la tabla de la fase
correspondiente.

```
| <fecha> | <marca y modelo> | <versión de Android / API> | <WhatsApp o WhatsApp Business y versión> | <qué se probó> | <resultado> |
```

## Fase 0 — validación del ContentProvider

Objetivo: confirmar que WhatsApp acepta un pack publicado por el
`ContentProvider` de esta app y que sus stickers quedan usables en un chat
real. Contexto y decisión en ADR-0004.

| Fecha | Dispositivo | Android | WhatsApp | Qué se probó | Resultado |
|---|---|---|---|---|---|
| 2026-09-20 | `<marca y modelo>` | `<versión de Android / API>` | `<WhatsApp o WhatsApp Business y versión>` | Botón "Añadir pack a WhatsApp" → confirmación en WhatsApp → bandeja de stickers en un chat | WhatsApp mostró el pack semilla y los 3 stickers aparecieron en la bandeja del chat. Fase 0 cerrada. |

## Fase 1 — codificador

Objetivo: dado un conjunto de bitmaps, producir un WebP animado de 512×512
que cumpla RF-10 (≤500 KB), RF-12 (ajuste automático) y RF-13 (fotograma
≥8 ms, animación ≤10 s). Contexto y decisión en ADR-0005.

Estado al 2026-09-20: `./gradlew :webp:test` (17 tests, lógica de ajuste de
calidad y fotogramas) y `./gradlew :webp:externalNativeBuildDebug` /
`:webp:assembleDebug` (compila y enlaza contra libwebp para arm64-v8a y
x86_64) pasan en la máquina de desarrollo. `WebpAnimEncoderInstrumentedTest`
(codificación real vía JNI) compila pero no se ha ejecutado todavía: no hubo
`adb devices` disponible en este entorno. Pendiente de correr
`./gradlew :webp:connectedAndroidTest` en un teléfono o emulador real antes
de dar la Fase 1 por probada.

| Fecha | Dispositivo | Android | Qué se probó | Resultado |
|---|---|---|---|---|
| `<fecha>` | `<marca y modelo>` | `<versión de Android / API>` | `./gradlew :webp:connectedAndroidTest` | `<pendiente>` |
