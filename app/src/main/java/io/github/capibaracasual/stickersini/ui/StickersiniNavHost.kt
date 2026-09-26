package io.github.capibaracasual.stickersini.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.capibaracasual.stickersini.media.MAX_CLIP_DURATION_MS
import io.github.capibaracasual.stickersini.media.NormalizedCrop

private object Routes {
    const val HOME = "home"
    const val PICK = "create/pick"
    const val TRIM = "create/trim"
    const val CROP = "create/crop"
    const val CONVERT = "create/convert"
}

/**
 * Estado del flujo de creación en curso (rutas bajo `create/`: elegir
 * archivo → tramo → recorte → convertir/guardar), compartido entre esos
 * pasos. Vive tan arriba como el `NavHost` (ADR-0013) para sobrevivir a la
 * navegación entre ellos sin serializarlo como argumento de ruta — un
 * `Uri` no es un tipo primitivo cómodo para eso.
 */
private class CreateStickerSession {
    var uri by mutableStateOf<Uri?>(null)
    var isVideo by mutableStateOf(false)
    var startMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(MAX_CLIP_DURATION_MS)
    var crop by mutableStateOf(NormalizedCrop.CENTERED)
}

/** Grafo de navegación completo de la app (ADR-0013). */
@Composable
fun StickersiniNavHost(navController: NavHostController = rememberNavController()) {
    val session = remember { CreateStickerSession() }

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            AddSeedPackScreen(onCreateSticker = { navController.navigate(Routes.PICK) })
        }

        composable(Routes.PICK) {
            CreateStickerPickScreen(
                onBack = { navController.popBackStack() },
                onPicked = { uri, isVideo ->
                    session.uri = uri
                    session.isVideo = isVideo
                    session.startMs = 0L
                    session.durationMs = MAX_CLIP_DURATION_MS
                    session.crop = NormalizedCrop.CENTERED
                    // El recorte de área (RF-07) aplica a video e imagen por
                    // igual; el tramo (RF-06) es propio de video, así que
                    // una imagen salta directo al recorte.
                    navController.navigate(if (isVideo) Routes.TRIM else Routes.CROP)
                },
            )
        }

        composable(Routes.TRIM) {
            val uri = session.uri
            if (uri == null) {
                // No debería ocurrir salvo restauración de proceso a mitad
                // del flujo: sin el archivo elegido no hay nada que recortar.
                LaunchedEffect(Unit) { navController.popBackStack(Routes.HOME, inclusive = false) }
            } else {
                TrimScreen(
                    uri = uri,
                    initialStartMs = session.startMs,
                    initialDurationMs = session.durationMs,
                    onBack = { navController.popBackStack() },
                    onContinue = { startMs, durationMs ->
                        session.startMs = startMs
                        session.durationMs = durationMs
                        navController.navigate(Routes.CROP)
                    },
                )
            }
        }

        composable(Routes.CROP) {
            val uri = session.uri
            if (uri == null) {
                LaunchedEffect(Unit) { navController.popBackStack(Routes.HOME, inclusive = false) }
            } else {
                CropScreen(
                    uri = uri,
                    isVideo = session.isVideo,
                    videoStartMs = session.startMs,
                    onBack = { navController.popBackStack() },
                    onContinue = { crop ->
                        session.crop = crop
                        navController.navigate(Routes.CONVERT)
                    },
                )
            }
        }

        composable(Routes.CONVERT) {
            val uri = session.uri
            if (uri == null) {
                LaunchedEffect(Unit) { navController.popBackStack(Routes.HOME, inclusive = false) }
            } else {
                ConvertPreviewSaveScreen(
                    uri = uri,
                    isVideo = session.isVideo,
                    startMs = session.startMs,
                    durationMs = session.durationMs,
                    crop = session.crop,
                    onBack = { navController.popBackStack(Routes.HOME, inclusive = false) },
                )
            }
        }
    }
}
