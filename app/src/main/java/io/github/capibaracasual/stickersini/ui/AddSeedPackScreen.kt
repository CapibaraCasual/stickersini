package io.github.capibaracasual.stickersini.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.capibaracasual.stickersini.R
import io.github.capibaracasual.stickersini.provider.WaStickerContract
import io.github.capibaracasual.stickersini.provider.WhatsAppStickerIntent
import io.github.capibaracasual.stickersini.stickers.domain.SeedPacks
import io.github.capibaracasual.stickersini.ui.theme.Spacing

private const val SEED_PACK_IDENTIFIER = SeedPacks.STATIC_IDENTIFIER
private const val SEED_PACK_NAME = SeedPacks.STATIC_NAME

/** Pantalla de inicio ("home"): añadir el pack semilla (Fase 0) o pasar al flujo de creación. */
@Composable
fun AddSeedPackScreen(onCreateSticker: () -> Unit) {
    val context = LocalContext.current
    var resultMessage by remember { mutableStateOf<String?>(null) }

    // Los textos se resuelven aquí, dentro de la composición, en vez de con
    // context.getString() en los callbacks: así siguen los cambios de
    // configuración (lint LocalContextGetResourceValueCall).
    val successMessage = stringResource(R.string.result_success)
    val canceledMessage = stringResource(R.string.result_canceled)
    val validationErrorTemplate = stringResource(R.string.result_validation_error)
    val whatsAppNotInstalledMessage = stringResource(R.string.result_whatsapp_not_installed)
    val noActivityMessage = stringResource(R.string.result_no_activity)

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        resultMessage = when (result.resultCode) {
            Activity.RESULT_OK -> successMessage
            else -> {
                val validationError = result.data?.getStringExtra(WaStickerContract.AddPackIntent.EXTRA_VALIDATION_ERROR)
                if (validationError != null) {
                    validationErrorTemplate.format(validationError)
                } else {
                    canceledMessage
                }
            }
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Button(onClick = onCreateSticker) {
                Text(text = stringResource(R.string.create_sticker_button))
            }

            Text(text = stringResource(R.string.add_pack_title), style = MaterialTheme.typography.titleLarge)
            Text(text = stringResource(R.string.add_pack_description), style = MaterialTheme.typography.bodyMedium)

            Button(onClick = {
                if (!WhatsAppStickerIntent.isWhatsAppInstalled(context)) {
                    resultMessage = whatsAppNotInstalledMessage
                    return@Button
                }
                val intent = WhatsAppStickerIntent.buildAddPackIntent(context, SEED_PACK_IDENTIFIER, SEED_PACK_NAME)
                try {
                    launcher.launch(intent)
                } catch (error: ActivityNotFoundException) {
                    resultMessage = noActivityMessage
                }
            }) {
                Text(text = stringResource(R.string.add_pack_button))
            }

            resultMessage?.let { message ->
                Text(text = message, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
