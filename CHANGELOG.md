# Changelog

Todos los cambios relevantes de este proyecto se documentan aquí.

El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y
el proyecto usa [versionado semántico](https://semver.org/lang/es/).

## [Sin publicar]

### Añadido
- Estructura inicial del repositorio, requisitos y primeras decisiones
  arquitectónicas (ADR-0001 a ADR-0004).
- Proyecto Gradle del módulo `app` (Kotlin + Compose, compileSdk/targetSdk 36,
  minSdk 26).
- Modelo de dominio de packs y stickers, con validación del mínimo/máximo de
  stickers por pack (RF-16) y de la prohibición de mezclar animados y
  estáticos (RF-18).
- `StickerContentProvider` conforme al contrato WAStickerApps (RF-19), con
  pack semilla de 3 stickers de prueba precargado (RF-17).
- Pantalla para añadir el pack semilla a WhatsApp mediante el intent de
  confirmación (RF-20), con detección de si WhatsApp está instalado (RF-21).

<!--
Categorías disponibles: Añadido, Cambiado, Obsoleto, Eliminado, Corregido,
Seguridad.

Al publicar una versión, mover lo de [Sin publicar] a una sección nueva:

## [0.1.0] - AAAA-MM-DD
-->
