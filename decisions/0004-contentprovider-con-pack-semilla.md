# ADR-0004: Entregar los stickers por ContentProvider, con un pack semilla

- Estado: Aceptado
- Fecha: 2026-09-19

## Contexto

El objetivo declarado del producto era que el usuario capturara algo y lo
tuviera al instante disponible como sticker en el chat. Al revisar cómo WhatsApp
acepta stickers de terceros, ese flujo resultó no ser posible.

WhatsApp no acepta stickers sueltos. Solo reconoce *packs*, publicados por la
aplicación de origen a través de un `ContentProvider` que cumple el contrato de
WAStickerApps, y el usuario debe confirmar explícitamente que quiere añadir el
pack mediante un intent hacia una actividad de WhatsApp.

Además, un pack solo es válido con un mínimo de 3 stickers y un máximo de 30.

Esto se descubrió antes de escribir código, al validar el supuesto central del
producto.

## Opciones consideradas

1. **Enviar el archivo `.webp` con `ACTION_SEND` a WhatsApp** — descartada: no
   funciona. El archivo llega como imagen normal, no como sticker. WhatsApp solo
   renderiza como sticker los mensajes construidos con ese tipo en su protocolo,
   al que una aplicación externa no tiene acceso. Era el camino obvio y
   simplemente no existe.
2. **`ContentProvider` con packs creados bajo demanda** — descartada: el primer
   sticker que el usuario cree deja el pack con un solo elemento, por debajo del
   mínimo de 3. WhatsApp lo rechaza. El usuario concluye que la aplicación está
   rota en su primer uso, que es el peor momento posible para eso.
3. **`ContentProvider` con un pack semilla precargado** — elegida.

## Decisión

Publicar los packs mediante un `ContentProvider` conforme a WAStickerApps, y
crear en la primera ejecución un pack inicial que ya contiene 3 stickers propios
de la aplicación (RF-17).

De ese modo el pack es válido desde antes de que el usuario cree nada, y su
primer sticker se añade a una estructura que WhatsApp ya acepta.

## Consecuencias

- La promesa del producto cambia. El flujo real es: capturar, convertir, añadir
  al pack, confirmar en WhatsApp, y el sticker queda en la bandeja para usarse
  en cualquier chat. Eso es lo que debe comunicar la ficha de la tienda y el
  onboarding. No se va a disimular.
- Hay que diseñar y producir 3 stickers propios. Son también material de marca y
  aparecen en las capturas de la tienda, así que el costo se aprovecha.
- La confirmación del pack ocurre una sola vez. A partir de ahí, los stickers
  nuevos aparecen actualizando el pack existente (RF-22), sin volver a pasar por
  el diálogo.
- Los packs no pueden mezclar stickers animados y estáticos (RF-18), lo que
  obliga a gestionar al menos dos packs por defecto.
- El `ContentProvider` es el punto único de fallo del producto. Se valida antes
  que cualquier otra cosa, con stickers hechos a mano, antes de construir el
  editor o la burbuja.
