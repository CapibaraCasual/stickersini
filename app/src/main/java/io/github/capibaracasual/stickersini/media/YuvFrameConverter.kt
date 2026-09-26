package io.github.capibaracasual.stickersini.media

import android.graphics.Bitmap
import android.media.Image
import io.github.capibaracasual.stickersini.yuv.NativeYuvConverter
import java.nio.ByteBuffer

/**
 * Convierte un [Image] `YUV_420_888` (la salida de un `MediaCodec`
 * apuntando a un `ImageReader`, ADR-0008) en un `Bitmap` `ARGB_8888`
 * cuadrado de [targetSize]×[targetSize]: el contrato de entrada de
 * [io.github.capibaracasual.stickersini.webp.WebpFrame]. Conversión en CPU,
 * sin RenderScript (deprecado) ni GPU — medido en dispositivo real
 * (`docs/desarrollo/pruebas.md`) que el costo escala con los píxeles
 * convertidos, no con recorrer el video, así que la ruta CPU de ADR-0008
 * queda confirmada sin necesitar GPU.
 *
 * **La conversión en sí (el bucle YUV→RGB) corre en C, vía JNI
 * ([NativeYuvConverter], módulo `:yuv`), no en Kotlin (ADR-0011): medido
 * que el bucle en Kotlin era el 54-63% del tiempo de decodificar un video,
 * y crecía con la duración del clip.** [yuv420CenterSquareToArgbKotlinReference]
 * conserva esa implementación original, ya no en la ruta de producción,
 * como referencia del test de paridad píxel a píxel contra la nativa
 * (`YuvConversionParityTest`).
 *
 * El recorte de área que elige el usuario (RF-07, [NormalizedCrop]) ya se
 * aplica acá: por defecto sigue siendo el cuadrado centrado más grande
 * posible ([NormalizedCrop.CENTERED]).
 *
 * **Recorta antes de convertir, no después.** La primera versión convertía
 * el fotograma de origen completo a RGB y recién ahí lo recortaba al
 * cuadrado central — para un video en retrato como el medido (720×1600),
 * eso convierte 1 152 000 píxeles para quedarse con 518 400 (un 55%
 * descartado de inmediato). Esta versión solo itera sobre las filas y
 * columnas del cuadrado central de los planos YUV de origen. El recorte se
 * calcula sobre las dimensiones de origen ([Image.getWidth]/[Image.getHeight],
 * antes de rotar) y no sobre las finales a propósito: un cuadrado centrado
 * en la imagen final, rotada 0/90/180/270 grados alrededor de su propio
 * centro, corresponde siempre al mismo cuadrado centrado en la imagen de
 * origen (incluida su definición de fila/columna) — rotar alrededor del
 * centro no mueve el centro, y un giro de múltiplo de 90° conserva la
 * forma cuadrada. Esta propiedad es la que hace que el recorte automático
 * (centrado) no necesite saber nada de rotación; un recorte elegido por el
 * usuario (RF-07) sí la rompe — `ui/CropScreen.kt` es quien convierte la
 * elección del usuario, hecha sobre el contenido ya rotado, de vuelta a
 * coordenadas de origen antes de llegar acá (ver
 * `displayedCropToNormalized`). Por eso [rotateSquareBitmap] sigue
 * aplicándose después del recorte, sobre el cuadrado ya chico, sin cambiar
 * qué píxeles de origen hacían falta. El cálculo del recorte en sí vive en
 * [SquareCrop], compartido con [ImageFrameDecoder].
 */
internal object YuvFrameConverter {

    fun toSquareBitmap(
        image: Image,
        rotationDegrees: Int,
        targetSize: Int,
        normalizedCrop: NormalizedCrop = NormalizedCrop.CENTERED,
    ): Bitmap {
        val cropped = yuv420CenterSquareToArgb(image, normalizedCrop)
        val rotated = if (rotationDegrees % 360 != 0) rotateSquareBitmap(cropped, rotationDegrees) else cropped
        return if (rotated.width == targetSize) {
            rotated
        } else {
            Bitmap.createScaledBitmap(rotated, targetSize, targetSize, /* filter = */ true)
        }
    }

    /** Extrae planos, strides y el recorte de [image] y delega en [NativeYuvConverter] (ADR-0011). */
    private fun yuv420CenterSquareToArgb(image: Image, normalizedCrop: NormalizedCrop): Bitmap {
        val crop = SquareCrop.of(image.width, image.height, normalizedCrop)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        return NativeYuvConverter.convert(
            yBuffer = yPlane.buffer,
            yRowStride = yPlane.rowStride,
            uBuffer = uPlane.buffer,
            uRowStride = uPlane.rowStride,
            uPixelStride = uPlane.pixelStride,
            vBuffer = vPlane.buffer,
            vRowStride = vPlane.rowStride,
            vPixelStride = vPlane.pixelStride,
            xOffset = crop.xOffset,
            yOffset = crop.yOffset,
            size = crop.size,
        )
    }

    /**
     * Implementación de referencia, ya no la ruta de producción (ver
     * [yuv420CenterSquareToArgb] / [NativeYuvConverter], ADR-0011). BT.601,
     * entero: misma fórmula que usan otras muestras de Android para
     * `YUV_420_888` (evita el costo de punto flotante por píxel sin
     * necesitar una tabla de conversión aparte). Las posiciones de los
     * planos U/V respetan strides y `pixelStride` propios: `YUV_420_888` no
     * garantiza que estén empaquetados de forma contigua. Convierte
     * únicamente el cuadrado `size × size` que empieza en
     * ([xOffset], [yOffset]), no `width × height`.
     *
     * `internal`, no `private`: la usa `YuvConversionParityTest`
     * (`app/src/androidTest`) para comparar, píxel a píxel y sobre los
     * mismos buffers sintéticos, contra [NativeYuvConverter.convert].
     */
    internal fun yuv420CenterSquareToArgbKotlinReference(
        yBuffer: ByteBuffer,
        yRowStride: Int,
        uBuffer: ByteBuffer,
        uRowStride: Int,
        uPixelStride: Int,
        vBuffer: ByteBuffer,
        vRowStride: Int,
        vPixelStride: Int,
        xOffset: Int,
        yOffset: Int,
        size: Int,
    ): Bitmap {
        val pixels = IntArray(size * size)
        for (row in 0 until size) {
            val sourceRow = row + yOffset
            val yRowStart = sourceRow * yRowStride
            val uvRow = sourceRow / 2
            val uRowStart = uvRow * uRowStride
            val vRowStart = uvRow * vRowStride
            var pixelIndex = row * size
            for (col in 0 until size) {
                val sourceCol = col + xOffset
                val y = (yBuffer.get(yRowStart + sourceCol).toInt() and 0xFF) - 16
                val uvCol = sourceCol / 2
                val u = (uBuffer.get(uRowStart + uvCol * uPixelStride).toInt() and 0xFF) - 128
                val v = (vBuffer.get(vRowStart + uvCol * vPixelStride).toInt() and 0xFF) - 128

                val yScaled = 298 * y
                val r = ((yScaled + 409 * v + 128) shr 8).coerceIn(0, 255)
                val g = ((yScaled - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
                val b = ((yScaled + 516 * u + 128) shr 8).coerceIn(0, 255)

                pixels[pixelIndex] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                pixelIndex++
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
