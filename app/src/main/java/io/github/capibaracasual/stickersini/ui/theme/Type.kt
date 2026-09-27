package io.github.capibaracasual.stickersini.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import io.github.capibaracasual.stickersini.R

/**
 * Fredoka (títulos, botones) y Karla (cuerpo de texto): fuentes variables de
 * Google Fonts (OFL-1.1, compatible con GPL-3.0 — RNF-10), vendorizadas en
 * `res/font` porque la app no declara `INTERNET` (RNF-01) y no puede
 * resolver una fuente descargable en tiempo de ejecución. Licencias
 * completas en `app/src/main/assets/licenses/`.
 *
 * Ambas son fuentes de ejes variables sin instancias estáticas publicadas:
 * cada peso se obtiene con [FontVariation.Settings] sobre el mismo archivo
 * (soportado desde API 26, igual al `minSdk` del proyecto), no con un
 * archivo por peso.
 */
@OptIn(ExperimentalTextApi::class)
private val Fredoka = FontFamily(
    Font(
        R.font.fredoka,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
)

@OptIn(ExperimentalTextApi::class)
private val Karla = FontFamily(
    Font(
        R.font.karla,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.karla,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
)

/**
 * Roles de Material 3 reutilizados tal cual (mismos tamaños/alturas de
 * línea por defecto): Fredoka en títulos y botones (`labelLarge`, el estilo
 * que usa `Button` de forma implícita), Karla en todo lo demás. Una pantalla
 * que necesite la variante en negrita de un rol de cuerpo (por ejemplo, la
 * etiqueta de un chip seleccionado) parte de `.copy(fontWeight =
 * FontWeight.Bold)`, no de un rol nuevo.
 */
val StickersiniTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = Fredoka, fontWeight = FontWeight.SemiBold),
        displayMedium = base.displayMedium.copy(fontFamily = Fredoka, fontWeight = FontWeight.SemiBold),
        displaySmall = base.displaySmall.copy(fontFamily = Fredoka, fontWeight = FontWeight.SemiBold),
        headlineLarge = base.headlineLarge.copy(fontFamily = Fredoka, fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontFamily = Fredoka, fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontFamily = Fredoka, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontFamily = Fredoka, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = Fredoka, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontFamily = Fredoka, fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontFamily = Fredoka, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(fontFamily = Karla, fontWeight = FontWeight.Normal),
        bodyMedium = base.bodyMedium.copy(fontFamily = Karla, fontWeight = FontWeight.Normal),
        bodySmall = base.bodySmall.copy(fontFamily = Karla, fontWeight = FontWeight.Normal),
        labelMedium = base.labelMedium.copy(fontFamily = Karla, fontWeight = FontWeight.Normal),
        labelSmall = base.labelSmall.copy(fontFamily = Karla, fontWeight = FontWeight.Normal),
    )
}
