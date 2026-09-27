package io.github.capibaracasual.stickersini.stickers.domain

/**
 * Un pack tal como está, válido para WhatsApp o no todavía — a diferencia de
 * [StickerPack], que solo puede existir si ya cumple RF-16 (3 a 30
 * stickers). Es lo que ve la pantalla de gestión de packs (RF-15): un pack
 * propio recién creado, o uno al que se le quitó un sticker y quedó por
 * debajo del mínimo, sigue siendo un [ManagedStickerPack] visible ahí,
 * aunque no llegue a ser un [StickerPack] válido para
 * [io.github.capibaracasual.stickersini.provider.StickerContentProvider]
 * (ver ADR-0014).
 */
data class ManagedStickerPack(
    val identifier: String,
    val name: String,
    val isAnimated: Boolean,
    val stickers: List<Sticker>,
    /** `true` para los dos packs semilla (ADR-0004): de solo lectura en la pantalla de gestión (ADR-0014). */
    val isSeedPack: Boolean,
    /**
     * Cuántos stickers tenía este pack la última vez que el usuario confirmó
     * agregarlo a WhatsApp (RF-20) — `null` si nunca lo confirmó. Es el
     * `image_data_version` que ese momento le mandó a WhatsApp (ver
     * [io.github.capibaracasual.stickersini.provider.StickerContentProvider]),
     * guardado en la propia app porque WhatsApp no expone ninguna forma de
     * volver a preguntárselo. Comparado contra `stickers.size` de hoy es lo
     * único que arma [whatsAppStatus].
     */
    val confirmedStickerCount: Int?,
) {
    /** Cuántos stickers faltan para llegar al mínimo de RF-16; `0` si ya lo cumple. */
    val missingForMinimum: Int
        get() = (StickerPack.STICKERS_MIN_PER_PACK - stickers.size).coerceAtLeast(0)

    /**
     * Qué sabe la *app* sobre este pack en WhatsApp — no qué sabe WhatsApp.
     * El contrato WAStickerApps no tiene una consulta para "¿este pack sigue
     * instalado?": si el usuario desinstaló WhatsApp o quitó el pack desde
     * ahí, esto sigue diciendo [UpToDate] igual, porque la app no tiene
     * forma de enterarse. [UpToDate] significa únicamente "el usuario
     * confirmó agregarlo y no le cambiamos la cantidad de stickers desde
     * entonces", no "sigue ahí".
     */
    val whatsAppStatus: WhatsAppStatus
        get() = when (confirmedStickerCount) {
            null -> WhatsAppStatus.NeverConfirmed
            stickers.size -> WhatsAppStatus.UpToDate
            else -> WhatsAppStatus.OutOfDate
        }
}

enum class WhatsAppStatus {
    /** Nunca se confirmó agregar este pack a WhatsApp. */
    NeverConfirmed,

    /** Se confirmó, y la cantidad de stickers no cambió desde entonces. */
    UpToDate,

    /** Se confirmó alguna vez, pero después se le agregó o quitó un sticker: WhatsApp puede tener cacheada una versión vieja. */
    OutOfDate,
}
