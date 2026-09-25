package io.github.capibaracasual.stickersini.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.capibaracasual.stickersini.webp.STATIC_WEBP_TARGET_SIZE_BYTES
import io.github.capibaracasual.stickersini.webp.WebpAnimEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

private const val TAG = "StickersiniImageImport"

/**
 * Contenido autocontenido (una foto/JPEG generada en el propio test, con
 * EXIF real escrito y releído por el framework), no un archivo que haya
 * que empujar a mano: a diferencia del video (donde la dificultad real solo
 * aparece con contenido real, ver `VideoImportPerformanceTest`), acá el
 * riesgo es de formato/orientación/recorte, no de cuánto tarda comprimir —
 * y eso sí se puede ejercitar con una imagen sintética.
 */
@RunWith(AndroidJUnit4::class)
class ImageFrameDecoderTest {

    private val sourceWidth = 1200
    private val sourceHeight = 1600

    /**
     * Fondo blanco con una marca roja bien adentro del cuadrado central que
     * [CenterSquareCrop] va a conservar (el cuadrado central de 1200×1600
     * excluye franjas de 200 px arriba y abajo), cerca de su esquina
     * superior izquierda. Sirve para verificar que la corrección de EXIF
     * rota de verdad, no solo que las dimensiones den 512×512.
     */
    private fun markerBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val gradient = LinearGradient(
            0f, 0f, sourceWidth.toFloat(), 0f,
            Color.rgb(230, 230, 230), Color.WHITE,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, sourceWidth.toFloat(), sourceHeight.toFloat(), Paint().apply { shader = gradient })
        // Marca de 300x300 dentro del cuadrado central (y >= 200), cerca de
        // su esquina superior izquierda. Suficientemente grande para que,
        // ya recortada y escalada a 512x512, siga cubriendo el punto de
        // muestreo (128,128) de su cuadrante — una marca más chica (100x100)
        // queda demasiado pegada a la esquina real y el muestreo la pierde.
        canvas.drawRect(50f, 250f, 350f, 550f, Paint().apply { color = Color.RED })
        return bitmap
    }

    private fun writeJpegWithOrientation(context: Context, exifOrientation: Int): File {
        val file = File(context.cacheDir, "stickersini_image_test_$exifOrientation.jpg")
        FileOutputStream(file).use { out -> markerBitmap().compress(Bitmap.CompressFormat.JPEG, 95, out) }
        val exif = ExifInterface(file.absolutePath)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
        exif.saveAttributes()
        return file
    }

    private enum class Quadrant { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, NONE }

    private fun redQuadrant(bitmap: Bitmap): Quadrant {
        val quarter = bitmap.width / 4
        val threeQuarters = bitmap.width - quarter
        fun isRed(x: Int, y: Int): Boolean {
            val pixel = bitmap.getPixel(x, y)
            return Color.red(pixel) > 180 && Color.green(pixel) < 100 && Color.blue(pixel) < 100
        }
        return when {
            isRed(quarter, quarter) -> Quadrant.TOP_LEFT
            isRed(threeQuarters, quarter) -> Quadrant.TOP_RIGHT
            isRed(quarter, threeQuarters) -> Quadrant.BOTTOM_LEFT
            isRed(threeQuarters, threeQuarters) -> Quadrant.BOTTOM_RIGHT
            else -> Quadrant.NONE
        }
    }

    /**
     * La marca queda cerca de la esquina superior izquierda del cuadrado ya
     * recortado. Corregir una foto guardada con `ORIENTATION_ROTATE_N`
     * equivale a rotarla `N` grados en sentido horario para que se vea
     * derecha — por eso la marca se desplaza en sentido horario con `N`.
     */
    @Test
    fun corrigeLaOrientacionExifDeVerdad() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val casos = mapOf(
            ExifInterface.ORIENTATION_NORMAL to Quadrant.TOP_LEFT,
            ExifInterface.ORIENTATION_ROTATE_90 to Quadrant.TOP_RIGHT,
            ExifInterface.ORIENTATION_ROTATE_180 to Quadrant.BOTTOM_RIGHT,
            ExifInterface.ORIENTATION_ROTATE_270 to Quadrant.BOTTOM_LEFT,
        )

        for ((orientation, esperado) in casos) {
            val file = writeJpegWithOrientation(context, orientation)
            val frame = ImageFrameDecoder().decode(context, Uri.fromFile(file))

            assertEquals("orientation=$orientation: ancho", 512, frame.bitmap.width)
            assertEquals("orientation=$orientation: alto", 512, frame.bitmap.height)
            assertEquals(
                "orientation=$orientation: la marca debería quedar en $esperado",
                esperado,
                redQuadrant(frame.bitmap),
            )
        }
    }

    /**
     * Mide decodificar (con recorte y orientación) + codificar como WebP
     * estático (RF-11, `targetSizeBytes = STATIC_WEBP_TARGET_SIZE_BYTES`).
     * Sin tabla de RNF-08 que comparar: ese requisito habla de clips de
     * video por duración, no de una imagen suelta; el número queda
     * registrado igual, en `docs/desarrollo/pruebas.md`.
     */
    @Test
    fun mideDecodificacionYCodificacionDeUnaImagen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = writeJpegWithOrientation(context, ExifInterface.ORIENTATION_NORMAL)

        val decodeStart = System.nanoTime()
        val frame = ImageFrameDecoder().decode(context, Uri.fromFile(file))
        val decodeMs = (System.nanoTime() - decodeStart) / 1_000_000

        val encodeStart = System.nanoTime()
        val result = WebpAnimEncoder(targetSizeBytes = STATIC_WEBP_TARGET_SIZE_BYTES).encode(listOf(frame))
        val encodeMs = (System.nanoTime() - encodeStart) / 1_000_000

        val line = "decodeMs=$decodeMs encodeMs=$encodeMs totalMs=${decodeMs + encodeMs} " +
            "sizeBytes=${result.bytes.size} quality=${result.quality}"
        Log.i(TAG, line)
        println("StickersiniImageImport: $line")

        assertTrue("RF-11: el sticker estático debe pesar 100 000 bytes o menos", result.bytes.size <= 100_000)
    }
}
