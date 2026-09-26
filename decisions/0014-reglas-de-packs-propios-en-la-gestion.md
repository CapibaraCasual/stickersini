# ADR-0014: Reglas de packs propios en la pantalla de gestión (RF-15)

- Estado: Aceptado
- Fecha: 2026-09-26

## Contexto

Hasta ahora el único pack "propio" del usuario era, en realidad, uno de los
dos packs semilla (ADR-0004) con stickers añadidos encima (ADR-0010):
siempre nace con 3 stickers, nunca hay que decidir qué pasa con un pack
inválido. RF-15 agrega packs enteramente creados por el usuario — con
nombre propio, y sin ninguna base de `assets/` — y eso obliga a decidir tres
cosas que ADR-0004 no cubre, porque en ese momento no existía el concepto de
un pack fuera del semilla.

## Decisión

**1. Un pack propio por debajo del mínimo de RF-16 (3 stickers) —incluido uno
recién creado, con 0— es invisible para `StickerContentProvider`/WhatsApp,
hasta llegar a 3.** Sigue visible en la pantalla de gestión de la app, con un
indicador de cuántos faltan.

`StickerPack.create` ya rechaza con una excepción cualquier lista de menos
de 3 stickers (RF-16, fijado y testeado en `StickerPackTest`) — invariante
correcta para lo que entrega el `ContentProvider` a WhatsApp, que no debe
relajarse solo para poder representar un pack a medio construir. La
alternativa (bajar el mínimo de `StickerPack` a 0 y validar en otro lado) le
quita a ese tipo la garantía de "si existe un `StickerPack`, es válido para
WhatsApp", que hoy vale en todo el código que lo consume
(`StickerContentProvider`, `WhatsAppStickerIntent`). En cambio, un tipo nuevo
y más permisivo, `ManagedStickerPack` (sin esa invariante), es lo que ve la
pantalla de gestión; `StickerPackRepository.getAllPacks()` (el lado que
alimenta a WhatsApp) sigue devolviendo solo los que llegan al mínimo,
exactamente el mismo criterio que ya aplican, de hecho, los dos packs
semilla (que nunca bajan de 3 porque ADR-0004 se aseguró de eso desde el
principio).

**2. Eliminar un pack propio no lo retira de WhatsApp si ya se había
confirmado ahí.** El contrato WAStickerApps (RF-19) es unidireccional: existe
`ENABLE_STICKER_PACK` para que el usuario confirme agregar un pack, pero
ninguna acción equivalente para pedirle a WhatsApp que lo quite — WhatsApp
cachea el contenido que ya aceptó. Borrar el pack de este lado hace que
`StickerContentProvider` deje de ofrecerlo (no vuelve a aparecer en
`/metadata`), pero no puede alcanzar la copia que WhatsApp ya guardó. La
pantalla de eliminar debe decirlo explícitamente antes de borrar, no fingir
que el borrado es simétrico — evita que alguien piense que la app rompió
algo del lado de WhatsApp cuando en realidad ese pack solo quedó huérfano
ahí (el usuario puede quitarlo a mano desde los ajustes de stickers de
WhatsApp).

**3. Los dos packs semilla quedan de solo lectura en la pantalla de
gestión**: sin opción de renombrarlos, eliminarlos, ni quitarles ningún
sticker — ni siquiera uno que el usuario les haya agregado después. Agregar
un sticker nuevo a un pack semilla sigue funcionando igual que antes desde
el flujo de creación; lo único que cambia es que la pantalla de gestión no
ofrece tocarlos en sentido contrario. Simplifica la interfaz (una sola regla
por tipo de pack, no una excepción por acción) y es consistente con que
ADR-0004 los definió como permanentes.

## Consecuencias

- `StickerPackRepository` pasa a construir dos formas del mismo dato:
  `ManagedStickerPack` (todos los packs, válidos o no, para la pantalla de
  gestión) y `StickerPack` (solo los que llegan a 3, para
  `StickerContentProvider`) — un solo punto de construcción interno evita
  repetir la lógica de "base de `assets/` + extras de `filesDir`" en cada uno
  por separado.
- Ningún cambio en `StickerContentProvider`: sigue sirviendo únicamente
  `StickerPack`, sin enterarse de que existen packs por debajo del mínimo.
- Esta Decisión no reemplaza ni contradice ADR-0004 (que sigue rigiendo el
  caso del pack semilla en particular): lo extiende al caso nuevo que RF-15
  introduce.
