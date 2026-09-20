package io.github.capibaracasual.stickersini.stickers.data

import android.content.Context
import io.github.capibaracasual.stickersini.stickers.domain.Sticker
import io.github.capibaracasual.stickersini.stickers.domain.StickerPack
import org.json.JSONObject

/**
 * Lee `assets/contents.json` y construye los [StickerPack] que sirve
 * [io.github.capibaracasual.stickersini.provider.StickerContentProvider]. Los bytes
 * de cada sticker viven en `assets/<identifier>/<image_file>`.
 */
class StickerPackAssetRepository(private val context: Context) {

    private val packs: List<StickerPack> by lazy { loadPacks() }

    fun getAllPacks(): List<StickerPack> = packs

    fun getPack(identifier: String): StickerPack? = packs.find { it.identifier == identifier }

    private fun loadPacks(): List<StickerPack> {
        val json = context.assets.open(CONTENTS_FILE).bufferedReader().use { it.readText() }
        val packsJson = JSONObject(json).getJSONArray("sticker_packs")
        return (0 until packsJson.length()).map { parsePack(packsJson.getJSONObject(it)) }
    }

    private fun parsePack(json: JSONObject): StickerPack {
        val isAnimated = json.optBoolean("animated_pack", false)
        val stickersJson = json.getJSONArray("stickers")
        val stickers = (0 until stickersJson.length()).map { parseSticker(stickersJson.getJSONObject(it), isAnimated) }
        return StickerPack.create(
            identifier = json.getString("identifier"),
            name = json.getString("name"),
            publisher = json.getString("publisher"),
            trayImageFileName = json.getString("tray_image_file"),
            stickers = stickers,
            publisherEmail = json.optString("publisher_email", ""),
            publisherWebsite = json.optString("publisher_website", ""),
            privacyPolicyWebsite = json.optString("privacy_policy_website", ""),
            licenseAgreementWebsite = json.optString("license_agreement_website", ""),
        )
    }

    private fun parseSticker(json: JSONObject, isAnimated: Boolean): Sticker {
        val emojisJson = json.optJSONArray("emojis")
        val emojis = emojisJson?.let { array -> (0 until array.length()).map { array.getString(it) } } ?: emptyList()
        return Sticker(
            imageFileName = json.getString("image_file"),
            isAnimated = isAnimated,
            emojis = emojis,
            accessibilityText = json.optString("accessibility_text", ""),
        )
    }

    companion object {
        private const val CONTENTS_FILE = "contents.json"
    }
}
