package dev.jamius.iris

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.jamius.iris.ui.settings.SettingsScreen
import dev.jamius.iris.ui.theme.IrisTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settingsRepository = (application as IrisApp).container.settingsRepository
        setContent {
            IrisTheme {
                SettingsScreen(repository = settingsRepository)
            }
        }
    }
}
