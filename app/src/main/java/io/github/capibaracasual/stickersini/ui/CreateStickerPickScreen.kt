package io.github.capibaracasual.stickersini.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.capibaracasual.stickersini.R
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

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            TextButton(onClick = onBack) { Text(text = stringResource(R.string.create_sticker_back)) }
            Text(text = stringResource(R.string.create_sticker_title), style = MaterialTheme.typography.titleLarge)

            Button(
                onClick = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.create_sticker_pick_button))
            }
        }
    }
}
