package io.github.capibaracasual.stickersini.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import io.github.capibaracasual.stickersini.R
import io.github.capibaracasual.stickersini.media.ImageDecodeException
import io.github.capibaracasual.stickersini.media.NormalizedCrop
import io.github.capibaracasual.stickersini.media.VideoDecodeException
import io.github.capibaracasual.stickersini.media.calculateInSampleSize
import io.github.capibaracasual.stickersini.media.displayedCropToNormalized
import io.github.capibaracasual.stickersini.media.readImageOrientationDegrees
import io.github.capibaracasual.stickersini.media.rotateSquareBitmap
import io.github.capibaracasual.stickersini.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Resolución de la vista previa de recorte: pensada para la interacción (pellizco/arrastre), no para el sticker final. */
private const val CROP_PREVIEW_TARGET_SIZE = 1024

/** Ningún recorte puede achicarse más allá de esta fracción del lado disponible: evita un pellizco degenerado (un sticker de un puñado de píxeles). */
private const val MIN_CROP_FRACTION = 0.2f

/**
 * Lo que hace falta para elegir un recorte: un fotograma o foto ya en la
 * orientación mostrada ([preview]), más las dimensiones reales del
 * contenido *antes* de rotar ([sourceWidth], [sourceHeight]) y la propia
 * rotación ([rotationDegrees]) — lo que [displayedCropToNormalized]
 * necesita para convertir la elección del usuario de vuelta a coordenadas
 * de origen.
 */
private class CropSource(
    val preview: Bitmap,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val rotationDegrees: Int,
)

/**
 * RF-07: elegir con pellizco (cambia el tamaño) y arrastre (mueve la
 * ventana) qué cuadrado del contenido se convierte en sticker. Por defecto
 * arranca en el mismo cuadrado centrado que ya daba el recorte automático
 * (RF-07 no cambia ese valor por defecto, solo agrega la posibilidad de
 * moverlo). Sin recorte de fondo todavía (RF-08).
 *
 * @param videoStartMs con qué fotograma previsualizar cuando [isVideo] es
 * `true` (el tramo ya elegido en `ui/TrimScreen.kt`); sin efecto si es imagen.
 */
@Composable
fun CropScreen(
    uri: Uri,
    isVideo: Boolean,
    videoStartMs: Long,
    onBack: () -> Unit,
    onContinue: (NormalizedCrop) -> Unit,
) {
    val context = LocalContext.current

    var source by remember { mutableStateOf<CropSource?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var cropSize by remember { mutableFloatStateOf(0f) }
    var cropOffsetX by remember { mutableFloatStateOf(0f) }
    var cropOffsetY by remember { mutableFloatStateOf(0f) }
    var containerSizePx by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(uri, videoStartMs) {
        try {
            val loaded = withContext(Dispatchers.Default) {
                if (isVideo) loadVideoCropSource(context, uri, videoStartMs) else loadImageCropSource(context, uri)
            }
            source = loaded
            val maxSize = minOf(loaded.preview.width, loaded.preview.height).toFloat()
            cropSize = maxSize
            cropOffsetX = (loaded.preview.width - maxSize) / 2f
            cropOffsetY = (loaded.preview.height - maxSize) / 2f
        } catch (error: Exception) {
            errorMessage = error.message ?: error.toString()
        }
    }

    // "Continuar" vive en bottomBar, no al final de la Column: un contenido
    // vertical (video grabado en mano, retrato) hace que el cuadro de
    // recorte de abajo pida más alto que ancho, y si el botón fuera el
    // último elemento de una Column sin scroll quedaba empujado fuera de
    // pantalla, sin forma de llegar a él. El bottomBar de Scaffold no
    // depende de cuánto mida el contenido de arriba.
    val currentSource = source

    Scaffold(
        bottomBar = {
            if (currentSource != null) {
                Button(
                    onClick = {
                        val normalized = displayedCropToNormalized(
                            displayedX = cropOffsetX,
                            displayedY = cropOffsetY,
                            displayedSize = cropSize,
                            sourceWidth = currentSource.sourceWidth.toFloat(),
                            sourceHeight = currentSource.sourceHeight.toFloat(),
                            rotationDegrees = currentSource.rotationDegrees,
                        )
                        onContinue(normalized)
                    },
                    modifier = Modifier.fillMaxWidth().padding(Spacing.large),
                ) {
                    Text(text = stringResource(R.string.crop_continue_button))
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            TextButton(onClick = onBack) { Text(text = stringResource(R.string.create_sticker_back)) }
            Text(text = stringResource(R.string.crop_title), style = MaterialTheme.typography.titleLarge)

            errorMessage?.let { message ->
                Text(text = stringResource(R.string.create_sticker_error, message), style = MaterialTheme.typography.bodyMedium)
            }

            if (currentSource == null) {
                if (errorMessage == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(text = stringResource(R.string.crop_loading), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                val minCropSize = MIN_CROP_FRACTION * minOf(currentSource.preview.width, currentSource.preview.height)
                val scale = if (containerSizePx.width > 0) containerSizePx.width / currentSource.preview.width.toFloat() else 0f

                // El cuadro de recorte ocupa como mucho el espacio que le
                // queda a la Column (weight(1f)) y, dentro de eso, se ajusta
                // para entrar tanto a lo ancho como a lo alto (igual que
                // ContentScale.Fit): la proporción del contenido nunca
                // empuja al botón de "Continuar" fuera de pantalla, sea
                // paisaje o retrato.
                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val contentAspect = currentSource.preview.width.toFloat() / currentSource.preview.height.toFloat()
                    val maxWidthPx = constraints.maxWidth.toFloat()
                    val maxHeightPx = constraints.maxHeight.toFloat()
                    var boxWidthPx = maxWidthPx
                    var boxHeightPx = boxWidthPx / contentAspect
                    if (boxHeightPx > maxHeightPx) {
                        boxHeightPx = maxHeightPx
                        boxWidthPx = boxHeightPx * contentAspect
                    }
                    val density = LocalDensity.current
                    val boxWidthDp = with(density) { boxWidthPx.toDp() }
                    val boxHeightDp = with(density) { boxHeightPx.toDp() }

                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(boxWidthDp, boxHeightDp)
                            .onSizeChanged { containerSizePx = it }
                            .pointerInput(currentSource) {
                                detectTransformGestures { centroid, pan, zoom, _ ->
                                    if (containerSizePx.width == 0) return@detectTransformGestures
                                    val gestureScale = containerSizePx.width / currentSource.preview.width.toFloat()
                                    val panContent = pan / gestureScale

                                    var offsetX = cropOffsetX + panContent.x
                                    var offsetY = cropOffsetY + panContent.y
                                    var size = cropSize

                                    if (zoom != 1f) {
                                        val focalX = centroid.x / gestureScale
                                        val focalY = centroid.y / gestureScale
                                        val maxSize = minOf(currentSource.preview.width, currentSource.preview.height).toFloat()
                                        val newSize = (size / zoom).coerceIn(minCropSize, maxSize)
                                        offsetX = focalX - (focalX - offsetX) * (newSize / size)
                                        offsetY = focalY - (focalY - offsetY) * (newSize / size)
                                        size = newSize
                                    }

                                    cropSize = size
                                    cropOffsetX = offsetX.coerceIn(0f, (currentSource.preview.width - size).coerceAtLeast(0f))
                                    cropOffsetY = offsetY.coerceIn(0f, (currentSource.preview.height - size).coerceAtLeast(0f))
                                }
                            },
                    ) {
                        Image(bitmap = currentSource.preview.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
                        val primaryColor = MaterialTheme.colorScheme.primary
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val boxX = cropOffsetX * scale
                            val boxY = cropOffsetY * scale
                            val boxSize = cropSize * scale
                            val scrim = Color.Black.copy(alpha = 0.55f)
                            drawRect(scrim, topLeft = Offset(0f, 0f), size = Size(size.width, boxY))
                            drawRect(scrim, topLeft = Offset(0f, boxY + boxSize), size = Size(size.width, size.height - boxY - boxSize))
                            drawRect(scrim, topLeft = Offset(0f, boxY), size = Size(boxX, boxSize))
                            drawRect(scrim, topLeft = Offset(boxX + boxSize, boxY), size = Size(size.width - boxX - boxSize, boxSize))
                            drawRect(
                                color = primaryColor,
                                topLeft = Offset(boxX, boxY),
                                size = Size(boxSize, boxSize),
                                style = Stroke(width = 4f),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun loadVideoCropSource(context: Context, uri: Uri, atMs: Long): CropSource {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val frame = retriever.getFrameAtTime(atMs * 1_000, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: throw VideoDecodeException("No se pudo leer un fotograma para elegir el recorte")
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
        // Reserva si el contenedor no informa ancho/alto (raro): asume
        // rotación 0 y las dimensiones del propio fotograma ya mostrado,
        // mismo criterio de "mejor esfuerzo" que ya usa TrimScreen con la
        // duración cuando el contenedor tampoco la informa.
        if (width == null || height == null || width <= 0 || height <= 0) {
            CropSource(frame, frame.width, frame.height, rotationDegrees = 0)
        } else {
            CropSource(frame, width, height, rotationDegrees = rotation ?: 0)
        }
    } finally {
        retriever.release()
    }
}

private fun loadImageCropSource(context: Context, uri: Uri): CropSource {
    val rotationDegrees = readImageOrientationDegrees(context, uri)

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { stream -> BitmapFactory.decodeStream(stream, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        throw ImageDecodeException("No se pudieron leer las dimensiones de $uri")
    }

    val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, CROP_PREVIEW_TARGET_SIZE)
    val sampled = context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
    } ?: throw ImageDecodeException("No se pudo decodificar $uri")

    val displayed = if (rotationDegrees % 360 != 0) rotateSquareBitmap(sampled, rotationDegrees) else sampled
    return CropSource(displayed, sampled.width, sampled.height, rotationDegrees)
}
