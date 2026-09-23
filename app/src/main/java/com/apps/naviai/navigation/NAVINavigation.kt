package com.apps.naviai.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.apps.naviai.ui.screens.CalibrationScreen
import com.apps.naviai.ui.screens.DetectionScreen
import com.apps.naviai.ui.screens.MemoryScreen
import com.apps.naviai.ui.screens.NavigationScreen
import com.apps.naviai.ui.screens.RecordingScreen
import com.apps.naviai.ui.screens.SavedRoutesScreen
import com.apps.naviai.ui.screens.SettingsScreen
import com.apps.naviai.ui.screens.SplashScreen
import java.net.URLDecoder
import java.net.URLEncoder

object NAVIDestinations {
    const val SPLASH = "splash"
    const val DETECTION = "detection"
    const val SETTINGS = "settings"
    const val CALIBRATION = "calibration"
    const val RECORDING = "recording"
    const val SAVED_ROUTES = "saved_routes"
    const val MEMORY = "memory"
    const val ROUTE_NAME_ARG = "routeName"
    const val NAVIGATION = "navigation/{$ROUTE_NAME_ARG}"

    fun navigationRoute(routeName: String) = "navigation/${URLEncoder.encode(routeName, "UTF-8")}"
}

@Composable
fun NAVINavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = NAVIDestinations.SPLASH) {
        composable(NAVIDestinations.SPLASH) {
            SplashScreen(
                onFinished = {
                    navController.navigate(NAVIDestinations.DETECTION) {
                        popUpTo(NAVIDestinations.SPLASH) { inclusive = true }
                    }
                }
            )
        }
        composable(NAVIDestinations.MEMORY) {
            MemoryScreen(onBack = { navController.popBackStack() })
        }
        composable(NAVIDestinations.DETECTION) {
            DetectionScreen(
                onOpenSettings = { navController.navigate(NAVIDestinations.SETTINGS) },
                onOpenCalibration = { navController.navigate(NAVIDestinations.CALIBRATION) },
                onOpenRecording = { navController.navigate(NAVIDestinations.RECORDING) },
                onOpenSavedRoutes = { navController.navigate(NAVIDestinations.SAVED_ROUTES) },
                onOpenMemory = { navController.navigate(NAVIDestinations.MEMORY) },
                onStartNavigation = { routeName -> navController.navigate(NAVIDestinations.navigationRoute(routeName)) }
            )
        }
        composable(NAVIDestinations.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenCalibration = { navController.navigate(NAVIDestinations.CALIBRATION) }
            )
        }
        composable(NAVIDestinations.CALIBRATION) {
            CalibrationScreen(onBack = { navController.popBackStack() })
        }
        composable(NAVIDestinations.RECORDING) {
            RecordingScreen(onBack = { navController.popBackStack() })
        }
        composable(NAVIDestinations.SAVED_ROUTES) {
            SavedRoutesScreen(
                onBack = { navController.popBackStack() },
                onNavigateRoute = { routeName -> navController.navigate(NAVIDestinations.navigationRoute(routeName)) }
            )
        }
        composable(
            NAVIDestinations.NAVIGATION,
            arguments = listOf(navArgument(NAVIDestinations.ROUTE_NAME_ARG) { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedName = backStackEntry.arguments?.getString(NAVIDestinations.ROUTE_NAME_ARG).orEmpty()
            NavigationScreen(routeName = URLDecoder.decode(encodedName, "UTF-8"), onBack = { navController.popBackStack() })
        }
    }
}
