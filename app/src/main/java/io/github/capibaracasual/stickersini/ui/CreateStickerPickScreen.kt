package io.github.capibaracasual.stickersini.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.capibaracasual.stickersini.R
import io.github.capibaracasual.stickersini.ui.theme.Radius
import io.github.capibaracasual.stickersini.ui.theme.Spacing

/**
 * Primer paso del flujo de creación (RF-02/RF-03): elegir un video o una
 * imagen del almacenamiento. No decodifica ni convierte nada — solo
 * entrega el [Uri] elegido y si es video a [onPicked], que decide a qué
 * pantalla sigue (tramo si es video, conversión directa si es imagen: ver
 * [io.github.capibaracasual.stickersini.ui.StickersiniNavHost]).
 */
@Composable
fun CreateStickerPickScreen(onBack: () -> Unit, onPicked: (uri: Uri, isVideo: Boolean) -> Unit) {
    val context = LocalContext.current

    val pickMedia = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val mimeType = context.contentResolver.getType(uri).orEmpty()
        onPicked(uri, mimeType.startsWith("video/"))
    }

    Scaffold(
        bottomBar = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.large),
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                StickerPrimaryButton(
                    text = stringResource(R.string.create_sticker_pick_button),
                    onClick = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.create_sticker_pick_reassurance),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.large)) {
            StickerScreenHeader(
                title = stringResource(R.string.create_sticker_title),
                backLabel = stringResource(R.string.create_sticker_back),
                onBack = onBack,
            )

            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                PickIllustration()
            }
        }
    }
}

/**
 * Dos "stickers" de papel superpuestos, uno de menta detrás y uno coral
 * adelante con un glifo de foto/video adentro: no ilustra un contenido
 * real todavía (los seis stickers semilla definitivos son trabajo
 * pendiente, ver README "Qué falta") pero deja ver, desde esta primera
 * pantalla, la misma lámina de stickers que el resto del recorrido.
 */
@Composable
private fun PickIllustration() {
    Box(modifier = Modifier.size(220.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(160.dp)
                .rotate(-10f)
                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(Radius.card)),
        )
        Box(
            modifier = Modifier
                .size(184.dp)
                .rotate(6f)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Radius.card))
                .padding(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(Radius.card)),
                contentAlignment = Alignment.Center,
            ) {
                MediaGlyph(color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun MediaGlyph(color: Color) {
    Canvas(modifier = Modifier.size(72.dp)) {
        val strokeWidth = 3.dp.toPx()
        val inset = size.width * 0.12f
        drawRoundRect(
            color = color,
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2, size.height - inset * 2),
            cornerRadius = CornerRadius(size.width * 0.14f),
            style = Stroke(width = strokeWidth),
        )
        val triSize = size.width * 0.28f
        val cx = size.width / 2f
        val cy = size.height / 2f
        val path = Path().apply {
            moveTo(cx - triSize * 0.4f, cy - triSize * 0.55f)
            lineTo(cx - triSize * 0.4f, cy + triSize * 0.55f)
            lineTo(cx + triSize * 0.6f, cy)
            close()
        }
        drawPath(path, color = color)
    }
}
