package io.github.capibaracasual.stickersini.webp

import android.graphics.Bitmap

/**
 * Un fotograma de entrada para [WebpAnimEncoder]. [bitmap] debe ser
 * `ARGB_8888` y del mismo tamaño que el resto de fotogramas del conjunto.
 */
data class WebpFrame(val bitmap: Bitmap, val durationMs: Int)
