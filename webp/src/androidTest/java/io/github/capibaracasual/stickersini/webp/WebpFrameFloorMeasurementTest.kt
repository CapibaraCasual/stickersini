package io.github.capibaracasual.stickersini.webp

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

private const val TAG = "StickersiniFrameFloor"
private const val PER_ATTEMPT_TIMEOUT_MS = 60_000L

/**
 * Mide, sobre contenido adverso, qué combinación de fotogramas y calidad
 * (a `method=0`, sin `minimize_size`) cabe en 500 KB conservando más
 * movimiento — el dato que le faltaba a ADR-0007 para fijar un piso de
 * fotogramas por debajo del cual la reducción por proporción de
 * ADR-0006 no debe bajar (a 30 fotogramas / 3 s, terminaba en 3
 * fotogramas: 1 fps, ya no es una animación).
 *
 * Dos grupos, cada uno variando un solo eje:
 * - Calidad fija en la baja (30 fotogramas, quality en {50, 25, 0}): qué
 *   tan barato sale bajar calidad sin tocar el número de fotogramas.
 * - Fotogramas fijos por debajo de 30 (quality=75, frameCount en {15, 10}):
 *   qué tan barato sale conservar más fotogramas de los que la
 *   proporción de ADR-0006 elegiría sola.
 */
@RunWith(AndroidJUnit4::class)
class WebpFrameFloorMeasurementTest {

    private val size = 512
    private val frameDurationMs = 100

    private fun noisyBitmap(seed: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val random = Random(seed)
        val pixels = IntArray(size * size) {
            Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

    private fun frames(count: Int) = (0 until count).map { i -> WebpFrame(noisyBitmap(i), frameDurationMs) }

    @Test
    fun pisoDeFotogramas_calidadYFrameCount_contenidoAdverso() {
        val trace = TraceWriter("webp_frame_floor_trace.txt")
        val runner = TimedAttemptRunner("webp-frame-floor")

        data class Config(val frameCount: Int, val quality: Int)
        val configs = listOf(
            Config(30, 50),
            Config(30, 25),
            Config(30, 0),
            Config(15, 75),
            Config(10, 75),
        )
        var medidas = 0

        try {
            for (config in configs) {
                val result = runner.run(PER_ATTEMPT_TIMEOUT_MS) {
                    NativeWebpEncoder.encode(
                        frames(config.frameCount),
                        quality = config.quality,
                        minimizeSize = false,
                        method = 0,
                    )
                }
                medidas++

                val line = if (result.timedOut) {
                    "frameCount=${config.frameCount} quality=${config.quality} method=0 minimizeSize=false " +
                        "TIMEOUT tras ${PER_ATTEMPT_TIMEOUT_MS}ms"
                } else {
                    "frameCount=${config.frameCount} quality=${config.quality} method=0 minimizeSize=false " +
                        "sizeBytes=${result.bytes!!.size} elapsedMs=${result.elapsedMs} " +
                        "cabe500KB=${result.bytes.size <= ANIMATED_WEBP_TARGET_SIZE_BYTES}"
                }
                Log.i(TAG, line)
                trace.line(line)
            }
        } finally {
            runner.shutdown()
            trace.close()
        }

        val resumen = "StickersiniFrameFloor: $medidas mediciones, traza completa en ${trace.file.absolutePath}"
        Log.i(TAG, resumen)
        println(resumen)

        assertTrue("deberían quedar registradas las ${configs.size} mediciones", medidas == configs.size)
    }
}
