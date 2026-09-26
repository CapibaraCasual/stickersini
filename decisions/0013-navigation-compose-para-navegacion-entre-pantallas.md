# ADR-0013: Navigation Compose para la navegación entre pantallas

- Estado: Aceptado e implementado
- Fecha: 2026-09-26

## Contexto

Hasta ahora la app tenía dos pantallas y la navegación entre ellas era un
`var showCreateScreen by remember { mutableStateOf(false) }` en
`MainActivity` (un booleano conmutado a mano). Con el resto de la interfaz
de Fase 3 (selector de tramo RF-06, recorte con pellizco RF-07, gestión de
packs RF-15), el flujo de creación pasa a tener varios pasos propios
(elegir archivo → tramo → recorte → conversión/vista previa/guardado) más
una sección de packs separada (lista → detalle) — cinco o más destinos en
total, varios de ellos con su propia noción de "volver" (de recorte a
tramo sin perder el archivo elegido, por ejemplo). Seguir agregando
booleanos/enums a mano para esto multiplica el código de propósito general
(pila de retroceso, argumentos entre pasos) sin que el proyecto gane nada
por escribirlo de nuevo.

## Decisión

**Usar `androidx.navigation:navigation-compose` (2.9.8, Apache-2.0) como
mecanismo de navegación entre pantallas.** Licencia compatible con GPL-3.0
(RNF-10): es una biblioteca de AndroidX, mismo tratamiento que el resto de
`androidx.*` ya presentes en el proyecto, sin dependencia de red ni de
Play Services.

Versión 2.9.8, no la 2.10.x más nueva: 2.10.2 (la última al momento de
escribir esto) exige `compileSdk` 37 o más — `checkDebugAarMetadata` lo
rechaza, y `compileSdk` sigue fijado en 36 a propósito (ver el comentario
en `gradle/libs.versions.toml`, misma razón que ya aplicaba a
`composeBom`/`coreKtx`/`activityCompose`). 2.9.8 es la última de la serie
2.9.x, que sí compila contra `android-36`. Subir a la serie 2.10.x queda
para la misma tanda en que suba `compileSdk` a 37, no antes.

Rutas como `String` simples (`"home"`, `"create/pick"`, `"create/trim"`,
`"create/convert"`, y las de packs cuando existan), no destinos
`@Serializable` tipados: evita sumar `kotlinx-serialization` como
dependencia nueva solo para esto, y a esta escala (un puñado de rutas fijas,
sin deep links) el tipado extra no paga su complejidad.

El estado que un paso necesita pasarle al siguiente dentro de un mismo
flujo (por ejemplo, el `Uri` elegido y el tramo confirmado en
"create/*") no viaja serializado como argumento de ruta — un `Uri` no es
un tipo primitivo cómodo para eso. Vive en un objeto de estado simple
(`mutableStateOf` por campo) creado con `remember` en el mismo nivel que el
`NavHost`, por encima de la pila de navegación: sobrevive a navegar entre
los pasos de ese flujo, se descarta al volver a "home". Sin ViewModel ni
Hilt: el proyecto no adoptó todavía una capa de inyección de dependencias
(ver la nota de "Otras elecciones razonables" en `CLAUDE.md`), y este
estado es lo bastante chico como para no justificar agregarla solo por
esto.

## Consecuencias

- `MainActivity` pasa a ser solo el punto de entrada de Compose
  (`setContent` + tema): la composición de rutas vive en
  `ui/StickersiniNavHost.kt`.
- Cada pantalla es una función `@Composable` de nivel de archivo (no
  `private` dentro de `MainActivity.kt` como antes), porque el `NavHost`
  vive en un archivo distinto y necesita poder llamarlas.
- El retroceso del sistema (botón atrás de Android) ya hace lo correcto
  automáticamente sin código propio: `NavHost` mantiene la pila.
