plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.capibaracasual.stickersini.webp"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Las cuatro ABI que Android usa hoy en dispositivos reales.
            // Sin armeabi-v7a/x86, el AAB no restringía la instalación a
            // arm64-v8a/x86_64: un teléfono de 32 bits puro instalaba la
            // app igual y NativeWebpEncoder fallaba al cargar la librería
            // en cuanto algo la invocara. Ver docs/desarrollo/pruebas.md.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=none")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
