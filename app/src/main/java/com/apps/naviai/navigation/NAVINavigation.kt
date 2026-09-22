package com.apps.naviai.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.apps.naviai.ui.screens.CalibrationScreen
import com.apps.naviai.ui.screens.DetectionScreen
import com.apps.naviai.ui.screens.SettingsScreen
import com.apps.naviai.ui.screens.SplashScreen

object NAVIDestinations {
    const val SPLASH = "splash"
    const val DETECTION = "detection"
    const val SETTINGS = "settings"
    const val CALIBRATION = "calibration"
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
        composable(NAVIDestinations.DETECTION) {
            DetectionScreen(
                onOpenSettings = { navController.navigate(NAVIDestinations.SETTINGS) },
                onOpenCalibration = { navController.navigate(NAVIDestinations.CALIBRATION) }
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
    }
}
