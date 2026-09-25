package io.github.capibaracasual.stickersini.webp

/**
 * Avance real de [WebpAnimEncoder.encode]: no hay forma de saber de antemano
 * cuántos intentos hará (depende del contenido), pero sí cuánto del
 * presupuesto de tiempo de RNF-08 ya se gastó — esa fracción
 * ([elapsedMs] / [hardTimeLimitMs]) es la que la UI puede mostrar como
 * avance determinado en vez de un giro sin información.
 */
data class EncodeAttemptProgress(
    val attemptNumber: Int,
    val elapsedMs: Long,
    val hardTimeLimitMs: Long,
)
