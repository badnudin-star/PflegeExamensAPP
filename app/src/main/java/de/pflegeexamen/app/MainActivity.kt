package de.pflegeexamen.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import de.pflegeexamen.app.ui.navigation.LearningApp
import de.pflegeexamen.app.ui.navigation.AppUpdatePrompt
import de.pflegeexamen.app.ui.theme.PflegeExamenTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as PflegeExamenApp
        setContent {
            PflegeExamenTheme {
                LearningApp(store = app.contentStore)
                if (BuildConfig.ENABLE_APP_UPDATES) AppUpdatePrompt(app.appUpdates)
            }
        }
    }
}
