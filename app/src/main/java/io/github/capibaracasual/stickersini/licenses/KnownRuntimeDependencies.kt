package io.github.capibaracasual.stickersini.licenses

/**
 * Qué coordenadas `grupo:artefacto` de `releaseRuntimeClasspath` ya están
 * cubiertas por [thirdPartyLicenses], con su licencia verificada a mano
 * contra el POM/manifiesto/repositorio real de cada una (no asumida) — ver
 * ADR-0017. Agrupa por prefijo de grupo cuando el publicador es el mismo
 * para decenas de artefactos (AndroidX, Kotlin, kotlinx), y por coordenada
 * exacta cuando cubre una sola dependencia transitiva puntual.
 *
 * `ThirdPartyLicensesCoverageTest` usa esto para fallar en cuanto
 * `releaseRuntimeClasspath` sume una dependencia nueva sin clasificar, en
 * vez de dejar la pantalla de licencias desactualizada en silencio.
 */
internal object KnownRuntimeDependencies {
    fun isCovered(group: String, artifact: String): Boolean =
        group.startsWith("androidx.") ||
            group == "org.jetbrains.kotlin" ||
            group == "org.jetbrains.kotlinx" ||
            (group == "org.jetbrains" && artifact == "annotations") ||
            group == "org.jspecify" ||
            (group == "com.google.guava" && artifact == "listenablefuture")
}
