package io.github.capibaracasual.stickersini.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.capibaracasual.stickersini.R
import io.github.capibaracasual.stickersini.media.ConversionStage
import io.github.capibaracasual.stickersini.media.NormalizedCrop
import io.github.capibaracasual.stickersini.media.StickerConversionPipeline
import io.github.capibaracasual.stickersini.stickers.data.PackChoice
import io.github.capibaracasual.stickersini.stickers.data.StickerPackRepository
import io.github.capibaracasual.stickersini.stickers.domain.ManagedStickerPack
import io.github.capibaracasual.stickersini.ui.theme.Radius
import io.github.capibaracasual.stickersini.ui.theme.Spacing
import io.github.capibaracasual.stickersini.webp.WebpEncodeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Último tramo del flujo de creación: convierte ([startMs]/[durationMs] ya
 * elegidos en `ui/TrimScreen.kt` — RF-06 — y [crop] en `ui/CropScreen.kt`
 * — RF-07 —), muestra vista previa (RF-09) y guarda en el pack que el
 * usuario elija (RF-15: el semilla correspondiente o uno propio del mismo
 * tipo — ver [StickerPackRepository.getEligiblePacksForNewSticker]). La
 * pantalla con el criterio más explícito del pase de diseño visual: que
 * guardar nunca se sienta como un trámite administrativo (README "Qué
 * falta").
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

    Scaffold(
        bottomBar = {
            if (previewBitmap != null && savedPack == null) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(Spacing.large),
                    verticalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    StickerPrimaryButton(
                        text = stringResource(R.string.create_sticker_save_button),
                        enabled = selectedIdentifier != null && stage == null,
                        onClick = {
                            val result = pendingResult
                            val identifier = selectedIdentifier
                            if (result != null && identifier != null) {
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
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.create_sticker_save_reassurance),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            StickerScreenHeader(
                title = stringResource(R.string.convert_title),
                backLabel = stringResource(R.string.create_sticker_back),
                onBack = onBack,
            )

            stage?.let { currentStage -> ConversionProgress(currentStage) }

            errorMessage?.let { message ->
                Text(text = stringResource(R.string.create_sticker_error, message), style = MaterialTheme.typography.bodyMedium)
            }

            previewBitmap?.let { bitmap ->
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    StickerDieCutFrame(modifier = Modifier.size(240.dp)) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (isVideo) {
                        StickerBadge(
                            text = stringResource(R.string.convert_animated_badge),
                            modifier = Modifier.align(Alignment.TopEnd).offset(x = (-4).dp, y = (-4).dp),
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.convert_meta, formatSeconds(durationMs), (pendingResult?.bytes?.size ?: 0) / 1024),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                // ADR-0016: acortar la duración solo se sabe si hizo falta
                // después de intentar codificar, nunca antes — por eso el
                // aviso vive acá (con el resultado ya listo) y no en
                // TrimScreen.
                pendingResult?.let { result ->
                    if (result.shortenedByMs > 0) {
                        Text(
                            text = stringResource(
                                R.string.convert_shortened_notice,
                                formatSeconds(result.frameDurationsMs.sumOf { it.toLong() }),
                                formatSeconds(result.requestedDurationMs),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                if (savedPack == null) {
                    Text(text = stringResource(R.string.create_sticker_choose_pack_title), style = MaterialTheme.typography.titleMedium)

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                        modifier = Modifier.fillMaxWidth().selectableGroup(),
                    ) {
                        items(packChoices, key = { it.identifier }) { choice ->
                            PackChip(
                                choice = choice,
                                selected = choice.identifier == selectedIdentifier,
                                onClick = { selectedIdentifier = choice.identifier },
                            )
                        }
                    }
                }
            }

            savedPack?.let { pack -> SavedPackCard(pack) }
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

/**
 * Un pack para elegir dónde guardar, como un sticker circular más de la
 * misma lámina: iniciales del nombre sobre un círculo de color, con un
 * anillo coral cuando está seleccionado. Sin ícono propio por pack todavía
 * — eso depende de los seis stickers semilla definitivos (README "Qué
 * falta"), que hoy son placeholders.
 */
@Composable
private fun PackChip(choice: PackChoice, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(72.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .border(
                    width = if (selected) 3.dp else 0.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = CircleShape,
                )
                .padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            PackAvatar(name = choice.name, modifier = Modifier.fillMaxSize(), size = null)
        }
        Text(
            text = choice.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SavedPackCard(pack: ManagedStickerPack) {
    Card(
        shape = RoundedCornerShape(Radius.card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Text(
                text = stringResource(R.string.create_sticker_saved, pack.name),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (pack.missingForMinimum > 0) {
                Text(
                    text = stringResource(R.string.pack_missing_for_whatsapp, pack.missingForMinimum),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            } else {
                AddToWhatsAppButton(identifier = pack.identifier, packName = pack.name, status = pack.whatsAppStatus)
            }
        }
    }
}

private fun formatSeconds(ms: Long): String = String.format(Locale.US, "%.1f s", ms / 1000.0)
