package io.github.capibaracasual.stickersini.stickers.domain

/**
 * Identificadores de los dos packs semilla (RF-17, ADR-0004): uno estático y
 * uno animado, porque RF-18 prohíbe mezclar ambos tipos en un mismo pack —
 * consecuencia que ADR-0004 ya preveía ("obliga a gestionar al menos dos
 * packs por defecto"). Hasta que exista una UI de packs propios (RF-15),
 * son también el único destino posible de un sticker nuevo: agregarlo a uno
 * de estos dos, ya válidos desde la instalación, evita el problema de un
 * pack propio que empieza con menos de 3 stickers y que WhatsApp rechaza.
 */
object SeedPacks {
    const val STATIC_IDENTIFIER = "sticker_pack_semilla"
    const val STATIC_NAME = "Stickersini"
    const val ANIMATED_IDENTIFIER = "sticker_pack_semilla_animado"
    const val ANIMATED_NAME = "Stickersini animado"
}
