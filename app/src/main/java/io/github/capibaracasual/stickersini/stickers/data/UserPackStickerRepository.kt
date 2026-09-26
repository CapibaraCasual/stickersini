package io.github.capibaracasual.stickersini.stickers.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import io.github.capibaracasual.stickersini.media.SquareCrop
import io.github.capibaracasual.stickersini.stickers.domain.Sticker
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** RF-14: el ícono de bandeja de un pack propio es PNG de 96×96, igual que el de un pack semilla. */
private const val TRAY_ICON_SIZE = 96
private const val TRAY_FILE_NAME = "tray.png"

/**
 * Persiste los stickers que el usuario agrega a un pack: un índice JSON con
 * la misma forma que usa `assets/contents.json`, más los `.webp` reales en
 * un directorio propio por pack. Sirve tanto para la *extensión* de un pack
 * semilla (que sigue teniendo su definición base en `assets/`, ADR-0010)
 * como para un pack propio entero (RF-15, sin ninguna base en `assets/` —
 * ver [UserPackManifestRepository] para el nombre/tipo de esos packs): este
 * repositorio no distingue entre los dos casos, un identificador es un
 * identificador.
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

    /** RF-15: saca [imageFileName] del índice y borra su archivo. Sin efecto si no estaba (no es un extra de este pack). */
    fun removeSticker(identifier: String, isAnimated: Boolean, imageFileName: String) {
        val remaining = getExtraStickers(identifier, isAnimated).filterNot { it.imageFileName == imageFileName }
        writeIndexAtomically(identifier, remaining)
        stickerFile(identifier, imageFileName).delete()
    }

    /**
     * RF-14: si el pack todavía no tiene ícono de bandeja, lo genera a partir
     * de [representativeWebpBytes] (recorte centrado + escalado a
     * [TRAY_ICON_SIZE]×[TRAY_ICON_SIZE], igual que el recorte automático de
     * un sticker — [SquareCrop.of]). Con un WebP animado,
     * `BitmapFactory.decodeByteArray` ya da su primer fotograma, sin
     * decodificación especial. Solo aplica a un pack propio (RF-15): los
     * packs semilla ya traen su `tray.png` en `assets/` (ADR-0004).
     */
    fun ensureTrayIcon(identifier: String, representativeWebpBytes: ByteArray) {
        val trayFile = File(packDir(identifier), TRAY_FILE_NAME)
        if (trayFile.exists()) return
        val decoded = BitmapFactory.decodeByteArray(representativeWebpBytes, 0, representativeWebpBytes.size) ?: return
        val crop = SquareCrop.of(decoded.width, decoded.height)
        val cropped = Bitmap.createBitmap(decoded, crop.xOffset, crop.yOffset, crop.size, crop.size)
        val scaled = cropped.scale(TRAY_ICON_SIZE, TRAY_ICON_SIZE)
        packDir(identifier).mkdirs()
        FileOutputStream(trayFile).use { out -> scaled.compress(Bitmap.CompressFormat.PNG, 100, out) }
    }

    /** RF-15: borra el pack propio entero (sus stickers, índice e ícono de bandeja) al eliminarlo. */
    fun deletePackDirectory(identifier: String) {
        packDir(identifier).deleteRecursively()
    }

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
