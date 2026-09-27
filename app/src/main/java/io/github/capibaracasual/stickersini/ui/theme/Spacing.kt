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

/**
 * Escala de radios de esquina, separada de [Spacing]: la dirección visual
 * "Plancha de stickers" (elegida el 2026-09-27) usa esquinas mucho más
 * redondeadas que las que daría reutilizar la escala de espaciado como
 * radio (como hacían `ConvertPreviewSaveScreen` y `PackListScreen` antes de
 * esto, con `RoundedCornerShape(Spacing.small)`).
 */
object Radius {
    val chip = 999.dp
    val card = 28.dp
    val button = 20.dp
    val small = 12.dp
}
