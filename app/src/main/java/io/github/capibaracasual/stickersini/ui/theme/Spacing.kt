package io.github.capibaracasual.stickersini.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Escala de espaciado única para toda la interfaz: reemplaza los `24.dp`,
 * `16.dp`, `8.dp` sueltos que antes se repetían por pantalla, con el
 * riesgo de que diverjan sin que nadie lo note.
 */
object Spacing {
    val small = 8.dp
    val medium = 16.dp
    val large = 24.dp
}
