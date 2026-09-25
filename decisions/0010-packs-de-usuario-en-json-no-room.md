# ADR-0010: Los packs de usuario se guardan como JSON + archivos, no con Room

- Estado: Aceptado
- Fecha: 2026-09-24

## Contexto

La Fase 3 necesita guardar los packs que arma el propio usuario (no el pack
semilla), para poder mostrarlos en una vista previa y servirlos luego al
`StickerContentProvider`. Hasta ahora no existía ningún pack editable: el
único que hay (`sticker_pack_semilla`) vive en `assets/contents.json` +
`assets/<identifier>/<archivo>`, es de solo lectura, y lo lee
`StickerPackAssetRepository` con `org.json`.

CLAUDE.md dejaba anotado "Room para persistencia" como elección razonable
por defecto, pero esa línea se escribió al arrancar el proyecto, sin este
caso concreto delante. Toca evaluarlo contra la escala y el patrón de
acceso reales, no darlo por bueno.

La escala real: un puñado de packs, entre 3 y 30 stickers cada uno (RF-16,
límite duro), un total de unas pocas decenas de registros para un único
usuario local. El patrón de acceso real: "todos los packs" y "un pack por
identificador" — exactamente las dos operaciones que
`StickerPackAssetRepository` ya resuelve hoy cargando todo a memoria y
haciendo `find` sobre una lista. Ningún requisito (RF-15 a RF-22) pide
búsqueda, filtrado, orden ni relaciones entre packs.

## Opciones consideradas

1. **Room.** Da DAO generado, migraciones versionadas, verificación de SQL en
   tiempo de compilación y `Flow` reactivo. Todo eso paga su costo cuando hay
   muchos registros o consultas no triviales; aquí no hay ninguna de las dos
   cosas. Habría que mantener un esquema y sus migraciones para una forma
   (pack → stickers → emojis) que ya vive, sin fricción, como el JSON anidado
   que usa el pack semilla. Suma una dependencia y un procesador de
   anotaciones (KSP) al build por una capacidad que la app no ejercita.
   Descartada: no por licencia (Apache-2.0, compatible con RNF-10), sino
   porque no hay evidencia de que la necesite este volumen de datos.
2. **SQLite plano (`SQLiteOpenHelper`, sin Room).** Evita el codegen, pero
   sigue exigiendo escribir a mano el versionado del esquema, las
   migraciones y el mapeo de columnas para una forma relacional que no gana
   nada de serlo: ninguna consulta se beneficia de índices ni de `JOIN`
   cuando las dos únicas operaciones son "todo" y "por id", ya resueltas en
   memoria. Descartada por la misma razón que Room, con menos beneficio
   todavía (sin generación de código ni tipado de las consultas).
3. **DataStore (Preferences o Proto).** DataStore de Preferences no tipa una
   lista anidada de packs y stickers: modelarlo ahí significaría serializar
   el mismo JSON dentro de una sola clave, sumando una dependencia sin
   ninguna ganancia estructural. DataStore de Proto sí tipa la forma
   correctamente, pero exige escribir y mantener un esquema `.proto` y su
   codegen — el mismo tipo de costo que Room, para un contenedor que esta
   app no necesita: no hay lecturas parciales ni actualizaciones reactivas
   más allá de "recargar todo después de escribir", el mismo patrón que ya
   usa el pack semilla. Descartada.
4. **JSON en almacenamiento interno + archivos, mismo esquema que el pack
   semilla — elegida.**

## Decisión

Los packs de usuario se guardan en `filesDir/packs/packs.json`, con la
**misma forma** que ya usa `assets/contents.json` (mismas claves:
`identifier`, `name`, `stickers`, `emojis`, etc.), y cada pack tiene su
propio directorio `filesDir/packs/<identifier>/` con los `.webp` de sus
stickers y su ícono de bandeja — el mismo esquema de rutas que
`assets/<identifier>/` usa para el pack semilla.

Consecuencias directas de esa simetría:

- El parseo de JSON a `StickerPack`/`Sticker` que ya existe en
  `StickerPackAssetRepository` se extrae a una función compartida; lo único
  nuevo es el lado de escritura (serializar `StickerPack` a JSON) y la
  ubicación del archivo (`filesDir` en vez de `assets`), no un formato
  distinto que aprender ni migrar.
- Un nuevo repositorio de packs de usuario (lectura + escritura) convive con
  `StickerPackAssetRepository` (lectura, sin cambios) detrás de una fachada
  que concatena ambas listas. `StickerContentProvider` deja de lanzar
  `UnsupportedOperationException` sin condición en `insert`/`update`/`delete`
  — eso se mantiene solo para el identificador del pack semilla.
- Cada escritura es "leer el archivo entero, modificar, volver a escribirlo
  entero", con escritura atómica (archivo temporal + rename) para no dejar
  `packs.json` corrupto si el proceso muere a mitad de la escritura. A esta
  escala (unos pocos KB, escrituras poco frecuentes) es correcto; dejaría de
  serlo si el número de packs o stickers creciera en órdenes de magnitud, algo
  que el tope de 30 stickers por pack de RF-16 ya impide dentro de un pack, y
  ningún requisito en alcance crea muchos packs automáticamente.

## Consecuencias

- Sin dependencia nueva, sin migraciones que escribir, sin procesador de
  anotaciones agregado al build.
- `StickerPack` y `Sticker` (el modelo de dominio) no cambian: esta Decisión
  toca solo la capa de datos.
- RF-22 (notificar a WhatsApp cuando cambia el contenido de un pack) sigue
  sin resolver — esta Decisión no lo resuelve, solo dejar el almacenamiento
  listo para que se implemente sobre él.
- Esta Decisión no es "nunca Room": si en el futuro aparece una necesidad real
  de consulta (buscar por emoji, paginar cientos de packs), migrar es
  cambiar solo la implementación del repositorio — el modelo de dominio y el
  contrato del `ContentProvider` quedan iguales. Hasta entonces, no hay
  evidencia de que este proyecto la necesite.
