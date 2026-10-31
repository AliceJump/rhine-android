package io.github.alicejump.rhine

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import io.github.alicejump.rhine.ui.RhineApp
import io.github.alicejump.rhine.ui.RhineTheme

class MainActivity : ComponentActivity() {
    private val model: RhineViewModel by viewModels()
    private val chooseFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) model.importFolder(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RhineTheme(dark = model.darkMode, accentIndex = model.accentIndex) {
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !model.darkMode
                        isAppearanceLightNavigationBars = !model.darkMode
                    }
                }
                RhineApp(model, onChooseFolder = { chooseFolder.launch(model.lastFolder) })
            }
        }
    }
}
