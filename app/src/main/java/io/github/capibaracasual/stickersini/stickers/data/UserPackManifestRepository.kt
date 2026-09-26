package io.github.capibaracasual.stickersini.stickers.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Un pack creado por el usuario (RF-15): sin base en `assets/`, a diferencia de un pack semilla. */
data class UserPackEntry(val identifier: String, val name: String, val isAnimated: Boolean)

/**
 * Persiste el nombre y tipo (animado/estático, RF-18) de cada pack que el
 * usuario creó (RF-15) — no sus stickers, que siguen viviendo en
 * [UserPackStickerRepository] con el mismo esquema de índice por pack que ya
 * usan las extensiones de los packs semilla (ADR-0010): un pack propio es,
 * para ese repositorio, un identificador más, sin ninguna diferencia.
 *
 * `filesDir/packs/user_packs.json` — array con la forma
 * `{identifier, name, animated_pack}`, mismo patrón de escritura atómica
 * (archivo temporal + rename) que [UserPackStickerRepository.writeIndexAtomically].
 */
class UserPackManifestRepository(private val context: Context) {

    fun getAll(): List<UserPackEntry> {
        val file = manifestFile()
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        return (0 until array.length()).map { parseEntry(array.getJSONObject(it)) }
    }

    fun create(name: String, isAnimated: Boolean): UserPackEntry {
        val entry = UserPackEntry(identifier = "user_${UUID.randomUUID()}", name = name, isAnimated = isAnimated)
        writeAll(getAll() + entry)
        return entry
    }

    fun rename(identifier: String, newName: String) {
        writeAll(getAll().map { if (it.identifier == identifier) it.copy(name = newName) else it })
    }

    fun delete(identifier: String) {
        writeAll(getAll().filterNot { it.identifier == identifier })
    }

    private fun writeAll(entries: List<UserPackEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("identifier", entry.identifier)
                    put("name", entry.name)
                    put("animated_pack", entry.isAnimated)
                },
            )
        }
        val dir = packsDir().apply { mkdirs() }
        val tempFile = File(dir, "user_packs.json.tmp")
        tempFile.writeText(array.toString())
        tempFile.renameTo(manifestFile())
    }

    private fun parseEntry(json: JSONObject) = UserPackEntry(
        identifier = json.getString("identifier"),
        name = json.getString("name"),
        isAnimated = json.optBoolean("animated_pack", false),
    )

    private fun packsDir() = File(context.filesDir, "packs")

    private fun manifestFile() = File(packsDir(), "user_packs.json")
}
