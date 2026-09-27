package io.github.capibaracasual.stickersini.stickers.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Cuántos stickers tenía cada pack la última vez que el usuario confirmó
 * agregarlo a WhatsApp (RF-20) — el único momento en que la app se entera de
 * algo, porque el contrato WAStickerApps no tiene una consulta de "¿este
 * pack ya está agregado?". Comparar ese número contra la cantidad real de
 * hoy es lo que arma [io.github.capibaracasual.stickersini.stickers.domain.ManagedStickerPack.whatsAppStatus]:
 * distingue "nunca confirmado" de "confirmado, sin cambios" de "confirmado,
 * pero cambió después" (se le agregó o quitó un sticker y WhatsApp puede
 * tener cacheada la versión vieja).
 *
 * `filesDir/packs/whatsapp_confirmations.json` — mismo patrón de archivo
 * temporal + rename que [UserPackManifestRepository].
 */
class WhatsAppConfirmationRepository(private val context: Context) {

    fun getConfirmedStickerCount(identifier: String): Int? = readAll()[identifier]

    fun markConfirmed(identifier: String, stickerCount: Int) {
        val all = readAll().toMutableMap()
        all[identifier] = stickerCount
        writeAll(all)
    }

    private fun readAll(): Map<String, Int> {
        val file = storeFile()
        if (!file.exists()) return emptyMap()
        val array = JSONArray(file.readText())
        return (0 until array.length()).associate { index ->
            val entry = array.getJSONObject(index)
            entry.getString("identifier") to entry.getInt("sticker_count")
        }
    }

    private fun writeAll(entries: Map<String, Int>) {
        val array = JSONArray()
        entries.forEach { (identifier, count) ->
            array.put(
                JSONObject().apply {
                    put("identifier", identifier)
                    put("sticker_count", count)
                },
            )
        }
        val dir = packsDir().apply { mkdirs() }
        val tempFile = File(dir, "whatsapp_confirmations.json.tmp")
        tempFile.writeText(array.toString())
        tempFile.renameTo(storeFile())
    }

    private fun packsDir() = File(context.filesDir, "packs")

    private fun storeFile() = File(packsDir(), "whatsapp_confirmations.json")
}
