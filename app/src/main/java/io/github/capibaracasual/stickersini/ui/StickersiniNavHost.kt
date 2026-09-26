package io.github.capibaracasual.stickersini.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

private object Routes {
    const val HOME = "home"
    const val PICK = "create/pick"
    const val CONVERT = "create/convert"
}

/**
 * Estado del flujo de creación en curso (rutas bajo `create/`: elegir
 * archivo → convertir/guardar), compartido entre esos pasos. Vive tan
 * arriba como el `NavHost` (ADR-0013) para sobrevivir a la navegación entre
 * ellos sin serializarlo como argumento de ruta — un `Uri` no es un tipo
 * primitivo cómodo para eso.
 */
private class CreateStickerSession {
    var uri by mutableStateOf<Uri?>(null)
    var isVideo by mutableStateOf(false)
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
                    navController.navigate(Routes.CONVERT)
                },
            )
        }

        composable(Routes.CONVERT) {
            val uri = session.uri
            if (uri == null) {
                // No debería ocurrir salvo restauración de proceso a mitad
                // del flujo: sin el archivo elegido no hay nada que convertir.
                LaunchedEffect(Unit) { navController.popBackStack(Routes.HOME, inclusive = false) }
            } else {
                ConvertPreviewSaveScreen(
                    uri = uri,
                    isVideo = session.isVideo,
                    onBack = { navController.popBackStack(Routes.HOME, inclusive = false) },
                )
            }
        }
    }
}
