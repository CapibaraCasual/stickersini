package io.github.capibaracasual.stickersini.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.capibaracasual.stickersini.R
import io.github.capibaracasual.stickersini.provider.WaStickerContract
import io.github.capibaracasual.stickersini.provider.WhatsAppStickerIntent
import io.github.capibaracasual.stickersini.stickers.domain.SeedPacks
import io.github.capibaracasual.stickersini.ui.theme.StickersiniTheme

private const val SEED_PACK_IDENTIFIER = SeedPacks.STATIC_IDENTIFIER
private const val SEED_PACK_NAME = SeedPacks.STATIC_NAME

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StickersiniTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var showCreateScreen by remember { mutableStateOf(false) }
                    if (showCreateScreen) {
                        CreateStickerScreen(onBack = { showCreateScreen = false })
                    } else {
                        AddSeedPackScreen(onCreateSticker = { showCreateScreen = true })
                    }
                }
            }
        }
    }
}

@Composable
private fun AddSeedPackScreen(onCreateSticker: () -> Unit) {
    val context = LocalContext.current
    var resultMessage by remember { mutableStateOf<String?>(null) }

    // Los textos se resuelven aquÃ­, dentro de la composiciÃ³n, en vez de con
    // context.getString() en los callbacks: asÃ­ siguen los cambios de
    // configuraciÃ³n (lint LocalContextGetResourceValueCall).
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
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
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
