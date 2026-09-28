package io.github.capibaracasual.stickersini.licenses

/**
 * Un texto legal completo, embebido en `assets/licenses/` de `:app` o `:webp`
 * (sin red, RNF-01). La ruta es relativa a la raíz de assets del APK ya
 * fusionado: AGP mezcla los `assets/` de cada módulo Android en uno solo.
 */
enum class LicenseAsset(val assetPath: String) {
    GPL_3_0("licenses/gpl-3.0.txt"),
    APACHE_2_0("licenses/apache-2.0.txt"),
    LIBWEBP_COPYING("licenses/libwebp-COPYING.txt"),
    LIBWEBP_PATENTS("licenses/libwebp-PATENTS.txt"),
    FREDOKA_OFL("licenses/fredoka-OFL.txt"),
    KARLA_OFL("licenses/karla-OFL.txt"),
}

/** Una fila de la pantalla de licencias (RNF-11, ADR-0017). */
data class ThirdPartyLicense(
    val name: String,
    val licenseName: String,
    val detail: String,
    val texts: List<LicenseAsset>,
)

/**
 * Lista a mano de todo lo que `releaseRuntimeClasspath` distribuye de verdad
 * en el APK, agrupada por quién lo publica y no artefacto por artefacto
 * (serían más de cien filas para una pantalla de créditos: AndroidX y
 * Compose solos ya son unos 100). [KnownRuntimeDependencies] cubre la misma
 * agrupación del lado de la verificación, y
 * `ThirdPartyLicensesCoverageTest` falla si `releaseRuntimeClasspath` suma
 * una coordenada que ninguna de las dos reconoce — ver ADR-0017 para por qué
 * esto es manual y no un plugin de licencias.
 *
 * Dependencias de solo test/build (JUnit, Mockito, androidx.test) no
 * aparecen acá a propósito: no se distribuyen en el APK, así que RNF-11 no
 * aplica.
 */
val thirdPartyLicenses = listOf(
    ThirdPartyLicense(
        name = "AndroidX y Jetpack Compose",
        licenseName = "Apache License 2.0",
        detail = "Google. Incluye Activity, Core, Compose (UI, Material 3, Foundation, animación), " +
            "Lifecycle, Navigation, Window y sus dependencias transitivas.",
        texts = listOf(LicenseAsset.APACHE_2_0),
    ),
    ThirdPartyLicense(
        name = "Kotlin",
        licenseName = "Apache License 2.0",
        detail = "JetBrains. Biblioteca estándar (kotlin-stdlib).",
        texts = listOf(LicenseAsset.APACHE_2_0),
    ),
    ThirdPartyLicense(
        name = "kotlinx.coroutines",
        licenseName = "Apache License 2.0",
        detail = "JetBrains.",
        texts = listOf(LicenseAsset.APACHE_2_0),
    ),
    ThirdPartyLicense(
        name = "kotlinx.serialization",
        licenseName = "Apache License 2.0",
        detail = "JetBrains. Dependencia transitiva de Navigation Compose.",
        texts = listOf(LicenseAsset.APACHE_2_0),
    ),
    ThirdPartyLicense(
        name = "JetBrains Java Annotations",
        licenseName = "Apache License 2.0",
        detail = "JetBrains. Dependencia transitiva.",
        texts = listOf(LicenseAsset.APACHE_2_0),
    ),
    ThirdPartyLicense(
        name = "JSpecify",
        licenseName = "Apache License 2.0",
        detail = "The JSpecify Authors. Dependencia transitiva.",
        texts = listOf(LicenseAsset.APACHE_2_0),
    ),
    ThirdPartyLicense(
        name = "Guava (solo ListenableFuture)",
        licenseName = "Apache License 2.0",
        detail = "Google. Dependencia transitiva; se distribuye solo la clase ListenableFuture, " +
            "no el resto de Guava.",
        texts = listOf(LicenseAsset.APACHE_2_0),
    ),
    ThirdPartyLicense(
        name = "libwebp",
        licenseName = "BSD-3-Clause + concesión de patentes",
        detail = "Google / WebM Project. Vendorizado en el módulo :webp, v1.6.0 " +
            "(commit 4fa21912338357f89e4fd51cf2368325b59e9bd9, ver ADR-0005).",
        texts = listOf(LicenseAsset.LIBWEBP_COPYING, LicenseAsset.LIBWEBP_PATENTS),
    ),
    ThirdPartyLicense(
        name = "Fredoka",
        licenseName = "SIL Open Font License 1.1",
        detail = "Tipografía de títulos de la dirección visual \"Plancha de stickers\".",
        texts = listOf(LicenseAsset.FREDOKA_OFL),
    ),
    ThirdPartyLicense(
        name = "Karla",
        licenseName = "SIL Open Font License 1.1",
        detail = "Tipografía de texto de la dirección visual \"Plancha de stickers\".",
        texts = listOf(LicenseAsset.KARLA_OFL),
    ),
)
