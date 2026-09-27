package io.github.capibaracasual.stickersini.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
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
import io.github.capibaracasual.stickersini.provider.WhatsAppStickerIntent
import io.github.capibaracasual.stickersini.stickers.data.StickerPackRepository
import io.github.capibaracasual.stickersini.ui.theme.Spacing

/**
 * Lanza el intent de confirmación de WhatsApp para [identifier] (RF-20).
 * Compartido entre `ConvertPreviewSaveScreen` (justo después de guardar un
 * sticker nuevo) y `PackDetailScreen` (RF-15, para cualquier pack ya
 * existente): la acción es la misma en los dos casos, solo cambia desde
 * dónde se dispara. Cuando WhatsApp confirma con éxito, registra la
 * cantidad de stickers de ese momento (único lugar donde se llama
 * [StickerPackRepository.markAddedToWhatsApp]) para que la gestión de packs
 * pueda mostrar si un pack está al día o cambió después.
 */
@Composable
fun AddToWhatsAppButton(identifier: String, packName: String) {
    val context = LocalContext.current
    val repository = remember { StickerPackRepository(context.applicationContext) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    val noActivityMessage = stringResource(R.string.result_no_activity)
    val whatsAppNotInstalledMessage = stringResource(R.string.result_whatsapp_not_installed)
    val successMessage = stringResource(R.string.result_success)

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            repository.markAddedToWhatsApp(identifier)
            resultMessage = successMessage
        } else {
            resultMessage = null
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        StickerPrimaryButton(
            text = stringResource(R.string.add_pack_button),
            onClick = {
                if (!WhatsAppStickerIntent.isWhatsAppInstalled(context)) {
                    resultMessage = whatsAppNotInstalledMessage
                    return@StickerPrimaryButton
                }
                val intent = WhatsAppStickerIntent.buildAddPackIntent(context, identifier, packName)
                try {
                    launcher.launch(intent)
                } catch (error: ActivityNotFoundException) {
                    resultMessage = noActivityMessage
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        resultMessage?.let { message -> Text(text = message, style = MaterialTheme.typography.bodyMedium) }
    }
}
