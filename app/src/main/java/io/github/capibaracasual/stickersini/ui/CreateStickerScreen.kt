package io.github.capibaracasual.stickersini.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.capibaracasual.stickersini.R
import io.github.capibaracasual.stickersini.media.ConversionStage
import io.github.capibaracasual.stickersini.media.StickerConversionPipeline
import io.github.capibaracasual.stickersini.provider.WhatsAppStickerIntent
import io.github.capibaracasual.stickersini.stickers.data.StickerPackRepository
import io.github.capibaracasual.stickersini.stickers.domain.SeedPacks
import io.github.capibaracasual.stickersini.webp.WebpEncodeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Recorrido mínimo de Fase 3 (elegir archivo → vista previa → guardar), sin
 * recorte de tramo (RF-06) ni de área (RF-07) todavía: siempre procesa
 * desde el segundo 0 de un video y recorta al cuadrado central, tal como ya
 * hace [StickerConversionPipeline]. El resultado se agrega siempre a uno de
 * los dos packs semilla (RF-18, ver [SeedPacks]), no a un pack propio
 * nuevo: no hay todavía una pantalla para crear o elegir packs (RF-15).
 */
@Composable
fun CreateStickerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { StickerPackRepository(context.applicationContext) }

    var isVideo by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf<ConversionStage?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pendingResult by remember { mutableStateOf<WebpEncodeResult?>(null) }
    var savedPackName by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun reset() {
        stage = null
        previewBitmap = null
        pendingResult = null
        savedPackName = null
        errorMessage = null
    }

    val pickMedia = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        reset()
        val mimeType = context.contentResolver.getType(uri).orEmpty()
        isVideo = mimeType.startsWith("video/")
        stage = if (isVideo) ConversionStage.DecodingVideo(0, 1) else ConversionStage.DecodingImage
        scope.launch(Dispatchers.Default) {
            try {
                val result = StickerConversionPipeline.convert(context, uri, isVideo) { newStage -> stage = newStage }
                val bitmap = BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size)
                previewBitmap = bitmap
                pendingResult = result
            } catch (error: Exception) {
                errorMessage = error.message ?: error.toString()
            } finally {
                stage = null
            }
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TextButton(onClick = onBack) { Text(text = stringResource(R.string.create_sticker_back)) }
            Text(text = stringResource(R.string.create_sticker_title), style = MaterialTheme.typography.titleLarge)

            Button(onClick = {
                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }) {
                Text(text = stringResource(R.string.create_sticker_pick_button))
            }

            stage?.let { currentStage ->
                ConversionProgress(currentStage)
            }

            errorMessage?.let { message ->
                Text(text = stringResource(R.string.create_sticker_error, message), style = MaterialTheme.typography.bodyMedium)
            }

            previewBitmap?.let { bitmap ->
                Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(160.dp))

                if (savedPackName == null) {
                    Button(onClick = {
                        val result = pendingResult ?: return@Button
                        stage = ConversionStage.Saving
                        scope.launch(Dispatchers.Default) {
                            try {
                                val pack = repository.addStickerToSeedPack(
                                    isAnimated = isVideo,
                                    webpBytes = result.bytes,
                                    emojis = emptyList(),
                                    accessibilityText = "",
                                )
                                savedPackName = pack.name
                            } catch (error: Exception) {
                                errorMessage = error.message ?: error.toString()
                            } finally {
                                stage = null
                            }
                        }
                    }) {
                        Text(text = stringResource(R.string.create_sticker_save_button))
                    }
                }
            }

            savedPackName?.let { packName ->
                val identifier = if (isVideo) SeedPacks.ANIMATED_IDENTIFIER else SeedPacks.STATIC_IDENTIFIER
                Text(text = stringResource(R.string.create_sticker_saved, packName), style = MaterialTheme.typography.bodyLarge)
                AddToWhatsAppButton(identifier = identifier, packName = packName)
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

@Composable
private fun AddToWhatsAppButton(identifier: String, packName: String) {
    val context = LocalContext.current
    var resultMessage by remember { mutableStateOf<String?>(null) }
    val noActivityMessage = stringResource(R.string.result_no_activity)
    val whatsAppNotInstalledMessage = stringResource(R.string.result_whatsapp_not_installed)
    val successMessage = stringResource(R.string.result_success)

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        resultMessage = if (result.resultCode == Activity.RESULT_OK) successMessage else null
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            if (!WhatsAppStickerIntent.isWhatsAppInstalled(context)) {
                resultMessage = whatsAppNotInstalledMessage
                return@Button
            }
            val intent = WhatsAppStickerIntent.buildAddPackIntent(context, identifier, packName)
            try {
                launcher.launch(intent)
            } catch (error: ActivityNotFoundException) {
                resultMessage = noActivityMessage
            }
        }) {
            Text(text = stringResource(R.string.add_pack_button))
        }
        resultMessage?.let { message -> Text(text = message) }
    }
}
