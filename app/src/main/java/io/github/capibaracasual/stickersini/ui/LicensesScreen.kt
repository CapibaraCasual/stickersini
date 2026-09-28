package io.github.capibaracasual.stickersini.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import io.github.capibaracasual.stickersini.R
import io.github.capibaracasual.stickersini.licenses.LicenseAsset
import io.github.capibaracasual.stickersini.licenses.ThirdPartyLicense
import io.github.capibaracasual.stickersini.licenses.thirdPartyLicenses
import io.github.capibaracasual.stickersini.ui.theme.Radius
import io.github.capibaracasual.stickersini.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * RNF-11: avisos de licencia. Encabeza con la licencia propia (GPL-3.0,
 * texto completo, URL del repo en texto plano — no es una acción de la app,
 * así que no lleva enlace clicable) y sigue con las dependencias reales de
 * `releaseRuntimeClasspath`, agrupadas por quién las publica en
 * [thirdPartyLicenses] (ver ADR-0017). Cada fila de terceros navega a su
 * propio texto legal completo en vez de mostrarlo acá: son varias licencias
 * distintas (Apache-2.0, BSD-3-Clause, OFL-1.1) y listarlas todas de entrada
 * haría la pantalla de inicio de la sección ilegible.
 */
@Composable
fun LicensesScreen(onBack: () -> Unit, onOpenLicense: (Int) -> Unit) {
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            item {
                StickerScreenHeader(
                    title = stringResource(R.string.licenses_title),
                    backLabel = stringResource(R.string.create_sticker_back),
                    onBack = onBack,
                )
            }

            item { OwnLicenseSection() }

            item {
                Text(
                    text = stringResource(R.string.licenses_third_party_heading),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            items(thirdPartyLicenses.size) { index ->
                ThirdPartyLicenseRow(entry = thirdPartyLicenses[index], onClick = { onOpenLicense(index) })
            }
        }
    }
}

@Composable
private fun OwnLicenseSection() {
    val text = rememberAssetText(LicenseAsset.GPL_3_0.assetPath)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
        Text(text = stringResource(R.string.licenses_own_license_summary), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = stringResource(R.string.licenses_own_license_repo),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LicenseTextBlock(text = text)
    }
}

@Composable
private fun ThirdPartyLicenseRow(entry: ThirdPartyLicense, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(Radius.card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.medium)) {
            Text(text = entry.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = entry.licenseName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Detalle de una entrada de [thirdPartyLicenses]: su(s) texto(s) legal(es) completos. */
@Composable
fun ThirdPartyLicenseDetailScreen(index: Int, onBack: () -> Unit) {
    val entry = thirdPartyLicenses[index]
    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.large)) {
            StickerScreenHeader(title = entry.name, backLabel = stringResource(R.string.create_sticker_back), onBack = onBack)
            Text(
                text = entry.detail,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = Spacing.medium),
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(Spacing.large),
            ) {
                items(entry.texts.size) { i ->
                    LicenseTextBlock(text = rememberAssetText(entry.texts[i].assetPath))
                }
            }
        }
    }
}

@Composable
private fun LicenseTextBlock(text: String?) {
    if (text == null) {
        CircularProgressIndicator()
        return
    }
    Text(text = text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
}

@Composable
private fun rememberAssetText(path: String): String? {
    val context = LocalContext.current
    var text by remember(path) { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        text = readAssetText(context, path)
    }
    return text
}

private suspend fun readAssetText(context: Context, path: String): String = withContext(Dispatchers.IO) {
    context.assets.open(path).bufferedReader().use { it.readText() }
}
