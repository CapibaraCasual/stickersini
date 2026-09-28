package io.github.capibaracasual.stickersini.media

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.capibaracasual.stickersini.webp.ANIMATED_WEBP_TARGET_SIZE_BYTES
import io.github.capibaracasual.stickersini.webp.ProductionWebpEncoder
import io.github.capibaracasual.stickersini.webp.QualitySearch
import io.github.capibaracasual.stickersini.webp.WebpAnimEncoder
import io.github.capibaracasual.stickersini.webp.WebpFrame
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileWriter

private const val TAG = "StickersiniSampledSearch"

/**
 * Investigación de producto (ver conversación de la fase — surge de
 * [HighFpsPipelineProbeTest]: el cuello de botella para 12-15 fps es el
 * encoder, no el decode), no aprobada para producción todavía:
 *
 * 1. Cuenta cuántas codificaciones COMPLETAS hace hoy la bisección de
 *    [WebpAnimEncoder] a 8/12/15 fps, y cuánto tarda cada una — para saber
 *    si de verdad hay margen recodificando menos veces.
 * 2. Mide el costo real de `minimize_size` en contenido real (aislado,
 *    fuera de la lógica de "cerca del límite" de [WebpAnimEncoder]) y si
 *    su beneficio sigue siendo marginal como midió ADR-0006 en otro
 *    contenido.
 * 3. Prototipo de búsqueda de calidad por MUESTREO: bisecta sobre 1 de
 *    cada [SAMPLE_STRIDE] fotogramas (barato), extrapola el tamaño
 *    objetivo por proporción, y hace una sola codificación completa de
 *    verificación (con reintento por bisección completa si no alcanza).
 *    Mide tiempo total y cuántas codificaciones completas necesitó, contra
 *    el enfoque actual, a 8/12/15 fps.
 *
 * Reutiliza [VideoFrameDecoder], [QualitySearch] y [ProductionWebpEncoder]
 * de producción sin ningún cambio — no reemplaza ni modifica
 * [WebpAnimEncoder]: la búsqueda por muestreo es una función propia de
 * este test, no de producción.
 */
@RunWith(AndroidJUnit4::class)
class SampledQualitySearchProbeTest {

    private val sampleStride = SAMPLE_STRIDE

    private fun testVideoFile(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val videoFile = File(instrumentation.targetContext.getExternalFilesDir(null), "stickersini_test_video.mp4")
        assumeTrue(
            "No hay video de prueba en ${videoFile.absolutePath}.",
            videoFile.exists(),
        )
        return videoFile
    }

    @Test
    fun unaSolaCorridaComparandoBusquedas() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val targetFps = InstrumentationRegistry.getArguments().getString("targetFps")?.toIntOrNull()
            ?: VIDEO_PREFILTER_TARGET_FPS
        val videoFile = testVideoFile()
        val uri = Uri.fromFile(videoFile)

        val trace = File(context.getExternalFilesDir(null), "sampled_search_probe_trace.txt")
        val writer = FileWriter(trace, /* append = */ true)
        fun line(text: String) {
            Log.i(TAG, text)
            writer.write("$text\n")
            writer.flush()
        }

        line("=== una corrida, targetFps=$targetFps ===")
        try {
            val frames = VideoFrameDecoder(targetFps = targetFps).decode(context, uri, durationMs = 10_000L).frames
            line("fotogramas decodificados: ${frames.size}")

            // --- 1) Enfoque actual: cuenta y cronometra cada codificación completa. ---
            var attemptCount = 0
            var lastAttemptStartNanos = System.nanoTime()
            val currentApproachStart = System.nanoTime()
            val currentResult = WebpAnimEncoder(singleShotEncoder = ProductionWebpEncoder, targetSizeBytes = ANIMATED_WEBP_TARGET_SIZE_BYTES)
                .encode(frames) { progress ->
                    if (attemptCount > 0) {
                        val prevElapsedMs = (System.nanoTime() - lastAttemptStartNanos) / 1_000_000
                        line("  intento actual #$attemptCount tardó ${prevElapsedMs}ms")
                    }
                    attemptCount = progress.attemptNumber
                    lastAttemptStartNanos = System.nanoTime()
                }
            val currentApproachMs = (System.nanoTime() - currentApproachStart) / 1_000_000
            line(
                "ENFOQUE ACTUAL: intentos=$attemptCount totalMs=$currentApproachMs " +
                    "quality=${currentResult.quality} sizeBytes=${currentResult.bytes.size} " +
                    "frameCount=${currentResult.frameCount} shortenedByMs=${currentResult.shortenedByMs}",
            )

            // --- 2) Costo aislado de minimize_size, en contenido real, a la calidad ganadora. ---
            val minimizeOffStart = System.nanoTime()
            val bytesOff = ProductionWebpEncoder.encode(frames, quality = currentResult.quality, minimizeSize = false)
            val minimizeOffMs = (System.nanoTime() - minimizeOffStart) / 1_000_000

            val minimizeOnStart = System.nanoTime()
            val bytesOn = ProductionWebpEncoder.encode(frames, quality = currentResult.quality, minimizeSize = true)
            val minimizeOnMs = (System.nanoTime() - minimizeOnStart) / 1_000_000

            val savingBytes = bytesOff.size - bytesOn.size
            val savingPercent = savingBytes * 100.0 / bytesOff.size
            line(
                "MINIMIZE_SIZE a quality=${currentResult.quality}, ${frames.size} fotogramas reales: " +
                    "sin=${minimizeOffMs}ms/${bytesOff.size}B con=${minimizeOnMs}ms/${bytesOn.size}B " +
                    "ahorro=${savingBytes}B (%.2f%%) costoExtra=${minimizeOnMs - minimizeOffMs}ms"
                        .format(java.util.Locale.US, savingPercent),
            )

            // --- 3) Prototipo: bisección sobre una muestra + una verificación completa. ---
            val sample = frames.filterIndexed { index, _ -> index % sampleStride == 0 }
            val scaledTarget = (ANIMATED_WEBP_TARGET_SIZE_BYTES.toDouble() * sample.size / frames.size).toInt()
            line("MUESTREO: ${sample.size}/${frames.size} fotogramas (1 de cada $sampleStride), targetEscalado=$scaledTarget")

            val sampledSearchStart = System.nanoTime()
            val sampleSearch = QualitySearch(scaledTarget)
            // Misma política que WebpAnimEncoder.bisectQuality (atFloor=false),
            // no una bisección a ciegas hasta el óptimo: intenta FIRST_QUALITY
            // (75, ADR-0006/0007) y, si ya entra, PARA ahí — no sigue buscando
            // una calidad todavía mejor. Solo bisecta hacia abajo si 75 no
            // entra. Repetir la bisección completa de QualitySearch.next() sin
            // este atajo gastó 7 intentos en vez de 1-3 en la primera versión
            // de este prototipo: no era una comparación justa contra el
            // enfoque de producción, que aplica el mismo atajo.
            var sampleAttempts = 1
            val firstBytes = ProductionWebpEncoder.encode(sample, PRODUCTION_FIRST_QUALITY_SEED, minimizeSize = false)
            var sampleQuality: Int? = if (firstBytes.size <= scaledTarget) {
                null
            } else {
                sampleSearch.next(PRODUCTION_FIRST_QUALITY_SEED, firstBytes.size)
            }
            var candidateQuality = if (firstBytes.size <= scaledTarget) PRODUCTION_FIRST_QUALITY_SEED else null
            while (sampleQuality != null) {
                sampleAttempts++
                val bytes = ProductionWebpEncoder.encode(sample, sampleQuality, minimizeSize = false)
                if (bytes.size <= scaledTarget) candidateQuality = sampleQuality
                sampleQuality = sampleSearch.next(sampleQuality, bytes.size)
            }
            val sampledSearchMs = (System.nanoTime() - sampledSearchStart) / 1_000_000
            line("BUSQUEDA POR MUESTRA: intentos=$sampleAttempts tardó=${sampledSearchMs}ms calidadCandidata=$candidateQuality")

            if (candidateQuality == null) {
                line("PROTOTIPO: la muestra no encontró ninguna calidad candidata, no se puede verificar.")
            } else {
                var verifyAttempts = 1
                val verifyStart = System.nanoTime()
                var verifyBytes = ProductionWebpEncoder.encode(frames, candidateQuality, minimizeSize = false)
                var finalQuality = candidateQuality

                if (verifyBytes.size > ANIMATED_WEBP_TARGET_SIZE_BYTES) {
                    // La extrapolación por proporción falló: cae a bisección
                    // completa desde acá, sembrada con lo ya sabido (no
                    // reinicia desde cero).
                    val fullSearch = QualitySearch(ANIMATED_WEBP_TARGET_SIZE_BYTES)
                    var quality: Int? = fullSearch.next(finalQuality, verifyBytes.size)
                    while (quality != null) {
                        verifyAttempts++
                        verifyBytes = ProductionWebpEncoder.encode(frames, quality, minimizeSize = false)
                        finalQuality = quality
                        quality = fullSearch.next(quality, verifyBytes.size)
                    }
                    finalQuality = fullSearch.bestFittingQuality() ?: finalQuality
                }
                val verifyMs = (System.nanoTime() - verifyStart) / 1_000_000
                val prototypeTotalMs = sampledSearchMs + verifyMs

                line(
                    "PROTOTIPO TOTAL: intentosCompletos=$verifyAttempts (+${sampleAttempts} sobre la muestra) " +
                        "tiempoMuestra=${sampledSearchMs}ms tiempoVerificacion=${verifyMs}ms totalMs=$prototypeTotalMs " +
                        "quality=$finalQuality sizeBytes=${verifyBytes.size} " +
                        "cupo=${verifyBytes.size <= ANIMATED_WEBP_TARGET_SIZE_BYTES}",
                )
                line(
                    "COMPARACION: actual=${currentApproachMs}ms ($attemptCount intentos completos) vs " +
                        "prototipo=${prototypeTotalMs}ms ($verifyAttempts intentos completos + $sampleAttempts sobre muestra)",
                )
            }
        } catch (e: Exception) {
            line("FALLÓ: targetFps=$targetFps error=${e.message}")
            throw e
        } finally {
            writer.close()
        }

        println("StickersiniSampledSearch: traza completa en ${trace.absolutePath}")
    }

    private companion object {
        /** 1 de cada 4 fotogramas para la bisección barata del prototipo (punto 3 de la conversación). */
        const val SAMPLE_STRIDE = 4

        /**
         * Mismo valor que `FIRST_QUALITY` (privado) en `WebpAnimEncoder.kt`
         * (ADR-0006/ADR-0007): sembrar la bisección de la muestra a ciegas
         * en 100 (QualitySearch.firstQuality()) la hizo gastar 7-8 intentos
         * en vez de los 1-3 que logra producción con esta semilla — no es
         * una comparación justa contra el enfoque actual si se parte de una
         * bisección más tonta que la de referencia.
         */
        const val PRODUCTION_FIRST_QUALITY_SEED = 75
    }
}
