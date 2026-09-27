package io.github.capibaracasual.stickersini.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.capibaracasual.stickersini.R
import io.github.capibaracasual.stickersini.ui.theme.Radius
import io.github.capibaracasual.stickersini.ui.theme.Spacing
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pantalla de inicio: la acción principal es crear un sticker, no
 * administrar el pack semilla de Fase 0 (esa validación ya está cerrada,
 * ver ADR-0004 y README "Qué funciona ya"). Agregar un pack a WhatsApp deja
 * de tener botón propio acá — es la misma acción que ya existe, por cada
 * pack, en `PackDetailScreen` y en la tarjeta de éxito de
 * `ConvertPreviewSaveScreen`; tenerla también suelta en el inicio era la
 * acción triplicada en un lugar que ya no le corresponde.
 */
@Composable
fun AddSeedPackScreen(onCreateSticker: () -> Unit, onManagePacks: () -> Unit) {
    Scaffold(
        bottomBar = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.large),
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StickerPrimaryButton(
                    text = stringResource(R.string.create_sticker_button),
                    onClick = onCreateSticker,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = onManagePacks) {
                    Text(text = stringResource(R.string.manage_packs_button))
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.large)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.large),
            )

            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                HomeIllustration()
                Spacer(modifier = Modifier.height(Spacing.large))
                Text(
                    text = stringResource(R.string.home_tagline),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Tres "stickers" de papel en abanico: la misma lámina que ya se insinuaba
 * en `CreateStickerPickScreen`, ahora como la imagen de marca de la
 * portada. El del centro lleva una estrella en vez del glifo de foto/video
 * de esa otra pantalla — acá no se está por elegir un archivo, se está
 * mostrando qué es la app.
 */
@Composable
private fun HomeIllustration() {
    Box(modifier = Modifier.size(240.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(150.dp)
                .offset(x = (-30).dp)
                .rotate(-16f)
                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(Radius.card)),
        )
        Box(
            modifier = Modifier
                .size(150.dp)
                .offset(x = 30.dp)
                .rotate(14f)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(Radius.card)),
        )
        Box(
            modifier = Modifier
                .size(180.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Radius.card))
                .padding(8.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(Radius.card)),
                contentAlignment = Alignment.Center,
            ) {
                StarGlyph(color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun StarGlyph(color: Color) {
    Canvas(modifier = Modifier.size(80.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val outerRadius = size.minDimension / 2f
        val innerRadius = outerRadius * 0.42f
        val points = 5
        val path = Path().apply {
            for (i in 0 until points * 2) {
                val radius = if (i % 2 == 0) outerRadius else innerRadius
                val angle = (PI / points * i) - PI / 2
                val x = cx + (radius * cos(angle)).toFloat()
                val y = cy + (radius * sin(angle)).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        drawPath(path, color = color)
    }
}
