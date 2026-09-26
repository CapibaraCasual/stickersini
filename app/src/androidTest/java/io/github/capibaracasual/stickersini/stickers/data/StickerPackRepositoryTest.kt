package io.github.capibaracasual.stickersini.stickers.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.capibaracasual.stickersini.stickers.domain.SeedPacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ADR-0014: un pack propio por debajo del mínimo de RF-16 es invisible para
 * [StickerPackRepository.getAllPacks] (lo que ve
 * [io.github.capibaracasual.stickersini.provider.StickerContentProvider]/
 * WhatsApp) pero visible en [StickerPackRepository.getAllManagedPacks] (la
 * pantalla de gestión), hasta llegar a 3 stickers. Bytes de sticker
 * arbitrarios (no un `.webp` real): estos tests verifican visibilidad y
 * conteo, no la imagen en sí — `ensureTrayIcon` ya maneja bytes no
 * decodificables sin lanzar (ver su propio KDoc).
 *
 * Cada test borra el pack que crea, para no dejar residuo que rompa el
 * conteo fijo de `StickerContentProviderTest.metadata_devuelveLosDosPacksSemilla`.
 */
@RunWith(AndroidJUnit4::class)
class StickerPackRepositoryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = StickerPackRepository(context)

    private fun dummySticker(tag: Int) = "sticker-$tag".toByteArray()

    @Test
    fun unPackPropioNuevoQuedaOcultoHastaLlegarAlMinimo() {
        val entry = repository.createUserPack("Pack de prueba RF-16", isAnimated = false)
        try {
            assertTrue(repository.getAllManagedPacks().any { it.identifier == entry.identifier })
            assertFalse(repository.getAllPacks().any { it.identifier == entry.identifier })

            repository.addStickerToPack(entry.identifier, isAnimated = false, webpBytes = dummySticker(1), emojis = emptyList(), accessibilityText = "")
            repository.addStickerToPack(entry.identifier, isAnimated = false, webpBytes = dummySticker(2), emojis = emptyList(), accessibilityText = "")
            assertFalse(
                "con 2 stickers todavía no debería ser visible para WhatsApp",
                repository.getAllPacks().any { it.identifier == entry.identifier },
            )

            val afterThird = repository.addStickerToPack(
                entry.identifier, isAnimated = false, webpBytes = dummySticker(3), emojis = emptyList(), accessibilityText = "",
            )
            assertEquals(0, afterThird.missingForMinimum)
            assertTrue(
                "con 3 stickers ya debería ser visible para WhatsApp",
                repository.getAllPacks().any { it.identifier == entry.identifier },
            )
        } finally {
            repository.deleteUserPack(entry.identifier)
        }
    }

    @Test
    fun quitarUnStickerPorDebajoDelMinimoLoVuelveAOcultar() {
        val entry = repository.createUserPack("Pack de prueba quitar", isAnimated = false)
        try {
            repeat(3) { i ->
                repository.addStickerToPack(entry.identifier, isAnimated = false, webpBytes = dummySticker(i), emojis = emptyList(), accessibilityText = "")
            }
            assertTrue(repository.getAllPacks().any { it.identifier == entry.identifier })

            val stickerToRemove = repository.getAllManagedPacks().first { it.identifier == entry.identifier }.stickers.first()
            repository.removeStickerFromUserPack(entry.identifier, isAnimated = false, imageFileName = stickerToRemove.imageFileName)

            assertFalse(
                "al bajar a 2 stickers debería volver a ocultarse",
                repository.getAllPacks().any { it.identifier == entry.identifier },
            )
            assertTrue(repository.getAllManagedPacks().any { it.identifier == entry.identifier })
        } finally {
            repository.deleteUserPack(entry.identifier)
        }
    }

    @Test
    fun renombrarUnPackPropio() {
        val entry = repository.createUserPack("Nombre viejo", isAnimated = true)
        try {
            repository.renameUserPack(entry.identifier, "Nombre nuevo")
            assertEquals("Nombre nuevo", repository.getAllManagedPacks().find { it.identifier == entry.identifier }?.name)
        } finally {
            repository.deleteUserPack(entry.identifier)
        }
    }

    @Test
    fun eliminarUnPackPropioLoSacaDeTodosLados() {
        val entry = repository.createUserPack("Para borrar", isAnimated = false)
        repository.deleteUserPack(entry.identifier)

        assertFalse(repository.getAllManagedPacks().any { it.identifier == entry.identifier })
        assertFalse(repository.getAllPacks().any { it.identifier == entry.identifier })
    }

    @Test
    fun losPacksSemillaSonDeSoloLectura() {
        assertThrows(IllegalArgumentException::class.java) {
            repository.renameUserPack(SeedPacks.STATIC_IDENTIFIER, "otro nombre")
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.deleteUserPack(SeedPacks.STATIC_IDENTIFIER)
        }
    }
}
