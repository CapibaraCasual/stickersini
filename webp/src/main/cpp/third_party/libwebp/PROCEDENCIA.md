# Procedencia de este código

Este directorio es una copia vendorizada de libwebp, no un submódulo de git
ni una dependencia declarada. Motivo completo en
[ADR-0005](../../../../../../../decisions/0005-libwebp-vendorizado-no-submodulo-ni-binding.md).

| Campo | Valor |
|---|---|
| Proyecto de origen | https://github.com/webmproject/libwebp |
| Versión | v1.6.0 |
| Commit exacto | `4fa21912338357f89e4fd51cf2368325b59e9bd9` |
| Fecha de esa release | 2025-06-30 |
| Fecha de la copia a este repo | 2026-09-20 |
| Licencia | BSD-3-Clause (`COPYING`), más `PATENTS` (concesión de patentes de Google) |

## Qué se copió y qué no

Copiado: `src/`, `sharpyuv/`, `cmake/`, `CMakeLists.txt`, `COPYING`,
`PATENTS`, `AUTHORS`, `configure.ac`.

Omitido a propósito: `examples/`, `imageio/`, `tests/`, `doc/`, `man/`,
`swig/`, `webp_js/`, `extras/`, y el resto del build de Autotools/iOS/Gradle
a nivel de raíz (`autogen.sh`, `m4/`, `iosbuild.sh`, `xcframeworkbuild.sh`,
`gradlew`, `build.gradle`, `gradle/`, y el `Makefile.am` de la raíz). Los
`Makefile.am` **internos** de `src/*` y de `sharpyuv/` sí viajan: el
`CMakeLists.txt` de libwebp los lee para saber qué compilar, aunque este
proyecto no use Autotools.

`configure.ac` es el mismo caso: parece puramente de Autotools y se excluyó
en la primera vendorización, pero `cmake/deps.cmake` lo lee con
`file(READ ...)` para sacar el número de versión por regex. Sin él, el
configure de CMake falla (`file failed to open for reading`). Se detectó
al correr el build real, no al leer el `CMakeLists.txt` de antemano.

Nada de esto se modificó a mano. Si algún día hace falta un parche local,
anotarlo aquí y guardarlo como un `.patch` aparte, no editando el código
vendorizado directamente sin dejar rastro.

## Cómo actualizar

Ver la sección "Mantenimiento" de ADR-0005. Resumen: reemplazar el árbol
completo por el del tag nuevo (no editar archivo por archivo), actualizar
esta tabla, recompilar y volver a correr los tests instrumentados del
módulo `webp` antes de dar la actualización por buena.
