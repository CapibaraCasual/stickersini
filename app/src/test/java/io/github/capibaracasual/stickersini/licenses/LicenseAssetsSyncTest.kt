package io.github.capibaracasual.stickersini.licenses

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Las copias en `assets/licenses/` que muestra la pantalla de licencias
 * (RNF-11) no son la fuente de verdad: `LICENSE` en la raíz del repo lo es
 * para la licencia propia, y `webp/src/main/cpp/third_party/libwebp/` lo es
 * para libwebp (vendorizado, ADR-0005). Esta prueba falla si alguna copia
 * queda desactualizada — por ejemplo, tras actualizar libwebp siguiendo el
 * procedimiento de ADR-0005 sin acordarse de refrescar también la copia de
 * `:webp/src/main/assets/`.
 */
class LicenseAssetsSyncTest {
    @Test
    fun `la copia de la licencia propia coincide con LICENSE de la raiz`() {
        assertFileContentsEqual(
            sourceProperty = "stickersini.rootLicenseFile",
            assetProperty = "stickersini.gplAssetFile",
        )
    }

    @Test
    fun `la copia de COPYING de libwebp coincide con la vendorizada`() {
        assertFileContentsEqual(
            sourceProperty = "stickersini.libwebpCopyingSourceFile",
            assetProperty = "stickersini.libwebpCopyingAssetFile",
        )
    }

    @Test
    fun `la copia de PATENTS de libwebp coincide con la vendorizada`() {
        assertFileContentsEqual(
            sourceProperty = "stickersini.libwebpPatentsSourceFile",
            assetProperty = "stickersini.libwebpPatentsAssetFile",
        )
    }

    private fun assertFileContentsEqual(sourceProperty: String, assetProperty: String) {
        val sourceFile = requireSystemPropertyFile(sourceProperty)
        val assetFile = requireSystemPropertyFile(assetProperty)
        assertEquals(
            "El contenido de ${assetFile.path} quedó desactualizado respecto de ${sourceFile.path}",
            sourceFile.readText(),
            assetFile.readText(),
        )
    }

    private fun requireSystemPropertyFile(property: String): File {
        val path = System.getProperty(property)
            ?: error("Falta la propiedad de sistema $property: esta prueba necesita correr vía Gradle (./gradlew test).")
        val file = File(path)
        check(file.isFile) { "No existe el archivo $path (propiedad $property)." }
        return file
    }
}
