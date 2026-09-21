package io.github.capibaracasual.stickersini.webp

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Escribe cada línea de inmediato (con flush) a un archivo en el
 * almacenamiento propio de la app de test, además de logcat. El búfer de
 * logcat es circular y puede perder líneas de una corrida larga (pasó el
 * 2026-09-20: se perdieron los intentos 1 a 12 de una corrida de 14). Un
 * archivo en disco no tiene ese límite y sobrevive aunque el proceso muera
 * a mitad de la corrida, porque cada línea se vuelca al salir de [line].
 *
 * Vive en `getExternalFilesDir` (específico de la app de test, sin permisos
 * adicionales) para poder traerlo con `adb pull` sin root.
 */
internal class TraceWriter(fileName: String) {
    val file: File = File(
        InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
        fileName,
    )
    private val writer = FileWriter(file, /* append = */ false)

    init {
        writer.write("=== inicio de corrida: ${TIMESTAMP_FORMAT.format(Date())} ===\n")
        writer.flush()
    }

    fun line(text: String) {
        writer.write("[${TIMESTAMP_FORMAT.format(Date())}] $text\n")
        writer.flush()
    }

    fun close() {
        writer.write("=== fin de corrida: ${TIMESTAMP_FORMAT.format(Date())} ===\n")
        writer.close()
    }

    private companion object {
        val TIMESTAMP_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
    }
}
