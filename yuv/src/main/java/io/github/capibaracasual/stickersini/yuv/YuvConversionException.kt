package io.github.capibaracasual.stickersini.yuv

/** Lanzada desde la capa nativa (`yuv_jni.c`) si la conversión no pudo completarse. */
class YuvConversionException(message: String) : RuntimeException(message)
