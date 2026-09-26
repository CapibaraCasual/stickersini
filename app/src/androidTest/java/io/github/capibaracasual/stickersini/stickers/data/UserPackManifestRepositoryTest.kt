package io.github.capibaracasual.stickersini.stickers.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sobre almacenamiento real (`InstrumentationRegistry`), sin aislamiento
 * entre corridas: cada test borra lo que crea (`finally`/al final), para no
 * dejar residuo que afecte a otro test de packs (ver
 * `StickerContentProviderTest.metadata_devuelveLosDosPacksSemilla`, que
 * asume exactamente los dos packs semilla).
 */
@RunWith(AndroidJUnit4::class)
class UserPackManifestRepositoryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = UserPackManifestRepository(context)

    @Test
    fun crearRenombrarYEliminarUnPackPropio() {
        val entry = repository.create("Mi pack de prueba", isAnimated = false)
        try {
            assertTrue(repository.getAll().any { it.identifier == entry.identifier && it.name == "Mi pack de prueba" })

            repository.rename(entry.identifier, "Renombrado")
            assertEquals("Renombrado", repository.getAll().find { it.identifier == entry.identifier }?.name)
        } finally {
            repository.delete(entry.identifier)
        }

        assertNull(repository.getAll().find { it.identifier == entry.identifier })
    }

    @Test
    fun dosPacksNuevosNoSePisanEntreSi() {
        val first = repository.create("Uno", isAnimated = false)
        val second = repository.create("Dos", isAnimated = true)
        try {
            val all = repository.getAll()
            assertTrue(all.any { it.identifier == first.identifier && !it.isAnimated })
            assertTrue(all.any { it.identifier == second.identifier && it.isAnimated })
        } finally {
            repository.delete(first.identifier)
            repository.delete(second.identifier)
        }
    }
}
