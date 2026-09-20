package io.github.capibaracasual.stickersini.stickers.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerPackTest {

    private fun sticker(name: String, animated: Boolean = false) =
        Sticker(imageFileName = name, isAnimated = animated)

    private fun stickers(count: Int, animated: Boolean = false) =
        (1..count).map { sticker("sticker_$it.webp", animated) }

    @Test
    fun `create acepta un pack con el minimo de 3 stickers`() {
        val pack = StickerPack.create(
            identifier = "pack",
            name = "Pack",
            publisher = "Stickersini",
            trayImageFileName = "tray.png",
            stickers = stickers(3),
        )
        assertEquals(3, pack.stickers.size)
    }

    @Test
    fun `create acepta un pack con el maximo de 30 stickers`() {
        val pack = StickerPack.create(
            identifier = "pack",
            name = "Pack",
            publisher = "Stickersini",
            trayImageFileName = "tray.png",
            stickers = stickers(30),
        )
        assertEquals(30, pack.stickers.size)
    }

    @Test
    fun `create rechaza RF-16 un pack con menos de 3 stickers`() {
        assertThrows(IllegalArgumentException::class.java) {
            StickerPack.create(
                identifier = "pack",
                name = "Pack",
                publisher = "Stickersini",
                trayImageFileName = "tray.png",
                stickers = stickers(2),
            )
        }
    }

    @Test
    fun `create rechaza RF-16 un pack con mas de 30 stickers`() {
        assertThrows(IllegalArgumentException::class.java) {
            StickerPack.create(
                identifier = "pack",
                name = "Pack",
                publisher = "Stickersini",
                trayImageFileName = "tray.png",
                stickers = stickers(31),
            )
        }
    }

    @Test
    fun `create rechaza RF-18 un pack que mezcla animados y estaticos`() {
        val mixed = stickers(2, animated = true) + stickers(1, animated = false)
        assertThrows(IllegalArgumentException::class.java) {
            StickerPack.create(
                identifier = "pack",
                name = "Pack",
                publisher = "Stickersini",
                trayImageFileName = "tray.png",
                stickers = mixed,
            )
        }
    }

    @Test
    fun `create acepta un pack totalmente animado`() {
        val pack = StickerPack.create(
            identifier = "pack",
            name = "Pack",
            publisher = "Stickersini",
            trayImageFileName = "tray.png",
            stickers = stickers(3, animated = true),
        )
        assertTrue(pack.isAnimatedPack)
    }
}
