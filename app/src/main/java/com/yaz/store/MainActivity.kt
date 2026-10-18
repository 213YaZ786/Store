package com.yaz.store

// Ported from Files (YAZ/Files at HEAD, MainActivity.kt), itself from Calculator (https://github.com/213YaZ786/Calculator, MainActivity.kt).
// Left out of Files' version: the APK and folder intents, the recents setting, and the FragmentActivity (its biometric prompt).
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.yaz.store.navigation.StoreApp
import com.yaz.store.ui.theme.AppSurface

/** Store, the app's one window. */
class MainActivity : ComponentActivity() {

    // Files' MainActivity.onCreate.
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { AppSurface { StoreApp() } }
    }
}
