package dev.multiframe.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.multiframe.camera.ui.MultiframeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MultiframeTheme {
                CameraPermissionGate {
                    CameraScreen(modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/**
 * Requests CAMERA on first composition and only shows the camera once it is granted.
 */
@Composable
private fun CameraPermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted -> granted = isGranted }

    if (granted) {
        content()
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.permission_rationale),
                color = Color.White,
            )
            Button(
                onClick = { launcher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.padding(top = 24.dp),
            ) {
                Text(stringResource(R.string.grant_camera))
            }
        }
    }

    // Fire the request once on first composition when we do not already hold it.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }
}
