package io.github.capibaracasual.stickersini.webp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Codifica de verdad, a través de JNI y libwebp, en vez de con un
 * [SingleShotWebpEncoder] falso como en [WebpAnimEncoderTest]. Necesita un
 * dispositivo o emulador porque carga la librería nativa `stickersini_webp`.
 */
@RunWith(AndroidJUnit4::class)
class WebpAnimEncoderInstrumentedTest {

    private val size = 512

    private fun flatColorBitmap(color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(color)
        return bitmap
    }

    /** Ruido pseudoaleatorio: difícil de comprimir, obliga a bajar la calidad. */
    private fun noisyBitmap(seed: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val random = Random(seed)
        val pixels = IntArray(size * size) {
            Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

    @Test
    fun codificaTresFotogramasDeColorPlanoYCumpleRF10() {
        val frames = listOf(
            WebpFrame(flatColorBitmap(Color.RED), durationMs = 200),
            WebpFrame(flatColorBitmap(Color.GREEN), durationMs = 200),
            WebpFrame(flatColorBitmap(Color.BLUE), durationMs = 200),
        )

        val result = WebpAnimEncoder().encode(frames)

        assertIsWebp(result.bytes)
        assertTrue(
            "RF-10: ${result.bytes.size} bytes supera el límite de ${ANIMATED_WEBP_TARGET_SIZE_BYTES}",
            result.bytes.size <= ANIMATED_WEBP_TARGET_SIZE_BYTES,
        )
    }

    @Test
    fun codificaContenidoRuidosoBajandoCalidadHastaCumplirRF10() {
        val frames = (0 until 10).map { i ->
            WebpFrame(noisyBitmap(seed = i), durationMs = 100)
        }

        val result = WebpAnimEncoder().encode(frames)

        assertIsWebp(result.bytes)
        assertTrue(
            "RF-10: ${result.bytes.size} bytes supera el límite de ${ANIMATED_WEBP_TARGET_SIZE_BYTES}",
            result.bytes.size <= ANIMATED_WEBP_TARGET_SIZE_BYTES,
        )
        assertTrue(
            "contenido ruidoso a 512x512x10 fotogramas debería forzar una calidad " +
                "por debajo de la máxima, no ${result.quality}",
            result.quality < QualitySearch.MAX_QUALITY,
        )
    }

    @Test
    fun rechazaBitmapsDeTamanoDistinto() {
        val frames = listOf(
            WebpFrame(flatColorBitmap(Color.RED), durationMs = 100),
            WebpFrame(Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888), durationMs = 100),
        )

        try {
            NativeWebpEncoder.encode(frames, quality = 80, minimizeSize = false)
            throw AssertionError("se esperaba WebpEncodeException por tamaños distintos")
        } catch (expected: WebpEncodeException) {
            // esperado
        }
    }

    private fun assertIsWebp(bytes: ByteArray) {
        assertTrue("el archivo es demasiado corto para ser WebP", bytes.size > 12)
        val riff = String(bytes, 0, 4, Charsets.US_ASCII)
        val webp = String(bytes, 8, 4, Charsets.US_ASCII)
        assertEquals("RIFF", riff)
        assertEquals("WEBP", webp)
    }
}
