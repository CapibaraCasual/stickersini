package io.github.capibaracasual.stickersini.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.capibaracasual.stickersini.ui.theme.CoralShadow
import io.github.capibaracasual.stickersini.ui.theme.Ink
import io.github.capibaracasual.stickersini.ui.theme.Radius
import java.util.Locale

/**
 * Cabecera compartida de las pantallas del flujo de creación y de gestión
 * de packs: chevron de volver (dibujado a mano, no un ícono de Material —
 * evita sumar `material-icons-extended` solo por esta flecha) más título en
 * Fredoka (`titleLarge`, ver `ui/theme/Type.kt`). Reemplaza el
 * `TextButton(onClick = onBack) { Text("← Volver") }` que se repetía
 * idéntico en cada pantalla desde antes del diseño visual (dirección
 * "Plancha de stickers", elegida el 2026-09-27).
 */
@Composable
fun StickerScreenHeader(title: String, backLabel: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = backLabel }) {
            BackChevron()
        }
        Text(text = title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun BackChevron(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(22.dp)) {
        val strokeWidthPx = 2.4.dp.toPx()
        val path = Path().apply {
            moveTo(size.width * 0.625f, size.height * 0.208f)
            lineTo(size.width * 0.333f, size.height * 0.5f)
            lineTo(size.width * 0.625f, size.height * 0.792f)
        }
        drawPath(
            path,
            color = Ink,
            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * Botón de acción principal con el relieve "sticker apretable" de la
 * dirección visual elegida: una sombra plana y sólida (no difuminada, a
 * diferencia de la elevación por defecto de Material 3) que da la
 * sensación de una etiqueta de plástico gruesa, no de un botón
 * administrativo. Un solo estilo para toda la app: "Elegir archivo",
 * "Continuar" y "Guardar sticker" son la misma pieza con texto distinto.
 */
@Composable
fun StickerPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(Radius.button)
    Box(modifier = modifier.height(62.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .align(Alignment.BottomStart)
                .background(if (enabled) CoralShadow else CoralShadow.copy(alpha = 0.4f), shape),
        )
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
            contentPadding = PaddingValues(horizontal = 24.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp).align(Alignment.TopStart),
        ) {
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Marco "recortado" de la dirección visual: una tarjeta blanca con sombra
 * suave y un anillo de 6dp alrededor de un interior de color — la misma
 * forma que un sticker recién pelado de una lámina. Reutilizado para toda
 * vista previa de contenido (miniatura de video en `TrimScreen`, vista
 * previa del sticker convertido en `ConvertPreviewSaveScreen`): una sola
 * pieza en vez de repetir el mismo `Box` anidado en cada pantalla.
 */
@Composable
fun StickerDieCutFrame(
    modifier: Modifier = Modifier,
    innerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Radius.card)
    Box(
        modifier = modifier
            .shadow(elevation = 10.dp, shape = shape, clip = false)
            .background(MaterialTheme.colorScheme.surface, shape)
            .padding(6.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().clip(shape).background(innerColor),
            content = content,
        )
    }
}

/**
 * Insignia en Fredoka sobre una píldora de color, como la de "Animado" en
 * la vista previa de guardado o "Semilla" en la lista de packs. Con
 * [outlined] en `true` es un borde sin relleno del mismo color en vez de una
 * píldora sólida — para un recordatorio ("todavía falta hacer esto") que no
 * debe leerse con el mismo peso que un estado ya cumplido.
 */
@Composable
fun StickerBadge(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondary,
    contentColor: Color = MaterialTheme.colorScheme.onSecondary,
    outlined: Boolean = false,
) {
    val shape = RoundedCornerShape(Radius.chip)
    val background = if (outlined) Color.Transparent else containerColor
    val foreground = if (outlined) containerColor else contentColor
    Box(
        modifier = modifier
            .background(background, shape)
            .then(if (outlined) Modifier.border(1.5.dp, containerColor, shape) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.titleSmall, color = foreground)
    }
}

/**
 * Avatar de pack: la inicial del nombre sobre un círculo de color — sin
 * ícono propio por pack todavía (eso depende de los seis stickers semilla
 * definitivos, ver README "Qué falta"). Compartido entre el selector de
 * pack de `ConvertPreviewSaveScreen` y las filas de `PackListScreen`.
 */
@Composable
fun PackAvatar(name: String, modifier: Modifier = Modifier, size: Dp? = 56.dp) {
    val sized = if (size != null) modifier.size(size) else modifier
    Box(
        modifier = sized.background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(1).uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}
