package com.apps.naviai.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.navigation.compose.hiltViewModel
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
import com.apps.naviai.ui.screens.ProModeScreen
import com.apps.naviai.ui.screens.RecordingScreen
import com.apps.naviai.ui.screens.SavedRoutesScreen
import com.apps.naviai.ui.screens.SettingsScreen
import com.apps.naviai.ui.screens.SplashScreen
import com.apps.naviai.ui.viewmodel.GoHomeViewModel
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
    const val PRO_MODE = "pro_mode"
    const val ROUTE_NAME_ARG = "routeName"
    const val NAVIGATION = "navigation/{$ROUTE_NAME_ARG}"

    fun navigationRoute(routeName: String) = "navigation/${URLEncoder.encode(routeName, "UTF-8")}"
}

@Composable
fun NAVINavHost(navController: NavHostController = rememberNavController()) {
    // "NAVI, kembali" / "kembali ke home" (see GoHomeCommandMatcher) -- collected once here, not
    // per-screen, so it clears the WHOLE back stack and returns to Detection regardless of how deep
    // it is (a screen's own onBack only pops one level, which isn't enough from e.g.
    // Detection -> Saved Routes -> Navigation). Each trigger already stopped whatever it owns before
    // calling GoHomeSignal.trigger() -- see that class's doc.
    val goHomeViewModel: GoHomeViewModel = hiltViewModel()
    LaunchedEffect(Unit) {
        goHomeViewModel.events.collect {
            // Repeated plain popBackStack() (not navigate()+popUpTo(inclusive = true), and not the
            // single popBackStack(route, inclusive) call either) -- Detection is this graph's start
            // destination and is never itself removed from the bottom of the stack, so this always
            // ends up back at its ORIGINAL entry instead of destroying and recreating it.
            // navigate()+popUpTo(inclusive = true) used to tear down that entry's ViewModels too,
            // including VoiceCommandViewModel -- which is what remembers a manual mic pause
            // (VoiceCommandPanel's mic button) -- so a fresh instance's LaunchedEffect always
            // auto-started listening again on the way back, silently undoing a pause the user set
            // before leaving Detection. The single-call popBackStack(DETECTION, inclusive = false)
            // form that replaced it got Detection back on screen fine, but left voice commands
            // unresponsive afterwards specifically on THIS path -- unlike a screen's own plain
            // onBack()/popBackStack() (used by e.g. exiting Pro Mode normally, which doesn't have that
            // problem) -- so this uses that exact same plain, argument-less primitive instead,
            // repeated one level at a time until Detection is on top, rather than the route-targeted
            // overload.
            while (navController.currentBackStackEntry?.destination?.route != NAVIDestinations.DETECTION) {
                if (!navController.popBackStack()) break
            }
        }
    }

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
                onStartNavigation = { routeName -> navController.navigate(NAVIDestinations.navigationRoute(routeName)) },
                onOpenProMode = { navController.navigate(NAVIDestinations.PRO_MODE) }
            )
        }
        composable(NAVIDestinations.PRO_MODE) {
            ProModeScreen(onBack = { navController.popBackStack() })
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
