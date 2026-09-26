package io.github.capibaracasual.stickersini.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.capibaracasual.stickersini.R
import io.github.capibaracasual.stickersini.stickers.data.StickerPackRepository
import io.github.capibaracasual.stickersini.stickers.domain.ManagedStickerPack
import io.github.capibaracasual.stickersini.stickers.domain.Sticker
import io.github.capibaracasual.stickersini.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * RF-15: ver los stickers de un pack, renombrarlo, eliminarlo o quitarle un
 * sticker — ninguna de esas tres acciones si es un pack semilla (ADR-0014).
 */
@Composable
fun PackDetailScreen(identifier: String, onBack: () -> Unit, onPackDeleted: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { StickerPackRepository(context.applicationContext) }
    var pack by remember { mutableStateOf<ManagedStickerPack?>(null) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var stickerPendingRemoval by remember { mutableStateOf<Sticker?>(null) }

    LaunchedEffect(identifier, refreshTrigger) {
        pack = repository.getAllManagedPacks().find { it.identifier == identifier }
    }

    val currentPack = pack

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            TextButton(onClick = onBack) { Text(text = stringResource(R.string.create_sticker_back)) }

            if (currentPack == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    Text(text = currentPack.name, style = MaterialTheme.typography.titleLarge)
                    if (!currentPack.isSeedPack) {
                        TextButton(onClick = { showRenameDialog = true }) {
                            Text(text = stringResource(R.string.pack_detail_rename_action))
                        }
                    }
                }

                if (currentPack.stickers.isEmpty()) {
                    Text(text = stringResource(R.string.pack_detail_empty), style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        verticalArrangement = Arrangement.spacedBy(Spacing.small),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        modifier = Modifier.weight(1f),
                    ) {
                        items(currentPack.stickers, key = { it.imageFileName }) { sticker ->
                            StickerThumbnail(
                                identifier = identifier,
                                sticker = sticker,
                                repository = repository,
                                showRemove = !currentPack.isSeedPack,
                                onRemoveRequested = { stickerPendingRemoval = sticker },
                            )
                        }
                    }
                }

                if (currentPack.missingForMinimum > 0) {
                    Text(text = stringResource(R.string.pack_missing_for_whatsapp, currentPack.missingForMinimum))
                } else {
                    AddToWhatsAppButton(identifier = currentPack.identifier, packName = currentPack.name)
                }

                if (!currentPack.isSeedPack) {
                    Button(onClick = { showDeleteConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(text = stringResource(R.string.pack_detail_delete_pack_button))
                    }
                }
            }
        }
    }

    if (showRenameDialog && currentPack != null) {
        RenamePackDialog(
            initialName = currentPack.name,
            onDismiss = { showRenameDialog = false },
            onRename = { newName ->
                repository.renameUserPack(identifier, newName)
                showRenameDialog = false
                refreshTrigger++
            },
        )
    }

    stickerPendingRemoval?.let { sticker ->
        AlertDialog(
            onDismissRequest = { stickerPendingRemoval = null },
            title = { Text(text = stringResource(R.string.pack_detail_remove_sticker_confirm_title)) },
            text = { Text(text = stringResource(R.string.pack_detail_remove_sticker_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    repository.removeStickerFromUserPack(identifier, sticker.isAnimated, sticker.imageFileName)
                    stickerPendingRemoval = null
                    refreshTrigger++
                }) {
                    Text(text = stringResource(R.string.pack_detail_remove_sticker_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { stickerPendingRemoval = null }) { Text(text = stringResource(R.string.packs_dialog_cancel)) }
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(text = stringResource(R.string.pack_detail_delete_pack_confirm_title)) },
            text = { Text(text = stringResource(R.string.pack_detail_delete_pack_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    repository.deleteUserPack(identifier)
                    showDeleteConfirm = false
                    onPackDeleted()
                }) {
                    Text(text = stringResource(R.string.pack_detail_delete_pack_confirm_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(text = stringResource(R.string.packs_dialog_cancel)) }
            },
        )
    }
}

@Composable
private fun StickerThumbnail(
    identifier: String,
    sticker: Sticker,
    repository: StickerPackRepository,
    showRemove: Boolean,
    onRemoveRequested: () -> Unit,
) {
    var bitmap by remember(sticker.imageFileName) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(sticker.imageFileName) {
        bitmap = withContext(Dispatchers.IO) {
            val bytes = repository.readStickerBytes(identifier, sticker.imageFileName)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    Column {
        bitmap?.let { decoded ->
            Image(
                bitmap = decoded.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
        }
        if (showRemove) {
            TextButton(onClick = onRemoveRequested) {
                Text(text = stringResource(R.string.pack_detail_remove_sticker_action))
            }
        }
    }
}

@Composable
private fun RenamePackDialog(initialName: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.pack_detail_rename_dialog_title)) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onRename(name.trim()) }, enabled = name.isNotBlank()) {
                Text(text = stringResource(R.string.pack_detail_rename_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.packs_dialog_cancel)) }
        },
    )
}
