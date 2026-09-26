package io.github.capibaracasual.stickersini.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.capibaracasual.stickersini.webp.ANIMATED_WEBP_TARGET_SIZE_BYTES
import io.github.capibaracasual.stickersini.webp.WebpAnimEncoder
import io.github.capibaracasual.stickersini.webp.WebpFrame
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

private const val STICKER_SIZE = 512

/**
 * Genera, de la misma escena, un sticker con la configuración actual
 * (ADR-0012: 512×512, 8 fps) y con las dos resoluciones de codificación más
 * chicas probadas en `ResolutionFpsSweepTest`, al mismo fps — para mirarlos
 * en el teléfono, no para leer una tabla. La decisión entre nitidez y
 * margen la toma quien mira, no este test.
 *
 * El barrido de `ResolutionFpsSweepTest` no encontró ninguna combinación
 * con fps>8 que cumpla RNF-08 en ninguna resolución (bajar resolución no
 * ataca el cuello de botella de más fotogramas): por eso las tres
 * variantes comparadas acá son todas a 8 fps, sin cambiar ese valor. Lo que
 * sí cambia entre variantes es la resolución de codificación (512, 384,
 * 320), que el barrido mostró que casi duplica el margen del tramo de 10 s
 * (12.6% → 55.9%) al evitar un segundo intento de bisección del
 * codificador — tema abierto en el README, pendiente de esta comparación
 * visual.
 *
 * Corre una sola vez por variante (no 5 corridas: esto es para comparar el
 * resultado visual, no para medir tiempo). Guarda cada `.webp` en el
 * `externalFilesDir` de la app, con un nombre que dice qué configuración es.
 */
@RunWith(AndroidJUnit4::class)
class ComparisonStickerGeneratorTest {

    private data class Candidate(val label: String, val targetSize: Int, val fps: Int)

    // Config actual (ADR-0012), sin cambios.
    private val current = Candidate(label = "actual_512_8fps", targetSize = 512, fps = 8)

    // Mismo fps (8, sin cambios): solo baja la resolución de codificación.
    private val candidateA = Candidate(label = "candidata_384_8fps", targetSize = 384, fps = 8)
    private val candidateB = Candidate(label = "candidata_320_8fps", targetSize = 320, fps = 8)

    /** Misma escena para las tres variantes: 10 s (el máximo de RF-06), donde el barrido mostró la diferencia de margen. */
    private val comparisonDurationMs = 10_000L

    private fun testVideoFile(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val videoFile = File(instrumentation.targetContext.getExternalFilesDir(null), "stickersini_test_video.mp4")
        assumeTrue(
            "No hay video de prueba en ${videoFile.absolutePath}. Antes de correr este test: " +
                "adb push <tu_grabacion.mp4> ${videoFile.absolutePath}",
            videoFile.exists(),
        )
        return videoFile
    }

    private fun generate(context: Context, uri: Uri, candidate: Candidate): File {
        val importResult = VideoFrameDecoder(targetFps = candidate.fps)
            .decode(context, uri, durationMs = comparisonDurationMs, targetSize = candidate.targetSize)

        val framesToEncode = if (candidate.targetSize == STICKER_SIZE) {
            importResult.frames
        } else {
            importResult.frames.map { frame ->
                WebpFrame(Bitmap.createScaledBitmap(frame.bitmap, STICKER_SIZE, STICKER_SIZE, /* filter = */ true), frame.durationMs)
            }
        }

        val result = WebpAnimEncoder(targetSizeBytes = ANIMATED_WEBP_TARGET_SIZE_BYTES).encode(framesToEncode)
        val outFile = File(context.getExternalFilesDir(null), "comparacion_${candidate.label}.webp")
        outFile.writeBytes(result.bytes)
        return outFile
    }

    @Test
    fun generarStickersDeComparacion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val videoFile = testVideoFile()
        val uri = Uri.fromFile(videoFile)

        val generated = listOf(current, candidateA, candidateB).map { candidate ->
            val file = generate(context, uri, candidate)
            println(
                "StickersiniComparison: ${candidate.label} (res=${candidate.targetSize} fps=${candidate.fps}) -> " +
                    "${file.absolutePath} (${file.length()} bytes)",
            )
            file
        }

        println("StickersiniComparison: ${generated.size} archivos generados en ${context.getExternalFilesDir(null)}")
    }
}
