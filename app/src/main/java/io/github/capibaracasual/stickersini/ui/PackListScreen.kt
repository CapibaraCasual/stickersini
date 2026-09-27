package io.github.capibaracasual.stickersini.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.capibaracasual.stickersini.R
import io.github.capibaracasual.stickersini.stickers.data.StickerPackRepository
import io.github.capibaracasual.stickersini.stickers.domain.ManagedStickerPack
import io.github.capibaracasual.stickersini.stickers.domain.WhatsAppStatus
import io.github.capibaracasual.stickersini.ui.theme.Radius
import io.github.capibaracasual.stickersini.ui.theme.Spacing

/**
 * RF-15: ver todos los packs (semilla y propios) y crear uno nuevo con
 * nombre. Los dos packs semilla se distinguen con una insignia, pero no
 * ofrecen ninguna acción de edición desde acá (ADR-0014) — eso lo decide
 * [PackDetailScreen] según `isSeedPack`.
 */
@Composable
fun PackListScreen(onBack: () -> Unit, onOpenPack: (String) -> Unit) {
    val context = LocalContext.current
    val repository = remember { StickerPackRepository(context.applicationContext) }
    var packs by remember { mutableStateOf<List<ManagedStickerPack>>(emptyList()) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var showCreateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(refreshTrigger) {
        packs = repository.getAllManagedPacks()
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            StickerScreenHeader(
                title = stringResource(R.string.packs_title),
                backLabel = stringResource(R.string.create_sticker_back),
                onBack = onBack,
            )

            StickerPrimaryButton(
                text = stringResource(R.string.packs_create_button),
                onClick = { showCreateDialog = true },
                modifier = Modifier.fillMaxWidth(),
            )

            if (packs.isEmpty()) {
                Text(
                    text = stringResource(R.string.packs_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    items(packs, key = { it.identifier }) { pack ->
                        PackRow(pack = pack, onClick = { onOpenPack(pack.identifier) })
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreatePackDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, isAnimated ->
                repository.createUserPack(name, isAnimated)
                showCreateDialog = false
                refreshTrigger++
            },
        )
    }
}

@Composable
private fun PackRow(pack: ManagedStickerPack, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(Radius.card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            PackAvatar(name = pack.name, size = 48.dp)

            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.CenterVertically) {
                    Text(text = pack.name, style = MaterialTheme.typography.titleMedium)
                    if (pack.isSeedPack) {
                        StickerBadge(text = stringResource(R.string.packs_seed_badge))
                    }
                }
                val countText = if (pack.missingForMinimum > 0) {
                    stringResource(R.string.packs_missing_count, pack.stickers.size, pack.missingForMinimum)
                } else {
                    stringResource(R.string.packs_sticker_count, pack.stickers.size)
                }
                Text(text = countText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (pack.missingForMinimum == 0) {
                WhatsAppStatusBadge(pack.whatsAppStatus)
            }
        }
    }
}

/**
 * Recordatorio o confirmación de si este pack se agregó a WhatsApp — solo
 * lo que la propia app sabe (ver [ManagedStickerPack.whatsAppStatus], no
 * hay forma de consultarle a WhatsApp si sigue instalado). Es un indicador,
 * no un botón: tocar cualquier parte de la fila ya lleva a
 * `PackDetailScreen`, donde vive la acción real.
 */
@Composable
private fun WhatsAppStatusBadge(status: WhatsAppStatus) {
    when (status) {
        WhatsAppStatus.NeverConfirmed -> StickerBadge(
            text = stringResource(R.string.packs_whatsapp_add_badge),
            containerColor = MaterialTheme.colorScheme.primary,
            outlined = true,
        )
        WhatsAppStatus.OutOfDate -> StickerBadge(
            text = stringResource(R.string.packs_whatsapp_update_badge),
            containerColor = MaterialTheme.colorScheme.primary,
        )
        WhatsAppStatus.UpToDate -> StickerBadge(
            text = stringResource(R.string.packs_whatsapp_up_to_date_badge),
            containerColor = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun CreatePackDialog(onDismiss: () -> Unit, onCreate: (name: String, isAnimated: Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var isAnimated by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.packs_create_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(text = stringResource(R.string.packs_create_dialog_name_label)) },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !isAnimated, onClick = { isAnimated = false })
                    Text(text = stringResource(R.string.packs_create_dialog_type_static))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = isAnimated, onClick = { isAnimated = true })
                    Text(text = stringResource(R.string.packs_create_dialog_type_animated))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onCreate(name.trim(), isAnimated) }, enabled = name.isNotBlank()) {
                Text(text = stringResource(R.string.packs_create_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.packs_dialog_cancel)) }
        },
    )
}
