package dev.multiframe.camera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.multiframe.camera.ui.MultiframeTheme
import dev.multiframe.camera.ui.OnboardingGate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The status bar is someone else's information. A viewfinder is a
        // frame, and a clock and a battery meter sitting inside it are telling
        // you about the phone while you are trying to look at the picture. It
        // comes back on a swipe, for anyone who wants it.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
        setContent {
            MultiframeTheme {
                // The first screen, once: the statement and the camera
                // permission (ui/Onboarding.kt). The camera follows.
                OnboardingGate {
                    CameraScreen(modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
