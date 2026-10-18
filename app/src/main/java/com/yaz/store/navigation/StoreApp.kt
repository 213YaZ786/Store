package com.yaz.store.navigation

// Ported from Files (YAZ/Files at HEAD, navigation/FilesApp.kt), itself from Calculator (https://github.com/213YaZ786/Calculator, navigation/CalculatorApp.kt):
// the NavHost and PlainBack (the base kit's); Files' routes are replaced by one, Home, which holds the temporary list of the catalog for now.
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.yaz.store.feature.home.HomeScreen

object Routes {
    const val HOME = "home"
}

// Files' FilesApp.
@Composable
fun StoreApp() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = Routes.HOME) {
        // Home: the temporary list of the catalog (feature/home), same NavHost route as Files' FilesApp.
        composable(Routes.HOME) { HomeScreen() }
    }
    PlainBack(nav)
}
