plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.capibaracasual.stickersini"
    // compileSdk 36 en vez del último estable: ver el comentario en
    // gradle/libs.versions.toml.
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.capibaracasual.stickersini"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sin keystore de release todavía (pendiente de definir antes de
            // publicar, ver README "applicationId pendiente"): firma con la
            // key de debug para poder instalar y probar el build real
            // (-O2 nativo, RelWithDebInfo) fuera de este equipo. No usar
            // para publicar — ver ADR-0019.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":webp"))
    implementation(project(":yuv"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

/**
 * Volcado de `releaseRuntimeClasspath` a un archivo de texto (una coordenada
 * `grupo:artefacto:versión` resuelta por línea), para que
 * `ThirdPartyLicensesCoverageTest` (RNF-11, ADR-0017) pueda comparar contra
 * lo que de verdad se distribuye en el APK — con transitivas incluidas, no
 * solo lo declarado a mano en `gradle/libs.versions.toml`. Se resuelve en
 * `doLast`, no al configurar la tarea: resolver una configuración durante la
 * configuración del build ralentiza cualquier otra tarea del módulo.
 */
val releaseRuntimeClasspathFile = layout.buildDirectory.file("licenses/releaseRuntimeClasspath.txt")

val releaseRuntimeClasspathReport = tasks.register("releaseRuntimeClasspathReport") {
    outputs.file(releaseRuntimeClasspathFile)
    doLast {
        // Se camina el resultado de resolución del grafo de dependencias
        // (`resolutionResult`), no los artefactos (`resolvedConfiguration`
        // / `artifactView`): esto último fuerza a Gradle a elegir una
        // variante de artefacto para los módulos locales (:webp, :yuv) y
        // eso es ambiguo fuera del contexto normal de compilación. Lo único
        // que hace falta acá son las coordenadas resueltas, no los archivos.
        val coordinates = configurations.getByName("releaseRuntimeClasspath")
            .incoming.resolutionResult.allComponents
            .mapNotNull { component ->
                val id = component.id
                if (id is org.gradle.api.artifacts.component.ModuleComponentIdentifier) {
                    "${id.group}:${id.module}:${id.version}"
                } else {
                    null // Módulos del propio proyecto (:app, :webp, :yuv), no terceros.
                }
            }
            .distinct()
            .sorted()
        val file = releaseRuntimeClasspathFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(coordinates.joinToString("\n"))
    }
}

tasks.withType<Test>().configureEach {
    dependsOn(releaseRuntimeClasspathReport)
    systemProperty("stickersini.releaseRuntimeClasspathFile", releaseRuntimeClasspathFile.get().asFile.absolutePath)

    // Rutas para que LicenseAssetsSyncTest detecte si una copia vendorizada
    // (libwebp, ADR-0005) o la licencia propia del repo cambian sin que se
    // actualice la copia embebida en assets/ que usa la pantalla de
    // licencias (RNF-11, ADR-0017).
    systemProperty("stickersini.rootLicenseFile", rootProject.file("LICENSE").absolutePath)
    systemProperty("stickersini.gplAssetFile", project.file("src/main/assets/licenses/gpl-3.0.txt").absolutePath)
    systemProperty(
        "stickersini.libwebpCopyingSourceFile",
        rootProject.file("webp/src/main/cpp/third_party/libwebp/COPYING").absolutePath,
    )
    systemProperty(
        "stickersini.libwebpCopyingAssetFile",
        rootProject.file("webp/src/main/assets/licenses/libwebp-COPYING.txt").absolutePath,
    )
    systemProperty(
        "stickersini.libwebpPatentsSourceFile",
        rootProject.file("webp/src/main/cpp/third_party/libwebp/PATENTS").absolutePath,
    )
    systemProperty(
        "stickersini.libwebpPatentsAssetFile",
        rootProject.file("webp/src/main/assets/licenses/libwebp-PATENTS.txt").absolutePath,
    )
}
