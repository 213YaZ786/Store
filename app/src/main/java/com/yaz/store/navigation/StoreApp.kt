package com.yaz.store.navigation

// Ported from Files (YAZ/Files at HEAD, navigation/FilesApp.kt), itself from Calculator (https://github.com/213YaZ786/Calculator, navigation/CalculatorApp.kt):
// the NavHost and PlainBack (the base kit's); Files' routes are replaced by one, Home, which holds the base kit's EmptyZone for now.
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.yaz.store.ui.component.EmptyZone

object Routes {
    const val HOME = "home"
}

// Files' FilesApp.
@Composable
fun StoreApp() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyZone(title = "Store", message = "")
            }
        }
    }
    PlainBack(nav)
}
