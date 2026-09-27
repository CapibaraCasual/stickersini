package io.github.capibaracasual.stickersini.webp

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

private const val TAG = "StickersiniDegradationSweep"
private const val PER_ATTEMPT_TIMEOUT_MS = 60_000L

/** WhatsApp exige 512×512 exactos (restricción innegociable, CLAUDE.md): la resolución de codificación nunca cambia el tamaño del contenedor final, solo cuánta entropía real tiene lo que se escala hacia arriba antes de codificar. */
private const val ENCODE_SIZE = 512

/**
 * Mide la escalera de degradación completa para el caso que rompió RF-12
 * (ver `reproduceRf12_80fotogramas10s_contenidoAdverso` en
 * `WebpAnimEncoderPerformanceTest` y ADR-0007): el piso de 5 fps de
 * ADR-0007 nunca se midió más allá de un clip de 3 s (15 fotogramas); para
 * el máximo de RF-06 (10 s) da 50, y a esa cantidad, contenido adverso no
 * cabe en 500 KB ni a calidad 0.
 *
 * Barre tres ejes — cantidad de fotogramas ({50, 30, 24, 15}, pedidos para
 * este barrido: 50 es el piso actual para 10 s, 15 el piso ya confirmado
 * para 3 s, 30 y 24 puntos intermedios), calidad ({75, 50, 25, 0}) y
 * resolución de codificación ({512, 384, 320}, nunca antes conectada a la
 * degradación pese a estar medida desde el 2026-09-26, ver README "Tema
 * abierto") — para tener, antes de proponer un ADR nuevo, qué
 * combinaciones caben en 500 KB y cuáles no. Una sola pasada por
 * configuración (48 en total): es una medición de tamaño, no de tiempo
 * contra un presupuesto — no corre bajo el método de 5 corridas separadas
 * que exige CLAUDE.md para eso, mismo criterio que ya usó
 * `WebpFrameFloorMeasurementTest`.
 *
 * La resolución de codificación se logra generando el ruido directamente a
 * la resolución candidata y escalándolo a 512×512 antes de codificar (mismo
 * patrón que `ComparisonStickerGeneratorTest`): el WebP resultante sigue
 * siendo 512×512, pero con menos entropía real, que es lo que compra
 * tamaño.
 */
@RunWith(AndroidJUnit4::class)
class DegradationLadderSweepTest {

    private val frameDurationMs = 125 // 8 fps de prefiltro (ADR-0012); no afecta el tamaño codificado.

    private fun noisyBitmap(seed: Int, resolution: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(resolution, resolution, Bitmap.Config.ARGB_8888)
        val random = Random(seed)
        val pixels = IntArray(resolution * resolution) {
            Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
        }
        bitmap.setPixels(pixels, 0, resolution, 0, 0, resolution, resolution)
        return bitmap
    }

    private fun frames(frameCount: Int, resolution: Int): List<WebpFrame> = (0 until frameCount).map { i ->
        val generated = noisyBitmap(i, resolution)
        val bitmap = if (resolution == ENCODE_SIZE) {
            generated
        } else {
            Bitmap.createScaledBitmap(generated, ENCODE_SIZE, ENCODE_SIZE, /* filter = */ true)
        }
        WebpFrame(bitmap, frameDurationMs)
    }

    @Test
    fun barreFotogramasXCalidadXResolucion_contenidoAdverso() {
        val trace = TraceWriter("webp_degradation_ladder_sweep_trace.txt")
        val runner = TimedAttemptRunner("webp-degradation-sweep")

        data class Config(val frameCount: Int, val quality: Int, val resolution: Int)

        val frameCounts = listOf(50, 30, 24, 15)
        val qualities = listOf(75, 50, 25, 0)
        val resolutions = listOf(512, 384, 320)
        val configs = frameCounts.flatMap { frameCount ->
            qualities.flatMap { quality ->
                resolutions.map { resolution -> Config(frameCount, quality, resolution) }
            }
        }

        var medidas = 0
        try {
            for (config in configs) {
                val result = runner.run(PER_ATTEMPT_TIMEOUT_MS) {
                    NativeWebpEncoder.encode(
                        frames(config.frameCount, config.resolution),
                        quality = config.quality,
                        minimizeSize = false,
                        method = 0,
                    )
                }
                medidas++

                val line = if (result.timedOut) {
                    "frameCount=${config.frameCount} quality=${config.quality} resolution=${config.resolution} " +
                        "method=0 minimizeSize=false TIMEOUT tras ${PER_ATTEMPT_TIMEOUT_MS}ms"
                } else {
                    "frameCount=${config.frameCount} quality=${config.quality} resolution=${config.resolution} " +
                        "method=0 minimizeSize=false sizeBytes=${result.bytes!!.size} elapsedMs=${result.elapsedMs} " +
                        "cabe500KB=${result.bytes.size <= ANIMATED_WEBP_TARGET_SIZE_BYTES}"
                }
                Log.i(TAG, line)
                trace.line(line)
            }
        } finally {
            runner.shutdown()
            trace.close()
        }

        val resumen = "StickersiniDegradationSweep: $medidas mediciones, traza completa en ${trace.file.absolutePath}"
        Log.i(TAG, resumen)
        println(resumen)

        assertTrue("deberían quedar registradas las ${configs.size} mediciones", medidas == configs.size)
    }
}
