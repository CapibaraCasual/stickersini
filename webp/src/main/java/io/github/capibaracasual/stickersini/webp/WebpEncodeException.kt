package io.github.capibaracasual.stickersini.webp

/**
 * No se pudo producir un WebP animado dentro de los límites de RF-10/RF-13,
 * ni codificado por el lado nativo ni por agotarse el ajuste de RF-12.
 */
class WebpEncodeException(message: String) : Exception(message)
