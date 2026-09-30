package io.github.capibaracasual.stickersini.yuv

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Corrección de [NativeYuvConverter.resizeBicubic] (ADR-0022): sin una
 * referencia en Kotlin que comparar píxel a píxel (a diferencia de la
 * conversión YUV→RGB, ver `YuvConversionParityTest` en `:app`), estas
 * pruebas verifican propiedades matemáticas del bicúbico Catmull-Rom en
 * vez de valores exactos: los pesos de la convolución suman 1 para
 * cualquier desplazamiento fraccional, así que reescalar una imagen de
 * color uniforme debe devolver el mismo color uniforme (con el redondeo
 * de 8 bits), sea cual sea el recorte que produjo esa imagen o el
 * `sizeFraction` del zoom. También verifica el prefiltro de promedio de
 * área que se agrega cuando la reducción supera ~2× (el bicúbico de 4
 * taps fijos solo interpola bien, no anti-alias por sí solo).
 */
@RunWith(AndroidJUnit4::class)
class NativeYuvConverterResizeTest {

    private fun solidColorBitmap(size: Int, color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size) { color }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

    @Test
    fun reescalarColorUniformeHaciaArribaDevuelveElMismoColor() {
        val color = Color.rgb(200, 60, 10)
        val source = solidColorBitmap(120, color)

        val resized = NativeYuvConverter.resizeBicubic(source, 512)

        assertEquals(512, resized.width)
        assertEquals(512, resized.height)
        val samples = listOf(0, 1, 255, 256, 511) // esquinas y centro: donde más pesa el recorte de bordes
        for (x in samples) {
            for (y in samples) {
                val pixel = resized.getPixel(x, y)
                assertTrue(
                    "pixel($x,$y)=${Color.valueOf(pixel)} debería ser ~$color, redondeo de 8 bits",
                    kotlin.math.abs(Color.red(pixel) - Color.red(color)) <= 1 &&
                        kotlin.math.abs(Color.green(pixel) - Color.green(color)) <= 1 &&
                        kotlin.math.abs(Color.blue(pixel) - Color.blue(color)) <= 1,
                )
            }
        }
    }

    @Test
    fun reescalarColorUniformeHaciaAbajoDevuelveElMismoColor() {
        val color = Color.rgb(15, 220, 130)
        val source = solidColorBitmap(700, color)

        val resized = NativeYuvConverter.resizeBicubic(source, 384)

        val pixel = resized.getPixel(192, 192)
        assertTrue(
            kotlin.math.abs(Color.red(pixel) - Color.red(color)) <= 1 &&
                kotlin.math.abs(Color.green(pixel) - Color.green(color)) <= 1 &&
                kotlin.math.abs(Color.blue(pixel) - Color.blue(color)) <= 1,
        )
    }

    @Test
    fun siYaMideElTamanoObjetivoDevuelveLaMismaInstanciaSinReescalar() {
        val source = solidColorBitmap(512, Color.WHITE)

        val result = NativeYuvConverter.resizeBicubic(source, 512)

        assertSame("no debería reescalar si ya mide lo pedido", source, result)
    }

    @Test
    fun preservaAlfaOpaco() {
        val source = solidColorBitmap(64, Color.BLACK)

        val resized = NativeYuvConverter.resizeBicubic(source, 128)

        assertEquals(255, Color.alpha(resized.getPixel(64, 64)))
    }

    /** Rayas verticales de 1 píxel, blanco y negro alternados: la frecuencia más alta representable. */
    private fun finePatternBitmap(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size) { i -> if ((i % size) % 2 == 0) Color.WHITE else Color.BLACK }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

    @Test
    fun reduccionGrandePromediaEnVezDeAliasear() {
        // Reducción de 4x (2048->512, por encima del umbral de ~2x de
        // ADR-0022): sin el prefiltro de promedio de área, el bicúbico de
        // 4 taps fijos solo ve 4 columnas de un patrón de período 2 —
        // según la fase exacta del muestreo, puede dar blanco o negro casi
        // puro en vez del gris promedio (alias). Con el prefiltro, cada
        // píxel de salida promedia muchas columnas alternadas y converge
        // a gris medio.
        val source = finePatternBitmap(2048)

        val resized = NativeYuvConverter.resizeBicubic(source, 512)

        for (x in listOf(50, 150, 300, 450)) {
            val gray = Color.red(resized.getPixel(x, 256))
            assertTrue(
                "pixel x=$x salió $gray, se esperaba cerca de 127 (gris promedio) sin alias",
                gray in 100..155,
            )
        }
    }
}
