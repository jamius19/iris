/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.settings

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.jamius.iris.R
import dev.jamius.iris.overlay.IrisAccessibilityService
import dev.jamius.iris.ui.components.Card
import dev.jamius.iris.ui.components.NavCard
import dev.jamius.iris.ui.components.PageScaffold
import dev.jamius.iris.ui.components.ServicesDivider
import dev.jamius.iris.ui.components.StatusRow

/** Home: the Services card (accessibility service, microphone; re-checked on resume) and the sub-page cards. */
@Composable
fun HomePage(onOpen: (Page) -> Unit) {
    val context = LocalContext.current
    var accessibilityEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var micGranted by remember { mutableStateOf(isMicGranted(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        accessibilityEnabled = isAccessibilityServiceEnabled(context)
        micGranted = isMicGranted(context)
    }
    // Dismissing the first-ever prompt also returns false with no rationale, so App info needs an earlier request.
    var micRequested by rememberSaveable { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        micGranted = it
        // Permanently denied: the system returns false without a dialog, so send the user to App info instead.
        val activity = context as Activity
        if (!it && !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) && micRequested) {
            context.startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                )
            )
        }
        micRequested = true
    }

    PageScaffold(title = null, onBack = null) {
        Card(verticalPadding = 15.dp) {
            StatusRow(
                icon = R.drawable.lucide_accessibility,
                iconTopPadding = 1.dp,
                title = "Accessibility Service",
                enabled = accessibilityEnabled,
                helper = "If the toggle is greyed out, open App info for Iris, tap ⋮ and choose " +
                    "\"Allow restricted settings\".",
                onSetup = { context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            )
            ServicesDivider()
            StatusRow(
                icon = R.drawable.lucide_mic,
                iconTopPadding = 2.dp,
                title = "Microphone Permission",
                enabled = micGranted,
                helper = null,
                onSetup = { micPermission.launch(Manifest.permission.RECORD_AUDIO) }
            )
        }
        NavCard(
            R.drawable.lucide_audio_lines,
            "Transcription Provider",
            "Set up transcription provider settings"
        ) { onOpen(Page.Transcription) }
        NavCard(
            R.drawable.lucide_sparkles,
            "Post Processing Provider",
            "Set up post processing for the transcribed text"
        ) { onOpen(Page.PostProcessing) }
        NavCard(R.drawable.lucide_eye, "Appearance", "Customize Iris to fit you") { onOpen(Page.Appearance) }
        NavCard(
            R.drawable.lucide_gauge,
            "Statistics",
            "Transcription and cleanup times and usage"
        ) { onOpen(Page.Statistics) }
    }
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean =
    context.getSystemService(AccessibilityManager::class.java)
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any {
            it.resolveInfo.serviceInfo.packageName == context.packageName &&
                it.resolveInfo.serviceInfo.name == IrisAccessibilityService::class.java.name
        }

private fun isMicGranted(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
