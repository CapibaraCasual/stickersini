package io.github.capibaracasual.stickersini.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Coral,
    onPrimary = CardWhite,
    primaryContainer = CoralTint,
    onPrimaryContainer = Ink,
    secondary = Mint,
    onSecondary = CardWhite,
    secondaryContainer = MintTint,
    onSecondaryContainer = MintShadowText,
    background = Paper,
    onBackground = Ink,
    surface = CardWhite,
    onSurface = Ink,
    surfaceVariant = PaperTint,
    onSurfaceVariant = InkMuted,
    outline = Outline,
    outlineVariant = OutlineVariant,
    error = Error,
    onError = CardWhite,
    errorContainer = ErrorTint,
    onErrorContainer = Error,
)

// Misma paleta de acento, superficies invertidas: no es un rediseño propio
// para modo oscuro, solo evita que el sistema fuerce los colores por
// defecto de Material 3 si el usuario tiene el tema oscuro activado.
private val DarkColors = darkColorScheme(
    primary = Coral,
    onPrimary = CardWhite,
    primaryContainer = CoralShadow,
    onPrimaryContainer = CardWhite,
    secondary = Mint,
    onSecondary = CardWhite,
    secondaryContainer = MintShadowText,
    onSecondaryContainer = MintTint,
    background = Ink,
    onBackground = Paper,
    surface = Color(0xFF3A342C),
    onSurface = Paper,
    error = Error,
    onError = CardWhite,
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.small),
    small = RoundedCornerShape(Radius.small),
    medium = RoundedCornerShape(Radius.button),
    large = RoundedCornerShape(Radius.card),
    extraLarge = RoundedCornerShape(Radius.chip),
)

@Composable
fun StickersiniTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, typography = StickersiniTypography, shapes = AppShapes, content = content)
}
