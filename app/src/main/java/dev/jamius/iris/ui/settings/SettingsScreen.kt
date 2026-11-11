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
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.jamius.iris.R
import dev.jamius.iris.data.settings.CleanupPrompt
import dev.jamius.iris.data.settings.Secret
import dev.jamius.iris.data.settings.SettingsRepository
import dev.jamius.iris.overlay.IrisAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(repository: SettingsRepository, modifier: Modifier = Modifier) {
    val settings = repository.settings.collectAsState(initial = null).value
    val scope = rememberCoroutineScope()
    var editingPrompt by remember { mutableStateOf<CleanupPrompt?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val screenColor = MaterialTheme.colorScheme.surfaceContainer

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = screenColor,
        topBar = {
            LargeTopAppBar(
                title = { Text("Iris") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = screenColor,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        if (settings == null) return@Scaffold
        val layoutDirection = LocalLayoutDirection.current
        // Content scrolls under the navigation bar; its inset is added as bottom padding inside the scroll.
        // Only the applied padding is consumed, so imePadding() lifts the viewport by the IME's full height.
        val outerPadding = PaddingValues(
            start = innerPadding.calculateStartPadding(layoutDirection),
            top = innerPadding.calculateTopPadding(),
            end = innerPadding.calculateEndPadding(layoutDirection)
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(outerPadding)
                .consumeWindowInsets(outerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = innerPadding.calculateBottomPadding() + 24.dp)
        ) {
            SetupSection()

            Section("API keys") {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                }
            }

            Section("Transcription") {
                LabeledRow(
                    icon = R.drawable.ic_mic,
                    title = "OpenAI Realtime",
                    subtitle = "Speech-to-text provider",
                    badge = false,
                    modifier = Modifier.padding(16.dp)
                )
                CardDivider()
                FieldsColumn {
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
                }
            }

            // Not disabled, so cleanup can be configured before it is turned on; dimmed to show it is inactive.
            val cleanupAlpha = if (settings.cleanupEnabled) 1f else 0.38f
            Section("Cleanup") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = settings.cleanupEnabled,
                            role = Role.Switch,
                            onValueChange = { scope.launch { repository.setCleanupEnabled(it) } }
                        )
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LabeledRow(
                        icon = R.drawable.ic_auto_awesome,
                        title = "AI cleanup",
                        subtitle = "Polish text via Vercel AI Gateway",
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(16.dp))
                    Switch(checked = settings.cleanupEnabled, onCheckedChange = null)
                }
                CardDivider()
                FieldsColumn(modifier = Modifier.alpha(cleanupAlpha)) {
                    SavableTextField(
                        label = "Model",
                        value = settings.cleanupModel,
                        supportingText = "Model must support reasoning effort 'none'",
                        onSave = { scope.launch { repository.setCleanupModel(it) } }
                    )
                }
            }

            Section("Prompts") {
                Column(modifier = Modifier.alpha(cleanupAlpha)) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        settings.prompts.forEach { prompt ->
                            PromptRow(
                                prompt = prompt,
                                selected = prompt.id == settings.activePrompt?.id,
                                canDelete = settings.prompts.size > 1,
                                onSelect = { scope.launch { repository.setActivePrompt(prompt.id) } },
                                onEdit = { editingPrompt = prompt },
                                onDelete = { scope.launch { repository.deletePrompt(prompt.id) } }
                            )
                        }
                    }
                    CardDivider()
                    // Same start padding and 24 dp icon column as PromptRow's radio, so "+" and the label line up.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) {
                                editingPrompt = CleanupPrompt(UUID.randomUUID().toString(), "", "")
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_add),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            "Add prompt",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        editingPrompt?.let { prompt ->
            PromptDialog(
                prompt = prompt,
                isNew = settings.prompts.none { it.id == prompt.id },
                onDismiss = { editingPrompt = null },
                onSave = {
                    scope.launch { repository.savePrompt(it) }
                    editingPrompt = null
                }
            )
        }
    }
}

/** Accessibility service and microphone status, re-checked whenever the screen resumes. */
@Composable
private fun SetupSection() {
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

    Section("Setup") {
        StatusRow(
            icon = R.drawable.ic_accessibility,
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
        CardDivider()
        StatusRow(
            icon = R.drawable.ic_mic,
            label = "Microphone permission",
            status = if (micGranted) "Granted" else "Denied",
            ok = micGranted,
            actionLabel = "Grant",
            onAction = if (micGranted) null else ({ micPermission.launch(Manifest.permission.RECORD_AUDIO) })
        )
    }
}

/** A titled group of settings on a rounded card. */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        content = content
    )
}

@Composable
private fun CardDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainer)
}

/** Padding and spacing for the fields below a card's header row. */
@Composable
private fun FieldsColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@Composable
private fun IconBadge(@DrawableRes icon: Int) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * Icon with a title and a secondary line. [badge] puts the icon on the tinted circle used by interactive rows;
 * without it the icon is plain (same 40 dp column) so a static label doesn't look tappable.
 */
@Composable
private fun LabeledRow(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    badge: Boolean = true,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (badge) {
            IconBadge(icon)
        } else {
            Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusRow(
    @DrawableRes icon: Int,
    label: String,
    status: String,
    ok: Boolean,
    actionLabel: String,
    onAction: (() -> Unit)?,
    supportingText: String? = null,
) {
    val statusColor = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(if (ok) R.drawable.ic_check_circle else R.drawable.ic_error),
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(status, style = MaterialTheme.typography.labelLarge, color = statusColor)
                }
            }
        }
        supportingText?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 56.dp, top = 8.dp)
            )
        }
        onAction?.let {
            val buttonModifier = Modifier.padding(start = 56.dp, top = 12.dp)
            if (ok) {
                OutlinedButton(onClick = it, modifier = buttonModifier) { Text(actionLabel) }
            } else {
                Button(onClick = it, modifier = buttonModifier) { Text(actionLabel) }
            }
        }
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

/** Write-only key field: the stored key is never read back into the UI, only whether one is set. */
@Composable
private fun ApiKeyField(label: String, isSet: Boolean, onSave: (String) -> Unit, onClear: () -> Unit) {
    // Plain remember (not saveable) so the key is not written to saved instance state.
    var input by remember { mutableStateOf("") }
    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        label = { Text(label) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_key), contentDescription = null) },
        supportingText = { Text(if (isSet) "Saved (••••). Enter a new key to replace it." else "Not set") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        trailingIcon = when {
            input.isNotBlank() -> {
                {
                    TextButton(
                        onClick = {
                            onSave(input)
                            input = ""
                        }
                    ) { Text("Save") }
                }
            }
            isSet -> {
                {
                    IconButton(onClick = onClear) {
                        Icon(painterResource(R.drawable.ic_delete), contentDescription = "Clear $label")
                    }
                }
            }
            else -> null
        },
        modifier = Modifier.fillMaxWidth()
    )
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
    // While focused, scroll the whole field (incl. supporting text) into view once the IME inset stops changing.
    val bringIntoView = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    LaunchedEffect(focused) {
        if (focused) {
            // collectLatest restarts the wait on every inset change, so this runs after the IME animation ends.
            snapshotFlow { ime.getBottom(density) }.collectLatest {
                delay(IME_SETTLE_MS)
                bringIntoView.bringIntoView()
            }
        }
    }
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
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            .onFocusChanged { focused = it.isFocused }
    )
}

private const val IME_SETTLE_MS = 100L

@Composable
private fun PromptRow(
    prompt: CleanupPrompt,
    selected: Boolean,
    canDelete: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(prompt.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                prompt.systemPrompt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onEdit) {
            Icon(painterResource(R.drawable.ic_edit), contentDescription = "Edit ${prompt.name}")
        }
        IconButton(onClick = onDelete, enabled = canDelete) {
            Icon(painterResource(R.drawable.ic_delete), contentDescription = "Delete ${prompt.name}")
        }
    }
}

@Composable
private fun PromptDialog(
    prompt: CleanupPrompt,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (CleanupPrompt) -> Unit,
) {
    var name by remember { mutableStateOf(prompt.name) }
    var systemPrompt by remember { mutableStateOf(prompt.systemPrompt) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_auto_awesome), contentDescription = null) },
        title = { Text(if (isNew) "New prompt" else "Edit prompt") },
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
