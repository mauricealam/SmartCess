package com.smartcess.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.smartcess.app.data.repository.PreferencesRepository
import com.smartcess.app.navigation.SmartCessNavGraph
import com.smartcess.app.ui.theme.SmartCessTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Single-activity Compose entry point. All screens are navigation destinations. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var prefs: PreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val preferences by prefs.observe().collectAsState(initial = null)
            SmartCessTheme(
                highContrast = preferences?.highContrast == true,
                largeText = preferences?.largeText == true,
            ) {
                SmartCessNavGraph()
            }
        }
    }
}
