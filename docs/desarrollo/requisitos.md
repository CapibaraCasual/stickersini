# Requisitos

**Proyecto:** Stickersini
**Versión del documento:** 1.1 · **Fecha:** 2026-09-24

Este documento es la referencia de qué debe hacer el sistema. No describe
*cómo* se implementa: eso vive en `arquitectura.md` y en `decisions/`.

---

## Alcance

Aplicación Android que convierte contenido visual del propio dispositivo en
stickers de WhatsApp, animados o estáticos, sin salir del teléfono.

**Dentro del alcance:** captura de pantalla en vivo, importación de video,
importación de imágenes, edición mínima (recorte temporal y de área),
codificación a WebP, gestión de packs, entrega a WhatsApp.

**Fuera del alcance:** cuentas de usuario, catálogo o comunidad de stickers,
sincronización entre dispositivos, edición avanzada (filtros, capas, texto
enriquecido), soporte a mensajeros distintos de WhatsApp.

---

## Requisitos funcionales

### Entrada de contenido

| ID | Requisito |
|---|---|
| **RF-01** | El sistema debe capturar la pantalla del dispositivo mediante `MediaProjection`, previa autorización explícita del usuario en cada sesión. |
| **RF-02** | El sistema debe permitir importar un archivo de video existente desde el almacenamiento del dispositivo. |
| **RF-03** | El sistema debe permitir importar una imagen o captura de pantalla existente desde el almacenamiento del dispositivo. |
| **RF-04** | El sistema debe permitir capturar un fotograma desde la cámara del dispositivo. |
| **RF-05** | El sistema debe aceptar contenido compartido desde otras aplicaciones mediante `ACTION_SEND` (imagen o video). |

### Edición

| ID | Requisito |
|---|---|
| **RF-06** | El sistema debe permitir seleccionar un tramo temporal del video de origen, con duración máxima de 10 segundos. |
| **RF-07** | El sistema debe permitir seleccionar y reencuadrar el área cuadrada del contenido que se convertirá en sticker. |
| **RF-08** | El sistema debe permitir eliminar el fondo del contenido o dejarlo opaco, a elección del usuario. |
| **RF-09** | El sistema debe mostrar una vista previa del sticker resultante antes de guardarlo. |

### Generación

| ID | Requisito |
|---|---|
| **RF-10** | El sistema debe generar stickers animados en formato WebP de 512×512 píxeles exactos, con tamaño de archivo menor o igual a 500 KB. |
| **RF-11** | El sistema debe generar stickers estáticos en formato WebP de 512×512 píxeles exactos, con tamaño de archivo menor o igual a 100 KB. |
| **RF-12** | El sistema debe ajustar automáticamente la calidad y la tasa de fotogramas hasta cumplir los límites de RF-10 y RF-11, informando al usuario si no es posible. |
| **RF-13** | Cada fotograma de un sticker animado debe tener una duración mínima de 8 milisegundos y la animación completa no debe superar los 10 segundos. |
| **RF-14** | El sistema debe generar un ícono de bandeja en formato PNG de 96×96 píxeles por cada pack. |

### Packs

| ID | Requisito |
|---|---|
| **RF-15** | El sistema debe permitir crear, renombrar y eliminar packs de stickers. |
| **RF-16** | El sistema debe permitir añadir y quitar stickers de un pack, respetando el mínimo de 3 y el máximo de 30 por pack. |
| **RF-17** | El sistema debe crear un pack inicial precargado con 3 stickers propios durante la primera ejecución, de modo que el primer sticker del usuario se añada a un pack ya válido. |
| **RF-18** | El sistema debe impedir mezclar stickers animados y estáticos dentro de un mismo pack. |

### Entrega a WhatsApp

| ID | Requisito |
|---|---|
| **RF-19** | El sistema debe exponer sus packs a WhatsApp mediante un `ContentProvider` que cumpla el contrato de WAStickerApps. |
| **RF-20** | El sistema debe lanzar el intent de confirmación que permite al usuario añadir un pack a WhatsApp. |
| **RF-21** | El sistema debe detectar si WhatsApp está instalado e informar al usuario cuando no lo esté. |
| **RF-22** | El sistema debe notificar a WhatsApp cuando el contenido de un pack ya añadido haya cambiado. |

### Acceso rápido

| ID | Requisito |
|---|---|
| **RF-23** | El sistema debe ofrecer una burbuja flotante opcional que permita iniciar y detener la captura de pantalla sin volver a la aplicación. |
| **RF-24** | La burbuja flotante debe poder desactivarse por completo desde los ajustes. |

---

## Requisitos no funcionales

### Privacidad

| ID | Requisito |
|---|---|
| **RNF-01** | Todo el procesamiento debe ocurrir en el dispositivo. La aplicación no debe realizar ninguna conexión de red, no debe requerir cuenta de usuario y no debe subir contenido a ningún servidor. |
| **RNF-02** | La aplicación no debe incluir anuncios, SDK de publicidad ni telemetría de terceros. |
| **RNF-03** | La aplicación debe respetar `FLAG_SECURE` de otras aplicaciones y no intentar eludirlo. |

### Plataforma

| ID | Requisito |
|---|---|
| **RNF-04** | `targetSdk` 36 (Android 16), conforme al requisito de Google Play vigente desde el 31 de agosto de 2026. |
| **RNF-05** | `minSdk` 26 (Android 8.0). La captura de pantalla puede degradarse en versiones antiguas; las demás funciones deben operar completas. |
| **RNF-06** | La aplicación debe declarar los tipos de servicio en primer plano exigidos para `mediaProjection`, tanto en el manifiesto como en Play Console. |

### Rendimiento y tamaño

| ID | Requisito |
|---|---|
| **RNF-07** | El AAB por ABI debe pesar menos de 15 MB. |
| **RNF-08** | La conversión de un clip de **hasta 5 segundos** de contenido representativo (grabaciones de pantalla, video de cámara) debe completarse en menos de 5 segundos en un dispositivo de gama media. Para clips **más largos** que eso, o de contenido de alta complejidad visual, la conversión debe terminar en menos de 20 segundos, mostrando progreso, aunque el resultado tenga menos fotogramas. |
| **RNF-09** | La aplicación no debe requerir más de 200 MB de almacenamiento para datos propios en uso normal. |

### Licenciamiento

| ID | Requisito |
|---|---|
| **RNF-10** | Todo el código propio se distribuye bajo GPL-3.0. Las dependencias deben ser compatibles con esa licencia. |
| **RNF-11** | Los avisos de licencia de las bibliotecas de terceros deben estar visibles dentro de la aplicación. |

---

## Trazabilidad

| Requisito | Alimenta |
|---|---|
| RNF-01 | Política de privacidad · justificación de permisos en Play Console · argumento principal de la ficha de la tienda |
| RNF-02 | Argumento de la ficha · razón de la licencia GPL-3.0 (ADR-0003) |
| RF-17 | ADR-0004 — resuelve el mínimo de 3 stickers por pack |
| RF-10, RF-11 | ADR-0002 — motiva la elección del codificador |
| RNF-07 | ADR-0002 — descarta FFmpeg por peso |
