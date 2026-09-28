package io.github.capibaracasual.stickersini.licenses

import java.io.File
import org.junit.Assert.fail
import org.junit.Test

/**
 * RNF-11 / ADR-0017: si `releaseRuntimeClasspath` (con transitivas
 * incluidas, volcado por la tarea `releaseRuntimeClasspathReport` de
 * `app/build.gradle.kts` antes de correr los tests) suma una dependencia que
 * [KnownRuntimeDependencies] no reconoce, esta prueba falla en vez de dejar
 * la pantalla de licencias desactualizada en silencio.
 */
class ThirdPartyLicensesCoverageTest {
    @Test
    fun `toda dependencia de releaseRuntimeClasspath esta cubierta por la pantalla de licencias`() {
        val path = System.getProperty("stickersini.releaseRuntimeClasspathFile")
            ?: error(
                "Falta la propiedad de sistema stickersini.releaseRuntimeClasspathFile: " +
                    "esta prueba necesita correr vía Gradle (./gradlew test), no sola desde el IDE.",
            )

        val coordinates = File(path).readLines().filter { it.isNotBlank() }
        check(coordinates.isNotEmpty()) { "releaseRuntimeClasspath resolvió vacío, algo está mal con la tarea que lo genera." }

        val unclassified = coordinates.filterNot { coordinate ->
            val parts = coordinate.split(":")
            parts.size == 3 && KnownRuntimeDependencies.isCovered(parts[0], parts[1])
        }

        if (unclassified.isNotEmpty()) {
            fail(
                "Dependencia(s) runtime nueva(s) sin clasificar en la pantalla de licencias (RNF-11): " +
                    "${unclassified.joinToString(", ")}. Verificá su licencia a mano contra su repositorio " +
                    "o POM real y sumala a KnownRuntimeDependencies y a ThirdPartyLicenses antes de continuar.",
            )
        }
    }
}
