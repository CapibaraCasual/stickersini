package io.github.capibaracasual.stickersini.stickers.data

import android.content.Context
import io.github.capibaracasual.stickersini.stickers.domain.Sticker
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Persiste los stickers que el usuario agrega a un pack existente (hoy,
 * siempre uno de los dos packs semilla — estático o animado — ver
 * ADR-0010). Guarda solo la *extensión* sobre la definición base del pack
 * (que sigue viniendo de `assets/contents.json` sin cambios): un índice
 * JSON con la misma forma que ya usa esa definición, más los `.webp` reales
 * en un directorio propio por pack.
 *
 * `filesDir/packs/<identifier>/index.json` — lista de stickers de ese pack.
 * `filesDir/packs/<identifier>/<archivo>.webp` — bytes de cada uno.
 *
 * Cada escritura reemplaza el índice entero (archivo temporal + rename) en
 * vez de parchearlo: a esta escala (como mucho 27 stickers agregados, hasta
 * completar el máximo de 30 de RF-16 junto con los 3 del pack base) es
 * más simple que mantener un formato apendable, y evita dejar el índice a
 * medio escribir si el proceso muere en el momento exacto de guardar.
 */
class UserPackStickerRepository(private val context: Context) {

    /**
     * @param isAnimated no se guarda por sticker (igual que en
     * `assets/contents.json`, donde `animated_pack` es un dato del pack, no
     * de cada sticker): lo pasa quien ya sabe a qué pack pertenecen estos
     * extras.
     */
    fun getExtraStickers(identifier: String, isAnimated: Boolean): List<Sticker> {
        val indexFile = indexFile(identifier)
        if (!indexFile.exists()) return emptyList()
        val json = indexFile.readText()
        val array = JSONArray(json)
        return (0 until array.length()).map { parseSticker(array.getJSONObject(it), isAnimated) }
    }

    /**
     * Escribe los bytes del nuevo sticker y lo agrega al índice del pack.
     * No valida el máximo de RF-16 ni la mezcla de RF-18: eso es
     * responsabilidad de quien arma el [io.github.capibaracasual.stickersini.stickers.domain.StickerPack]
     * final con el resultado combinado (base + extras).
     */
    fun addSticker(
        identifier: String,
        webpBytes: ByteArray,
        isAnimated: Boolean,
        emojis: List<String>,
        accessibilityText: String,
    ): Sticker {
        val dir = packDir(identifier).apply { mkdirs() }
        val fileName = "user_${UUID.randomUUID()}.webp"
        File(dir, fileName).writeBytes(webpBytes)

        val sticker = Sticker(imageFileName = fileName, isAnimated = isAnimated, emojis = emojis, accessibilityText = accessibilityText)
        val updated = getExtraStickers(identifier, isAnimated) + sticker
        writeIndexAtomically(identifier, updated)
        return sticker
    }

    fun stickerFile(identifier: String, imageFileName: String): File = File(packDir(identifier), imageFileName)

    private fun writeIndexAtomically(identifier: String, stickers: List<Sticker>) {
        val array = JSONArray()
        stickers.forEach { sticker ->
            array.put(
                JSONObject().apply {
                    put("image_file", sticker.imageFileName)
                    put("emojis", JSONArray(sticker.emojis))
                    put("accessibility_text", sticker.accessibilityText)
                },
            )
        }
        val dir = packDir(identifier).apply { mkdirs() }
        val tempFile = File(dir, "index.json.tmp")
        tempFile.writeText(array.toString())
        tempFile.renameTo(indexFile(identifier))
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

    private fun packDir(identifier: String) = File(File(context.filesDir, "packs"), identifier)

    private fun indexFile(identifier: String) = File(packDir(identifier), "index.json")
}
