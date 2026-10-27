package com.smartcess.app

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.smartcess.app.data.repository.PreferencesRepository
import com.smartcess.app.navigation.SmartCessNavGraph
import com.smartcess.app.ui.theme.SmartCessTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Single-activity Compose entry point. All screens are navigation destinations. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var prefs: PreferencesRepository

    private companion object {
        /** Minimum splash visibility so the brand reveal reads (in budget). */
        const val SPLASH_MIN_MILLIS = 750L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Brand moment: hold the splash just long enough for the reveal to
        // read (it overlaps real cold-start init; total stays in budget).
        // Reduced motion skips the wait and the exit fade entirely.
        val reduceMotion = try {
            Settings.Global.getFloat(
                contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) == 0f
        } catch (_: Settings.SettingNotFoundException) {
            false
        }
        var ready = false
        splash.setKeepOnScreenCondition { !ready }
        lifecycleScope.launch {
            if (!reduceMotion) delay(SPLASH_MIN_MILLIS)
            ready = true
        }
        splash.setOnExitAnimationListener { provider ->
            if (reduceMotion) {
                provider.remove()
            } else {
                provider.view.animate().alpha(0f).setDuration(250)
                    .withEndAction { provider.remove() }.start()
            }
        }
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
