package dev.multiframe.camera.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import dev.multiframe.camera.R

/**
 * The first screen, once: what the app is, in three sentences, and the one
 * permission it needs.
 *
 * ### What it is not
 *
 * Not a tour. There is nothing to swipe through, no illustrations of features
 * and no account to make. The mission statement is the whole of the promise
 * -- a burst of raw frames, merged; no generative AI; every pixel measured --
 * and the two paragraphs under it are the two facts a person deciding
 * whether to allow the camera should have: what the camera is used for, and
 * where the photographs go. On this platform the photographs go to the
 * device's own library through the media store, which needs no permission
 * of the app's, so there is nothing to ask for on that account and the
 * screen says so rather than asking for something it does not need.
 *
 * ### The permission, and its refusal
 *
 * Continue asks for the camera. Refused once, the screen says plainly that
 * without it there is no viewfinder and asks again; refused for good (the
 * system will no longer show the request), it says that too and opens the
 * app's page in the system settings, which is the only place the decision
 * can be reversed. Nothing is greyed out and nothing pretends to work
 * without the camera.
 *
 * Shown once ([OnboardingState.seen]) and then never, unless the camera
 * permission is later withdrawn, in which case the refusal screen is what
 * the app opens to. Pure decisions in [OnboardingState] so the flow is
 * tested on the JVM; the composable is the surface.
 */
object OnboardingState {

    private const val PREFERENCES = "multiframe.onboarding"
    private const val KEY_SEEN = "seen"

    fun seen(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY_SEEN, false)

    fun markSeen(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(KEY_SEEN, true).apply()
    }

    /** Where a first run stands. */
    enum class Step {
        /** The statement and the one button. */
        INTRODUCE,
        /** The camera was refused once: say why it matters and ask again. */
        DENIED,
        /** The camera was refused for good: the system settings are the way back. */
        DENIED_PERMANENTLY,
        /** Nothing to show: the camera is the app's. */
        DONE,
    }

    /**
     * The step for the facts at hand: [granted] whether the camera is
     * allowed; [deniedOnce] whether a request has come back refused in this
     * run; [canAskAgain] whether the system would still show the request
     * (`shouldShowRequestPermissionRationale`, which is false both before
     * any request and after a permanent refusal -- hence [deniedOnce] to
     * tell those apart). A statement read before whose camera was later
     * withdrawn is introduced again: the statement is short, and it is the
     * screen with the button on it.
     */
    fun step(granted: Boolean, deniedOnce: Boolean, canAskAgain: Boolean): Step = when {
        granted -> Step.DONE
        !deniedOnce -> Step.INTRODUCE
        canAskAgain -> Step.DENIED
        else -> Step.DENIED_PERMANENTLY
    }
}

/**
 * Shows [content] once the camera is the app's, and the onboarding until it is.
 */
@Composable
fun OnboardingGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var seen by remember { mutableStateOf(OnboardingState.seen(context)) }
    var deniedOnce by remember { mutableStateOf(false) }
    var canAskAgain by remember { mutableStateOf(true) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        granted = isGranted
        if (!isGranted) {
            deniedOnce = true
            canAskAgain = activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA) } ?: false
        }
    }

    // Coming back from the system settings with the camera allowed: the
    // decision was made there, and is read again on every resume.
    LifecycleResumeEffect(Unit) {
        if (!granted) {
            granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        }
        onPauseOrDispose { }
    }

    when (OnboardingState.step(granted, deniedOnce, canAskAgain)) {
        OnboardingState.Step.DONE -> {
            if (!seen) {
                OnboardingState.markSeen(context)
                seen = true
            }
            content()
        }
        OnboardingState.Step.INTRODUCE -> OnboardingScreen(step = OnboardingState.Step.INTRODUCE) {
            OnboardingState.markSeen(context)
            seen = true
            launcher.launch(Manifest.permission.CAMERA)
        }
        OnboardingState.Step.DENIED -> OnboardingScreen(step = OnboardingState.Step.DENIED) {
            launcher.launch(Manifest.permission.CAMERA)
        }
        OnboardingState.Step.DENIED_PERMANENTLY -> OnboardingScreen(step = OnboardingState.Step.DENIED_PERMANENTLY) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/** The screen itself, for one [step]; [onPrimary] is the one button. */
@Composable
fun OnboardingScreen(step: OnboardingState.Step, onPrimary: () -> Unit) {
    val view = LocalView.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.Sheet)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp)
            .padding(top = 48.dp, bottom = 32.dp)
            .semantics { contentDescription = "onboarding ${step.name.lowercase()}" },
        verticalArrangement = Arrangement.Top,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            color = Ink.Bone,
            fontSize = 22.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.heightIn(min = 28.dp))
        Text(
            text = stringResource(R.string.onboarding_mission),
            color = Ink.SheetBody,
            fontSize = 18.sp,
            lineHeight = 26.sp,
            modifier = Modifier.semantics { contentDescription = "mission statement" },
        )

        Spacer(Modifier.heightIn(min = 36.dp))
        Text(text = stringResource(R.string.onboarding_camera_heading), style = Type.Section, color = Ink.Muted)
        Text(
            text = stringResource(R.string.onboarding_camera_body),
            color = Ink.SheetBody, fontSize = 14.sp, lineHeight = 21.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.heightIn(min = 20.dp))
        Text(text = stringResource(R.string.onboarding_storage_heading), style = Type.Section, color = Ink.Muted)
        Text(
            text = stringResource(R.string.onboarding_storage_body),
            color = Ink.SheetBody, fontSize = 14.sp, lineHeight = 21.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = stringResource(R.string.onboarding_network_body),
            color = Ink.SheetMuted, fontSize = 14.sp, lineHeight = 21.sp,
            modifier = Modifier.padding(top = 6.dp),
        )

        if (step != OnboardingState.Step.INTRODUCE) {
            Spacer(Modifier.heightIn(min = 28.dp))
            Text(
                text = stringResource(
                    if (step == OnboardingState.Step.DENIED) R.string.onboarding_denied
                    else R.string.onboarding_denied_permanently,
                ),
                color = Ink.Bone, fontSize = 14.sp, lineHeight = 21.sp,
                modifier = Modifier.semantics { contentDescription = "camera refused" },
            )
        }

        Spacer(Modifier.heightIn(min = 40.dp))
        val label = stringResource(
            when (step) {
                OnboardingState.Step.DENIED -> R.string.onboarding_try_again
                OnboardingState.Step.DENIED_PERMANENTLY -> R.string.onboarding_open_settings
                else -> R.string.onboarding_continue
            },
        )
        // The one button: a pill, filled, the weight of a selected thing.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp)
                .heightIn(min = 52.dp)
                .background(Ink.Bone, RoundedCornerShape(26.dp))
                .border(1.dp, Ink.Bone, RoundedCornerShape(26.dp))
                .clickable {
                    Haptics.segment(view)
                    onPrimary()
                }
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Text(text = label, style = Type.Control, color = Ink.OnBone)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
