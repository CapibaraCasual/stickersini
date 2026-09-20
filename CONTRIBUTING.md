# Cómo contribuir

Gracias por el interés. El proyecto es pequeño y lo mantiene una sola persona,
así que estas reglas son cortas a propósito.

## Antes de escribir código

Abre un issue describiendo el problema o la idea. Si ya existe uno parecido,
comenta ahí. Esto evita trabajo duplicado y conversaciones sobre pull requests
que no van a entrar.

## Reportar un error

Incluye:

- Modelo del dispositivo y versión de Android
- Versión de la aplicación
- Qué hiciste, qué esperabas y qué pasó
- Si aplica, el archivo de origen o una descripción de él

## Pull requests

- Una cosa por pull request. Si arreglas un error y de paso reorganizas
  carpetas, son dos.
- Enlaza el issue correspondiente.
- Commits pequeños y con mensaje descriptivo, en español o inglés, pero
  consistente dentro del PR.
- Si el cambio afecta a una decisión de arquitectura, propón un ADR nuevo en
  `decisions/`. No edites un ADR ya aceptado: se reemplaza, no se corrige.

## Alcance

Antes de proponer una función, revisa
[`docs/desarrollo/requisitos.md`](docs/desarrollo/requisitos.md). La sección de
alcance dice qué queda deliberadamente fuera. Las propuestas que contradigan
RNF-01 o RNF-02 (sin red, sin anuncios) no se van a aceptar: son la razón de
ser del proyecto.

## Licencia

Al contribuir aceptas que tu aportación se distribuya bajo GPL-3.0, igual que
el resto del proyecto.
