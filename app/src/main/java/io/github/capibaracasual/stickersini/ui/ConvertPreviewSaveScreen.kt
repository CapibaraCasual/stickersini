package io.github.capibaracasual.stickersini.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.capibaracasual.stickersini.R
import io.github.capibaracasual.stickersini.media.ConversionStage
import io.github.capibaracasual.stickersini.media.NormalizedCrop
import io.github.capibaracasual.stickersini.media.StickerConversionPipeline
import io.github.capibaracasual.stickersini.stickers.data.PackChoice
import io.github.capibaracasual.stickersini.stickers.data.StickerPackRepository
import io.github.capibaracasual.stickersini.stickers.domain.ManagedStickerPack
import io.github.capibaracasual.stickersini.ui.theme.Spacing
import io.github.capibaracasual.stickersini.webp.WebpEncodeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Último tramo del flujo de creación: convierte ([startMs]/[durationMs] ya
 * elegidos en `ui/TrimScreen.kt` — RF-06 — y [crop] en `ui/CropScreen.kt`
 * — RF-07 —), muestra vista previa (RF-09) y guarda en el pack que el
 * usuario elija (RF-15: el semilla correspondiente o uno propio del mismo
 * tipo — ver [StickerPackRepository.getEligiblePacksForNewSticker]).
 */
@Composable
fun ConvertPreviewSaveScreen(
    uri: Uri,
    isVideo: Boolean,
    startMs: Long,
    durationMs: Long,
    crop: NormalizedCrop,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { StickerPackRepository(context.applicationContext) }

    var stage by remember { mutableStateOf<ConversionStage?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pendingResult by remember { mutableStateOf<WebpEncodeResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var packChoices by remember { mutableStateOf<List<PackChoice>>(emptyList()) }
    var selectedIdentifier by remember { mutableStateOf<String?>(null) }
    var savedPack by remember { mutableStateOf<ManagedStickerPack?>(null) }

    LaunchedEffect(uri, startMs, durationMs, crop) {
        stage = if (isVideo) ConversionStage.DecodingVideo(0, 1) else ConversionStage.DecodingImage
        withContext(Dispatchers.Default) {
            try {
                val result = StickerConversionPipeline.convert(context, uri, isVideo, startMs, durationMs, crop) { newStage -> stage = newStage }
                previewBitmap = BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size)
                pendingResult = result
                val choices = repository.getEligiblePacksForNewSticker(isVideo)
                packChoices = choices
                selectedIdentifier = choices.firstOrNull()?.identifier
            } catch (error: Exception) {
                errorMessage = error.message ?: error.toString()
            } finally {
                stage = null
            }
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            TextButton(onClick = onBack) { Text(text = stringResource(R.string.create_sticker_back)) }

            stage?.let { currentStage -> ConversionProgress(currentStage) }

            errorMessage?.let { message ->
                Text(text = stringResource(R.string.create_sticker_error, message), style = MaterialTheme.typography.bodyMedium)
            }

            previewBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(160.dp),
                )

                if (savedPack == null) {
                    Text(text = stringResource(R.string.create_sticker_choose_pack_title), style = MaterialTheme.typography.titleMedium)
                    Column(Modifier.selectableGroup()) {
                        packChoices.forEach { choice ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = choice.identifier == selectedIdentifier,
                                        onClick = { selectedIdentifier = choice.identifier },
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = choice.identifier == selectedIdentifier, onClick = { selectedIdentifier = choice.identifier })
                                Text(text = stringResource(R.string.pack_choice_label, choice.name, choice.stickerCount))
                            }
                        }
                    }

                    Button(
                        onClick = {
                            val result = pendingResult ?: return@Button
                            val identifier = selectedIdentifier ?: return@Button
                            stage = ConversionStage.Saving
                            scope.launch(Dispatchers.Default) {
                                try {
                                    savedPack = repository.addStickerToPack(
                                        identifier = identifier,
                                        isAnimated = isVideo,
                                        webpBytes = result.bytes,
                                        emojis = emptyList(),
                                        accessibilityText = "",
                                    )
                                } catch (error: Exception) {
                                    errorMessage = error.message ?: error.toString()
                                } finally {
                                    stage = null
                                }
                            }
                        },
                        enabled = selectedIdentifier != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = stringResource(R.string.create_sticker_save_button))
                    }
                }
            }

            savedPack?.let { pack ->
                Card(
                    shape = RoundedCornerShape(Spacing.small),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(
                        modifier = Modifier.padding(Spacing.medium),
                        verticalArrangement = Arrangement.spacedBy(Spacing.small),
                    ) {
                        Text(
                            text = stringResource(R.string.create_sticker_saved, pack.name),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        if (pack.missingForMinimum > 0) {
                            Text(
                                text = stringResource(R.string.pack_missing_for_whatsapp, pack.missingForMinimum),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        } else {
                            AddToWhatsAppButton(identifier = pack.identifier, packName = pack.name)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversionProgress(stage: ConversionStage) {
    when (stage) {
        is ConversionStage.DecodingVideo -> {
            val fraction = (stage.framesDecoded.toFloat() / stage.estimatedTotalFrames).coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text(text = stringResource(R.string.create_sticker_stage_decoding_video, stage.framesDecoded, stage.estimatedTotalFrames))
        }
        ConversionStage.DecodingImage -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(text = stringResource(R.string.create_sticker_stage_decoding_image))
        }
        is ConversionStage.Encoding -> {
            val fraction = (stage.attempt.elapsedMs.toFloat() / stage.attempt.hardTimeLimitMs).coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text(text = stringResource(R.string.create_sticker_stage_encoding, stage.attempt.attemptNumber))
        }
        ConversionStage.Saving -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(text = stringResource(R.string.create_sticker_stage_saving))
        }
    }
}
