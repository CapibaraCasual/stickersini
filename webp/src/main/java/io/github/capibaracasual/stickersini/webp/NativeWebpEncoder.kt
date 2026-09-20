package io.github.capibaracasual.stickersini.webp

/**
 * Puente JNI hacia `webp/src/main/cpp/jni/webp_jni.c`. Una sola pasada de
 * codificación, sin ajuste: ver [SingleShotWebpEncoder].
 */
internal object NativeWebpEncoder : SingleShotWebpEncoder {

    init {
        System.loadLibrary("stickersini_webp")
    }

    override fun encode(frames: List<WebpFrame>, quality: Int): ByteArray {
        val bitmaps = Array(frames.size) { frames[it].bitmap }
        val durationsMs = IntArray(frames.size) { frames[it].durationMs }
        return nativeEncode(bitmaps, durationsMs, quality)
    }

    @JvmStatic
    private external fun nativeEncode(
        bitmaps: Array<android.graphics.Bitmap>,
        durationsMs: IntArray,
        quality: Int,
    ): ByteArray
}
