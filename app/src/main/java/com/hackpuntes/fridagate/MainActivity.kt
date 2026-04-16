package com.hackpuntes.fridagate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.hackpuntes.fridagate.ui.about.AboutScreen
import com.hackpuntes.fridagate.ui.dashboard.DashboardScreen
import com.hackpuntes.fridagate.ui.frida.FridaScreen
import com.hackpuntes.fridagate.ui.proxy.ProxyScreen
import com.hackpuntes.fridagate.ui.theme.FridagateTheme

/**
 * MainActivity - The single Activity that hosts the entire app.
 *
 * In modern Android with Jetpack Compose, apps typically have just ONE Activity.
 * All navigation between screens happens inside Compose using a NavController,
 * instead of starting new Activities or swapping Fragments.
 *
 * Structure:
 *  MainActivity (Activity)
 *  └── FridagateTheme (applies colors, typography)
 *      └── FridagateApp (Scaffold with BottomNavigationBar)
 *          ├── DashboardScreen  (tab: Home)
 *          ├── FridaScreen      (tab: Frida)
 *          └── ProxyScreen      (tab: Proxy)
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // enableEdgeToEdge makes the app draw behind the system status bar and navigation bar
        // giving a more immersive, modern look
        enableEdgeToEdge()

        // setContent replaces the traditional setContentView(R.layout.activity_main)
        // Everything inside this block is Compose UI
        setContent {
            FridagateTheme {
                FridagateApp()
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Navigation routes — simple string constants used to identify each screen
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Defines the navigation routes for each tab.
 * These strings are used by NavController to navigate between screens.
 * Using an object with constants prevents typos when referencing routes.
 */
object Routes {
    const val DASHBOARD = "dashboard"
    const val FRIDA = "frida"
    const val PROXY = "proxy"
    const val ABOUT = "about"
}

/**
 * Data class representing one tab in the bottom navigation bar.
 *
 * @param route     Navigation route string (must match a composable in NavHost)
 * @param label     Text shown under the icon
 * @param icon      Icon displayed in the tab
 */
data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

/**
 * FridagateApp - Root composable that sets up the navigation scaffold.
 *
 * Scaffold is a Material3 layout that provides slots for common UI elements:
 *  - topBar: toolbar at the top
 *  - bottomBar: navigation bar at the bottom
 *  - content: the main content area (fills the remaining space)
 *
 * NavController tracks which screen is currently shown and handles
 * back-stack management (pressing Back goes to the previous screen).
 *
 * @OptIn(ExperimentalMaterial3Api::class) tells the compiler we are aware that
 * TopAppBar is marked as experimental (may change in future versions) and we
 * accept that risk. Without this annotation, the build fails as a safety measure.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FridagateApp() {
    // rememberNavController creates a NavController that survives recompositions
    val navController = rememberNavController()

    // Define the three tabs
    val navItems = listOf(
        BottomNavItem(Routes.DASHBOARD, "Dashboard", Icons.Default.Home),
        BottomNavItem(Routes.FRIDA,     "Frida",     Icons.Default.Star),
        BottomNavItem(Routes.PROXY,     "Proxy",     Icons.Default.Settings),
        BottomNavItem(Routes.ABOUT,     "About",     Icons.Default.Info)
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),

        // ── Top App Bar ───────────────────────────────────────────────────────
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Fridagate",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },

        // ── Bottom Navigation Bar ─────────────────────────────────────────────
        bottomBar = {
            // currentBackStackEntry changes every time the user navigates
            // We collect it as State so the bottom bar recomposes when it changes
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination

            NavigationBar {
                navItems.forEach { item ->
                    NavigationBarItem(
                        // selected = true highlights this tab
                        // hierarchy checks if this route is anywhere in the current back stack
                        selected = currentDestination?.hierarchy?.any { it.route == item.route } == true,
                        onClick = {
                            navController.navigate(item.route) {
                                // Pop back stack to the start destination before navigating
                                // This prevents building up a large back stack when tapping tabs
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true // Save the state of the tab we're leaving
                                }
                                launchSingleTop = true  // Don't create a new instance if already on this tab
                                restoreState = true     // Restore the saved state when returning to a tab
                            }
                        },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) }
                    )
                }
            }
        }

    ) { innerPadding ->
        // ── NavHost — renders the correct screen for the current route ─────────
        // innerPadding is provided by Scaffold to avoid drawing behind the top/bottom bars
        NavHost(
            navController = navController,
            startDestination = Routes.DASHBOARD, // Dashboard is the first screen shown
            modifier = Modifier.padding(innerPadding)
        ) {
            // Each composable {} block maps a route string to a screen composable
            composable(Routes.DASHBOARD) { DashboardScreen() }
            composable(Routes.FRIDA)     { FridaScreen() }
            composable(Routes.PROXY)     { ProxyScreen() }
            composable(Routes.ABOUT)     { AboutScreen() }
        }
    }
}
