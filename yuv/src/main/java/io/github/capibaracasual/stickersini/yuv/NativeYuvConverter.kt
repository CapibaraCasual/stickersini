package io.github.capibaracasual.stickersini.yuv

import android.graphics.Bitmap
import java.nio.ByteBuffer

/**
 * Convierte el cuadrado centrado de un plano `YUV_420_888` a un `Bitmap`
 * `ARGB_8888`, en C vía JNI (ADR-0011): medido que la implementación en
 * Kotlin puro (`YuvFrameConverter` en `:app`, que sigue existiendo como
 * referencia) es el 54-63% del tiempo de decodificar un video, y crece con
 * la duración del clip — el cuello de botella real, no el resto del
 * decode.
 *
 * Módulo aparte, no dentro de `:webp`: mantiene a `:webp` como "solo
 * codificación WebP" (ver CLAUDE.md) y evita meter configuración de NDK
 * dentro de `:app`, que es donde vive la UI. `:app` depende de `:yuv` igual
 * que depende de `:webp`.
 */
object NativeYuvConverter {

    init {
        System.loadLibrary("stickersini_yuv")
    }

    /**
     * @param yBuffer,uBuffer,vBuffer deben ser [ByteBuffer.isDirect] — los
     * planos de un [android.media.Image] real siempre lo son; la capa
     * nativa no puede leer un buffer de heap.
     * @param xOffset,yOffset esquina superior izquierda del cuadrado a
     * convertir, en coordenadas del plano Y de origen.
     * @param size lado del cuadrado a convertir y del bitmap resultante.
     * @throws YuvConversionException si la capa nativa no pudo completar la
     * conversión (buffers no directos, bitmap de salida inválido).
     */
    fun convert(
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
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        nativeConvert(
            bitmap,
            yBuffer, yRowStride,
            uBuffer, uRowStride, uPixelStride,
            vBuffer, vRowStride, vPixelStride,
            xOffset, yOffset, size,
        )
        return bitmap
    }

    @JvmStatic
    private external fun nativeConvert(
        bitmap: Bitmap,
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
    )

    /**
     * Reescala [source] a [targetSize]×[targetSize] con bicúbico
     * Catmull-Rom, en C vía JNI (ADR-0022): reemplaza el bilineal de
     * `Bitmap.createScaledBitmap` en el recorte+zoom (RF-07,
     * `YuvFrameConverter`/`ImageFrameDecoder` en `:app`) — medido que
     * bilineal se ve notoriamente más borroso al ampliar un recorte más
     * chico que 512×512 (el caso típico al hacer zoom). Devuelve [source]
     * sin tocar si ya mide [targetSize] — no tiene sentido reescalar para
     * quedar igual.
     */
    fun resizeBicubic(source: Bitmap, targetSize: Int): Bitmap {
        if (source.width == targetSize && source.height == targetSize) return source
        val destination = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        nativeResizeBicubic(source, destination)
        return destination
    }

    @JvmStatic
    private external fun nativeResizeBicubic(source: Bitmap, destination: Bitmap)

    /**
     * Suavizado gaussiano 3×3 liviano de [source] (ADR-0022, investigación):
     * ver KDoc de `nativeLightBlur` en `yuv_jni.c`. No es una vía de
     * producción — solo para medir si reduce el bloqueo visible en
     * contenido adverso real a calidad muy baja.
     */
    fun lightBlur(source: Bitmap): Bitmap {
        val destination = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        nativeLightBlur(source, destination)
        return destination
    }

    @JvmStatic
    private external fun nativeLightBlur(source: Bitmap, destination: Bitmap)
}
