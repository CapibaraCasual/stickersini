package io.github.capibaracasual.stickersini.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.capibaracasual.stickersini.yuv.NativeYuvConverter
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/**
 * ADR-0011: la conversión YUV→RGB de producción se movió de Kotlin a C
 * (`NativeYuvConverter`, módulo `:yuv`), pero la implementación en Kotlin se
 * conserva (`YuvFrameConverter.yuv420CenterSquareToArgbKotlinReference`)
 * como referencia. Este test corre las dos sobre los mismos buffers YUV
 * sintéticos y compara el resultado píxel a píxel: un error de conversión
 * de color no rompe nada — no hay excepción, ni test de tamaño, ni
 * `WebpEncodeException` que lo delate — solo se nota mirando un sticker con
 * los colores mal, así que hace falta esta comparación exacta para
 * detectarlo.
 *
 * Buffers sintéticos, no un `Image` real decodificado: la lógica de
 * conversión, en ambos lados, ya no depende de `android.media.Image` (ver
 * el refactor de ADR-0011) precisamente para que este test no necesite
 * `ImageReader`/`MediaCodec` reales. Los strides se eligen a propósito
 * distintos del ancho lógico (relleno) y el `pixelStride` de U/V en 2
 * (semiplanar, como entrega la mayoría del hardware real) para ejercitar
 * esa aritmética, no solo el caso trivial de planos empaquetados.
 */
@RunWith(AndroidJUnit4::class)
class YuvConversionParityTest {

    // Ancho/alto lógicos del plano Y de origen, con relleno: yRowStride
    // (96) es mayor que el ancho lógico (80), como en un buffer real con
    // padding de alineación.
    private val sourceWidth = 80
    private val sourceHeight = 64
    private val yRowStride = 96

    // U/V semiplanar: la mitad de ancho/alto que Y, pixelStride=2 (los dos
    // bytes de cada posición U/V comparten fila con el canal contrario en
    // el layout NV12/NV21 real), con el mismo relleno que Y.
    private val uvWidth = sourceWidth / 2
    private val uvHeight = sourceHeight / 2
    private val uvRowStride = 64
    private val uvPixelStride = 2

    // Recorte no trivial: no arranca en (0,0) ni cubre todo el plano.
    private val xOffset = 8
    private val yOffset = 4
    private val size = 48

    private fun directBuffer(length: Int, valueAt: (Int) -> Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(length)
        for (i in 0 until length) buffer.put(i, valueAt(i).toByte())
        return buffer
    }

    @Test
    fun laConversionNativaCoincidePixelAPixelConLaReferenciaKotlin() {
        // Patrones deterministas y no triviales (no un color plano): cubren
        // el rango 0-255 completo, incluidos los extremos, en pocas
        // iteraciones por el módulo.
        val yBuffer = directBuffer(sourceHeight * yRowStride) { i -> (i * 37 + 11) % 256 }
        val uBuffer = directBuffer(uvHeight * uvRowStride) { i -> (i * 53 + 5) % 256 }
        val vBuffer = directBuffer(uvHeight * uvRowStride) { i -> (i * 71 + 17) % 256 }

        val nativeResult = NativeYuvConverter.convert(
            yBuffer = yBuffer, yRowStride = yRowStride,
            uBuffer = uBuffer, uRowStride = uvRowStride, uPixelStride = uvPixelStride,
            vBuffer = vBuffer, vRowStride = uvRowStride, vPixelStride = uvPixelStride,
            xOffset = xOffset, yOffset = yOffset, size = size,
        )
        val referenceResult = YuvFrameConverter.yuv420CenterSquareToArgbKotlinReference(
            yBuffer = yBuffer, yRowStride = yRowStride,
            uBuffer = uBuffer, uRowStride = uvRowStride, uPixelStride = uvPixelStride,
            vBuffer = vBuffer, vRowStride = uvRowStride, vPixelStride = uvPixelStride,
            xOffset = xOffset, yOffset = yOffset, size = size,
        )

        val nativePixels = IntArray(size * size)
        nativeResult.getPixels(nativePixels, 0, size, 0, 0, size, size)
        val referencePixels = IntArray(size * size)
        referenceResult.getPixels(referencePixels, 0, size, 0, 0, size, size)

        assertArrayEquals(
            "la conversión nativa debe producir exactamente los mismos píxeles que la referencia Kotlin",
            referencePixels,
            nativePixels,
        )
    }
}
