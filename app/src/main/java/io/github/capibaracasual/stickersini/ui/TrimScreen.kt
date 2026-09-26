package io.github.capibaracasual.stickersini.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.capibaracasual.stickersini.R
import io.github.capibaracasual.stickersini.media.MAX_CLIP_DURATION_MS
import io.github.capibaracasual.stickersini.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * RF-06: elegir el tramo del video de origen (hasta [MAX_CLIP_DURATION_MS])
 * que se va a convertir. Un `RangeSlider` de Material 3 sobre la duración
 * real del video (leída con [MediaMetadataRetriever], no asumida): mover un
 * extremo desplaza el otro lo necesario para no superar el ancho máximo,
 * nunca lo deja crecer más allá ([clampWindow]). Sin recorte de área propio
 * todavía (RF-07 queda pendiente): el resultado sigue recortando al
 * cuadrado central.
 */
@Composable
fun TrimScreen(
    uri: Uri,
    initialStartMs: Long,
    initialDurationMs: Long,
    onBack: () -> Unit,
    onContinue: (startMs: Long, durationMs: Long) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var videoDurationMs by remember { mutableStateOf<Long?>(null) }
    var range by remember { mutableStateOf(0f..0f) }
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uri) {
        val duration = withContext(Dispatchers.Default) { readDurationMs(context, uri) }
        videoDurationMs = duration
        val initialEnd = (initialStartMs + initialDurationMs).coerceAtMost(duration)
        range = initialStartMs.toFloat()..initialEnd.toFloat()
        thumbnail = withContext(Dispatchers.Default) { frameAt(context, uri, initialStartMs) }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            TextButton(onClick = onBack) { Text(text = stringResource(R.string.create_sticker_back)) }
            Text(text = stringResource(R.string.trim_title), style = MaterialTheme.typography.titleLarge)

            val duration = videoDurationMs
            if (duration == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(text = stringResource(R.string.trim_loading), style = MaterialTheme.typography.bodyMedium)
            } else {
                thumbnail?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(Spacing.small)),
                    )
                }

                val maxWidthMs = MAX_CLIP_DURATION_MS.coerceAtMost(duration)
                Text(
                    text = stringResource(
                        R.string.trim_selected_range,
                        formatMs(range.start.toLong()),
                        formatMs(range.endInclusive.toLong()),
                        formatMs((range.endInclusive - range.start).toLong()),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                RangeSlider(
                    value = range,
                    onValueChange = { newRange -> range = clampWindow(range, newRange, maxWidthMs.toFloat(), duration.toFloat()) },
                    onValueChangeFinished = {
                        val startMs = range.start.toLong()
                        scope.launch { thumbnail = withContext(Dispatchers.Default) { frameAt(context, uri, startMs) } }
                    },
                    valueRange = 0f..duration.toFloat(),
                )

                Button(
                    onClick = {
                        val startMs = range.start.toLong()
                        val durationMs = (range.endInclusive - range.start).toLong().coerceAtLeast(1L)
                        onContinue(startMs, durationMs)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.trim_continue_button))
                }
            }
        }
    }
}

/**
 * Mantiene `new.endInclusive - new.start` acotado a [maxWidth]: si el
 * extremo que se movió (detectado comparando contra [old]) hizo crecer la
 * ventana más allá del tope, empuja el *otro* extremo para volver al
 * límite, sin dejar que el que el usuario está arrastrando salte de lugar.
 */
private fun clampWindow(
    old: ClosedFloatingPointRange<Float>,
    new: ClosedFloatingPointRange<Float>,
    maxWidth: Float,
    upperBound: Float,
): ClosedFloatingPointRange<Float> {
    val startMoved = new.start != old.start
    var start = new.start
    var end = new.endInclusive
    if (end - start > maxWidth) {
        if (startMoved) {
            end = (start + maxWidth).coerceAtMost(upperBound)
        } else {
            start = (end - maxWidth).coerceAtLeast(0f)
        }
    }
    return start..end
}

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}

/** Duración real del video, o [MAX_CLIP_DURATION_MS] si el contenedor no la informa (mejor esfuerzo). */
private fun readDurationMs(context: Context, uri: Uri): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        raw.takeIf { it > 0 } ?: MAX_CLIP_DURATION_MS
    } finally {
        retriever.release()
    }
}

private fun frameAt(context: Context, uri: Uri, atMs: Long): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        retriever.getFrameAtTime(atMs * 1_000, MediaMetadataRetriever.OPTION_CLOSEST)
    } finally {
        retriever.release()
    }
}
