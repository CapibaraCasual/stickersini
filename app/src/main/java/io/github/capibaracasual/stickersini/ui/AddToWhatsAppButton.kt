package io.github.capibaracasual.stickersini.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
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
import io.github.capibaracasual.stickersini.ui.theme.Spacing

/**
 * Lanza el intent de confirmación de WhatsApp para [identifier] (RF-20).
 * Compartido entre `ConvertPreviewSaveScreen` (justo después de guardar un
 * sticker nuevo) y `PackDetailScreen` (RF-15, para cualquier pack ya
 * existente): la acción es la misma en los dos casos, solo cambia desde
 * dónde se dispara.
 */
@Composable
fun AddToWhatsAppButton(identifier: String, packName: String) {
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

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Button(
            onClick = {
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
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.add_pack_button))
        }
        resultMessage?.let { message -> Text(text = message) }
    }
}
