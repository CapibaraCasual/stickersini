package io.github.capibaracasual.stickersini.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Casos de esquina conocidos, no solo la álgebra de [displayedCropToNormalized]
 * repetida en el test: cada expectativa sale de rastrear a mano qué esquina
 * del contenido de origen (antes de rotar) termina en qué esquina del
 * contenido mostrado (después de rotar `rotationDegrees` grados en sentido
 * horario), el mismo método que ya usa `ImageFrameDecoderTest` para
 * verificar la corrección EXIF con una marca visual. `sourceWidth`
 * (300) ≠ `sourceHeight` (200) a propósito: un caso simétrico no
 * distinguiría un error de signo de uno correcto.
 */
class DisplayedCropTest {

    private val sourceWidth = 300f
    private val sourceHeight = 200f
    private val size = 50f

    private fun normalizedOf(sourceX: Float, sourceY: Float): NormalizedCrop {
        val maxSize = minOf(sourceWidth, sourceHeight)
        return NormalizedCrop(
            xFraction = sourceX / (sourceWidth - size),
            yFraction = sourceY / (sourceHeight - size),
            sizeFraction = size / maxSize,
        )
    }

    @Test
    fun sinRotacionElRecorteMostradoEsElDeOrigen() {
        val result = displayedCropToNormalized(
            displayedX = 30f, displayedY = 20f, displayedSize = size,
            sourceWidth = sourceWidth, sourceHeight = sourceHeight, rotationDegrees = 0,
        )
        assertEquals(normalizedOf(sourceX = 30f, sourceY = 20f), result)
    }

    @Test
    fun rotacion90_ElExtremoSuperiorDerechoMostradoEsElSuperiorIzquierdoDeOrigen() {
        // Mostrado: 200(ancho)x300(alto) — dimensiones de origen invertidas.
        val result = displayedCropToNormalized(
            displayedX = 200f - size, displayedY = 0f, displayedSize = size,
            sourceWidth = sourceWidth, sourceHeight = sourceHeight, rotationDegrees = 90,
        )
        assertEquals(normalizedOf(sourceX = 0f, sourceY = 0f), result)
    }

    @Test
    fun rotacion90_ElExtremoInferiorIzquierdoMostradoEsElInferiorDerechoDeOrigen() {
        val result = displayedCropToNormalized(
            displayedX = 0f, displayedY = 300f - size, displayedSize = size,
            sourceWidth = sourceWidth, sourceHeight = sourceHeight, rotationDegrees = 90,
        )
        assertEquals(normalizedOf(sourceX = sourceWidth - size, sourceY = sourceHeight - size), result)
    }

    @Test
    fun rotacion180_ElExtremoSuperiorIzquierdoMostradoEsElInferiorDerechoDeOrigen() {
        val result = displayedCropToNormalized(
            displayedX = 0f, displayedY = 0f, displayedSize = size,
            sourceWidth = sourceWidth, sourceHeight = sourceHeight, rotationDegrees = 180,
        )
        assertEquals(normalizedOf(sourceX = sourceWidth - size, sourceY = sourceHeight - size), result)
    }

    @Test
    fun rotacion270_ElExtremoSuperiorIzquierdoMostradoEsElSuperiorDerechoDeOrigen() {
        val result = displayedCropToNormalized(
            displayedX = 0f, displayedY = 0f, displayedSize = size,
            sourceWidth = sourceWidth, sourceHeight = sourceHeight, rotationDegrees = 270,
        )
        assertEquals(normalizedOf(sourceX = sourceWidth - size, sourceY = 0f), result)
    }

    @Test
    fun unRecorteCentradoDaLoMismoConCualquierRotacion() {
        // Por simetría: rotar alrededor del centro no mueve el centro.
        val centeredDisplayedX = (sourceHeight - size) / 2 // ancho mostrado = sourceHeight en 90/270
        val centeredDisplayedY = (sourceWidth - size) / 2
        for (rotation in listOf(0, 90, 180, 270)) {
            val (dx, dy) = if (rotation == 0 || rotation == 180) {
                (sourceWidth - size) / 2 to (sourceHeight - size) / 2
            } else {
                centeredDisplayedX to centeredDisplayedY
            }
            val result = displayedCropToNormalized(
                displayedX = dx, displayedY = dy, displayedSize = size,
                sourceWidth = sourceWidth, sourceHeight = sourceHeight, rotationDegrees = rotation,
            )
            assertEquals("rotationDegrees=$rotation", 0.5f, result.xFraction, 1e-4f)
            assertEquals("rotationDegrees=$rotation", 0.5f, result.yFraction, 1e-4f)
        }
    }
}
