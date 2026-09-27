package io.github.capibaracasual.stickersini.stickers.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedStickerPackTest {

    private fun sticker(name: String) = Sticker(imageFileName = name, isAnimated = false)

    private fun pack(stickerCount: Int, confirmedStickerCount: Int?) = ManagedStickerPack(
        identifier = "pack",
        name = "Pack",
        isAnimated = false,
        stickers = (1..stickerCount).map { sticker("sticker_$it.webp") },
        isSeedPack = false,
        confirmedStickerCount = confirmedStickerCount,
    )

    @Test
    fun `whatsAppStatus es NeverConfirmed sin ninguna confirmacion previa`() {
        assertEquals(WhatsAppStatus.NeverConfirmed, pack(stickerCount = 3, confirmedStickerCount = null).whatsAppStatus)
    }

    @Test
    fun `whatsAppStatus es UpToDate cuando la cantidad no cambio desde la confirmacion`() {
        assertEquals(WhatsAppStatus.UpToDate, pack(stickerCount = 3, confirmedStickerCount = 3).whatsAppStatus)
    }

    @Test
    fun `whatsAppStatus es OutOfDate si se agrego un sticker despues de confirmar`() {
        assertEquals(WhatsAppStatus.OutOfDate, pack(stickerCount = 4, confirmedStickerCount = 3).whatsAppStatus)
    }

    @Test
    fun `whatsAppStatus es OutOfDate si se quito un sticker despues de confirmar`() {
        assertEquals(WhatsAppStatus.OutOfDate, pack(stickerCount = 2, confirmedStickerCount = 3).whatsAppStatus)
    }
}
