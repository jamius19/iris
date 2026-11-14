/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.settings

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
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
import dev.jamius.iris.ui.components.Text
import dev.jamius.iris.ui.theme.IrisTheme

/**
 * Home: the Services card (accessibility service, microphone, battery mode; re-checked on resume) and the sub-page
 * cards.
 */
@Composable
fun HomePage(onOpen: (Page) -> Unit) {
    val context = LocalContext.current
    var accessibilityEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var accessibilityTurnedOn by remember { mutableStateOf(isAccessibilityServiceTurnedOn(context)) }
    var micGranted by remember { mutableStateOf(isMicGranted(context)) }
    var batteryMode by remember { mutableStateOf(currentBatteryMode(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        accessibilityEnabled = isAccessibilityServiceEnabled(context)
        accessibilityTurnedOn = isAccessibilityServiceTurnedOn(context)
        micGranted = isMicGranted(context)
        batteryMode = currentBatteryMode(context)
    }
    // Turned on but not bound: the process died (e.g. a permission change) and the system didn't restart the
    // service (HyperOS 3 lists it under "Crashed services"); only turning it off and on rebinds it.
    val accessibilityStopped = !accessibilityEnabled && accessibilityTurnedOn
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
    val dontKillMyApp = buildAnnotatedString {
        withLink(
            LinkAnnotation.Url(
                dontKillMyAppUrl(Build.MANUFACTURER),
                TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))
            )
        ) {
            append("Phone keeps turning Iris off?")
        }
    }

    PageScaffold(title = null, onBack = null) {
        Card(verticalPadding = 15.dp) {
            StatusRow(
                icon = R.drawable.lucide_accessibility,
                iconTopPadding = 1.dp,
                title = "Accessibility Service",
                enabled = accessibilityEnabled,
                helper = if (accessibilityStopped) {
                    "Iris stopped, e.g. after a permission change. Turn it off and on again in " +
                        "Accessibility settings."
                } else {
                    "If the toggle is greyed out, open App info for Iris, tap ⋮ and choose " +
                        "\"Allow restricted settings\"."
                },
                onSetup = { context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                disabledText = if (accessibilityStopped) "Not running" else "Disabled"
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
            ServicesDivider()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusRow(
                    icon = R.drawable.lucide_battery,
                    iconTopPadding = 1.dp,
                    title = "Battery Usage",
                    enabled = batteryMode == BatteryMode.UNRESTRICTED,
                    helper = "In App info, open Battery and choose Unrestricted (\"No restrictions\" on some " +
                        "phones), so Android doesn't stop Iris in the background.",
                    onSetup = {
                        context.startActivity(
                            Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)
                            )
                        )
                    },
                    enabledText = batteryMode.label,
                    disabledText = batteryMode.label
                )
                // Lines up with the row's text: IconRow's 24 dp icon column + 11 dp gap.
                Text(
                    dontKillMyApp,
                    IrisTheme.type.helper,
                    Modifier.padding(start = 35.dp),
                    color = IrisTheme.colors.muted
                )
            }
        }
        NavCard(
            R.drawable.lucide_audio_lines,
            "Transcription Settings",
            "Set up transcription provider settings"
        ) { onOpen(Page.Transcription) }
        NavCard(
            R.drawable.lucide_sparkles,
            "Post Processing Settings",
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

/** The user's toggle (the secure setting), whether or not the service is bound. */
private fun isAccessibilityServiceTurnedOn(context: Context): Boolean {
    val enabled = android.provider.Settings.Secure.getString(
        context.contentResolver,
        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val iris = ComponentName(context, IrisAccessibilityService::class.java)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == iris }
}

private fun isMicGranted(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun currentBatteryMode(context: Context): BatteryMode = batteryMode(
    ignoringOptimizations = context.getSystemService(PowerManager::class.java)
        .isIgnoringBatteryOptimizations(context.packageName),
    backgroundRestricted = context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
)
