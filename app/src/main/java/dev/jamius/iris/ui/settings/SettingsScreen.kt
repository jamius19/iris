/*
* Author: Jamius Siam
* Since: 11/11/2026
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.jamius.iris.data.settings.CleanupPrompt
import dev.jamius.iris.data.settings.Secret
import dev.jamius.iris.data.settings.SettingsRepository
import dev.jamius.iris.overlay.IrisAccessibilityService
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun SettingsScreen(repository: SettingsRepository, modifier: Modifier = Modifier) {
    val settings = repository.settings.collectAsState(initial = null).value ?: return
    val scope = rememberCoroutineScope()
    var editingPrompt by remember { mutableStateOf<CleanupPrompt?>(null) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Iris settings", style = MaterialTheme.typography.headlineSmall)

        PermissionRows()

        SectionTitle("API keys")
        ApiKeyField(
            label = "OpenAI API key",
            isSet = settings.openAiKeySet,
            onSave = { scope.launch { repository.setApiKey(Secret.OPENAI_API_KEY, it) } },
            onClear = { scope.launch { repository.clearApiKey(Secret.OPENAI_API_KEY) } }
        )
        ApiKeyField(
            label = "Vercel AI Gateway API key",
            isSet = settings.gatewayKeySet,
            onSave = { scope.launch { repository.setApiKey(Secret.GATEWAY_API_KEY, it) } },
            onClear = { scope.launch { repository.clearApiKey(Secret.GATEWAY_API_KEY) } }
        )

        SectionTitle("Speech to text")
        Text("Provider: OpenAI Realtime")
        SavableTextField(
            label = "Model",
            value = settings.sttModel,
            onSave = { scope.launch { repository.setSttModel(it) } }
        )
        SavableTextField(
            label = "Language code (optional, e.g. en)",
            value = settings.sttLanguage,
            supportingText = "Leave blank to auto-detect.",
            allowBlank = true,
            onSave = { scope.launch { repository.setSttLanguage(it) } }
        )

        SectionTitle("Cleanup")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Clean up transcript with an LLM", modifier = Modifier.weight(1f))
            Switch(
                checked = settings.cleanupEnabled,
                onCheckedChange = { scope.launch { repository.setCleanupEnabled(it) } }
            )
        }
        Text("Provider: Vercel AI Gateway")
        SavableTextField(
            label = "Model",
            value = settings.cleanupModel,
            supportingText = "Model must support reasoning effort 'none'",
            onSave = { scope.launch { repository.setCleanupModel(it) } }
        )
        Text("Prompts", style = MaterialTheme.typography.titleSmall)
        settings.prompts.forEach { prompt ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = prompt.id == settings.activePrompt?.id,
                    onClick = { scope.launch { repository.setActivePrompt(prompt.id) } }
                )
                Text(prompt.name, modifier = Modifier.weight(1f))
                TextButton(onClick = { editingPrompt = prompt }) { Text("Edit") }
                TextButton(
                    onClick = { scope.launch { repository.deletePrompt(prompt.id) } },
                    enabled = settings.prompts.size > 1
                ) { Text("Delete") }
            }
        }
        OutlinedButton(onClick = { editingPrompt = CleanupPrompt(UUID.randomUUID().toString(), "", "") }) {
            Text("Add prompt")
        }
    }

    editingPrompt?.let { prompt ->
        PromptDialog(
            prompt = prompt,
            onDismiss = { editingPrompt = null },
            onSave = {
                scope.launch { repository.savePrompt(it) }
                editingPrompt = null
            }
        )
    }
}

/** Accessibility service and microphone status, re-checked whenever the screen resumes. */
@Composable
private fun PermissionRows() {
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

    SectionTitle("Permissions")
    StatusRow(
        label = "Accessibility service",
        status = if (accessibilityEnabled) "Enabled" else "Disabled",
        ok = accessibilityEnabled,
        supportingText = if (accessibilityEnabled) {
            null
        } else {
            "If the toggle is greyed out, open App info for Iris, tap ⋮ and choose \"Allow restricted settings\"."
        },
        actionLabel = "Open settings",
        onAction = {
            context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    )
    StatusRow(
        label = "Microphone permission",
        status = if (micGranted) "Granted" else "Denied",
        ok = micGranted,
        actionLabel = "Grant",
        onAction = if (micGranted) null else ({ micPermission.launch(Manifest.permission.RECORD_AUDIO) })
    )
}

@Composable
private fun StatusRow(
    label: String,
    status: String,
    ok: Boolean,
    actionLabel: String,
    onAction: (() -> Unit)?,
    supportingText: String? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label)
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            supportingText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        onAction?.let { OutlinedButton(onClick = it) { Text(actionLabel) } }
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

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

/** Write-only key field: the stored key is never read back into the UI, only whether one is set. */
@Composable
private fun ApiKeyField(label: String, isSet: Boolean, onSave: (String) -> Unit, onClear: () -> Unit) {
    // Plain remember (not saveable) so the key is not written to saved instance state.
    var input by remember { mutableStateOf("") }
    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        label = { Text(label) },
        supportingText = { Text(if (isSet) "Saved (••••). Enter a new key to replace it." else "Not set") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                onSave(input)
                input = ""
            },
            enabled = input.isNotBlank()
        ) { Text("Save") }
        OutlinedButton(onClick = onClear, enabled = isSet) { Text("Clear") }
    }
}

/** Text field that edits locally and writes [onSave] only when the user taps Save. */
@Composable
private fun SavableTextField(
    label: String,
    value: String,
    onSave: (String) -> Unit,
    supportingText: String? = null,
    allowBlank: Boolean = false,
) {
    // Keyed on the stored value, so the field resets to it after a save.
    var input by rememberSaveable(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        label = { Text(label) },
        supportingText = supportingText?.let { { Text(it) } },
        singleLine = true,
        trailingIcon = if (input.trim() != value) {
            {
                TextButton(onClick = { onSave(input) }, enabled = allowBlank || input.isNotBlank()) {
                    Text("Save")
                }
            }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun PromptDialog(prompt: CleanupPrompt, onDismiss: () -> Unit, onSave: (CleanupPrompt) -> Unit) {
    var name by remember { mutableStateOf(prompt.name) }
    var systemPrompt by remember { mutableStateOf(prompt.systemPrompt) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Prompt") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text("System prompt") },
                    minLines = 5,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(prompt.copy(name = name.trim(), systemPrompt = systemPrompt.trim())) },
                enabled = name.isNotBlank() && systemPrompt.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
