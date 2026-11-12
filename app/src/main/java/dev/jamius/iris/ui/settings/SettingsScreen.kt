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
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.jamius.iris.R
import dev.jamius.iris.cleanup.CleanupException
import dev.jamius.iris.cleanup.CleanupProvider
import dev.jamius.iris.cleanup.GatewayModel
import dev.jamius.iris.cleanup.vercel.isChatModel
import dev.jamius.iris.data.settings.CleanupPrompt
import dev.jamius.iris.data.settings.Secret
import dev.jamius.iris.data.settings.Settings
import dev.jamius.iris.data.settings.SettingsRepository
import dev.jamius.iris.overlay.IrisAccessibilityService
import dev.jamius.iris.stt.SttException
import dev.jamius.iris.stt.openai.hasLiveText
import dev.jamius.iris.stt.openai.isRealtimeTranscriptionModel
import dev.jamius.iris.stt.vercel.gatewaySttLive
import dev.jamius.iris.stt.vercel.isStreamingTranscriptionModel
import dev.jamius.iris.stt.vercel.isTranscriptionModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: SettingsRepository,
    cleanup: CleanupProvider,
    openAiModels: suspend () -> List<String>,
    modifier: Modifier = Modifier,
) {
    val settings = repository.settings.collectAsState(initial = null).value
    val scope = rememberCoroutineScope()
    var editingPrompt by remember { mutableStateOf<CleanupPrompt?>(null) }
    // The Gateway's model catalog, shared by the cleanup and transcription model fields and the reasoning level;
    // kept in memory only.
    var models by remember { mutableStateOf<List<GatewayModel>?>(null) }
    var modelsLoading by remember { mutableStateOf(false) }
    var modelsError by remember { mutableStateOf<String?>(null) }
    val chatModelIds = remember(models) { models?.filter(::isChatModel)?.map { it.id } }
    val sttModels = remember(models) { models?.filter(::isTranscriptionModel) }
    val sttModelIds = remember(sttModels) { sttModels?.map { it.id } }
    val liveSttModelIds = remember(sttModels) { sttModels?.filter(::isStreamingTranscriptionModel)?.map { it.id } }

    fun loadModels() {
        if (modelsLoading) return
        modelsLoading = true
        modelsError = null
        scope.launch {
            try {
                models = cleanup.listModels()
            } catch (e: CleanupException) {
                modelsError = e.message
            } finally {
                modelsLoading = false
            }
        }
    }

    // Loads the catalog when the screen opens with cleanup or Gateway transcription on, and when either is turned
    // on before it has loaded.
    val cleanupEnabled = settings?.cleanupEnabled == true
    val sttGateway = settings?.sttGateway == true
    LaunchedEffect(cleanupEnabled, sttGateway) {
        if ((cleanupEnabled || sttGateway) && models == null) loadModels()
    }

    // OpenAI's transcription models for the OpenAI model field, listed with the user's key; in memory only.
    var openAiSttModels by remember { mutableStateOf<List<String>?>(null) }
    var openAiModelsLoading by remember { mutableStateOf(false) }
    var openAiModelsError by remember { mutableStateOf<String?>(null) }
    var openAiModelsJob by remember { mutableStateOf<Job?>(null) }

    fun loadOpenAiModels() {
        if (openAiModelsLoading) return
        openAiModelsLoading = true
        openAiModelsError = null
        openAiModelsJob = scope.launch {
            try {
                openAiSttModels = openAiModels()
            } catch (e: SttException) {
                openAiModelsError = e.message
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // E.g. the stored key failed to decrypt. The exception never carries the key.
                Log.w(TAG, "Loading OpenAI models failed", e)
                openAiModelsError = "Couldn't load models"
            } finally {
                // A load cancelled by a key change leaves the state to its replacement.
                if (openAiModelsJob === coroutineContext[Job]) openAiModelsLoading = false
            }
        }
    }

    // The list depends on the key: a key change drops it and cancels a running load (its result is never written),
    // then reloads while OpenAI is selected (without a key that shows the "set your key" error, with no request).
    // With the Gateway selected, the list loads when OpenAI is selected again.
    fun onOpenAiKeyChanged() {
        openAiModelsJob?.cancel()
        openAiModelsJob = null
        openAiModelsLoading = false
        openAiModelsError = null
        openAiSttModels = null
        if (settings?.sttGateway == false) loadOpenAiModels()
    }

    // Loads when the screen opens with OpenAI transcription selected, and when it is selected before it has loaded.
    // Without a key nothing is requested and the error asks for one.
    val sttOpenAi = settings?.sttGateway == false
    LaunchedEffect(sttOpenAi) {
        if (sttOpenAi && openAiSttModels == null) loadOpenAiModels()
    }
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
                        onSave = {
                            scope.launch {
                                repository.setApiKey(Secret.OPENAI_API_KEY, it)
                                onOpenAiKeyChanged()
                            }
                        },
                        onClear = {
                            scope.launch {
                                repository.clearApiKey(Secret.OPENAI_API_KEY)
                                onOpenAiKeyChanged()
                            }
                        }
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
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    ProviderRow(
                        title = "OpenAI",
                        subtitle = "Uses the OpenAI API key",
                        selected = !settings.sttGateway,
                        keyMissing = !settings.openAiKeySet,
                        onSelect = { scope.launch { repository.setSttProvider(Settings.STT_PROVIDER_OPENAI) } }
                    )
                    ProviderRow(
                        title = "Vercel AI Gateway",
                        subtitle = "Uses the AI Gateway API key (shared with cleanup)",
                        selected = settings.sttGateway,
                        keyMissing = !settings.gatewayKeySet,
                        onSelect = {
                            scope.launch { repository.setSttProvider(Settings.STT_PROVIDER_VERCEL_GATEWAY) }
                        }
                    )
                }
                CardDivider()
                FieldsColumn {
                    if (settings.sttGateway) {
                        ModelField(
                            value = settings.gatewaySttModel,
                            models = sttModelIds,
                            loading = modelsLoading,
                            error = modelsError,
                            supportingText = "Live models show text as you speak; others transcribe after you stop. " +
                                sttModeText(settings.gatewaySttLive),
                            onLoad = ::loadModels,
                            onSave = { scope.launch { repository.setGatewaySttModel(it, gatewaySttLive(it, models)) } },
                            streams = { it in liveSttModelIds.orEmpty() }
                        )
                    } else {
                        ModelField(
                            value = settings.sttModel,
                            models = openAiSttModels,
                            loading = openAiModelsLoading,
                            error = openAiModelsError,
                            supportingText = "Realtime models (gpt-live-transcribe, gpt-transcribe, ...) stream; " +
                                "other models upload after you stop. " +
                                sttModeText(
                                    live = hasLiveText(settings.sttModel),
                                    streams = isRealtimeTranscriptionModel(settings.sttModel)
                                ),
                            onLoad = ::loadOpenAiModels,
                            onSave = { scope.launch { repository.setSttModel(it) } },
                            streams = ::isRealtimeTranscriptionModel
                        )
                    }
                    SavableTextField(
                        label = "Language code (optional, e.g. en)",
                        value = settings.sttLanguage,
                        supportingText = "Leave blank to auto-detect.",
                        allowBlank = true,
                        onSave = { scope.launch { repository.setSttLanguage(it) } }
                    )
                    SliderSetting(
                        label = "Timeout",
                        value = settings.sttTimeoutSeconds,
                        range = Settings.STT_TIMEOUT_RANGE,
                        step = 1,
                        valueText = { "$it s" },
                        supportingText = "How long to wait for the final transcript after you stop.",
                        onSave = { scope.launch { repository.setSttTimeoutSeconds(it) } }
                    )
                }
                CardDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = settings.vadEnabled,
                            role = Role.Switch,
                            onValueChange = { scope.launch { repository.setVadEnabled(it) } }
                        )
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LabeledRow(
                        icon = R.drawable.ic_mic,
                        title = "Skip silence",
                        subtitle = "Detects speech on-device and sends only that, to cut cost",
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(16.dp))
                    Switch(checked = settings.vadEnabled, onCheckedChange = null)
                }
                CardDivider()
                FieldsColumn {
                    SliderSetting(
                        label = "Speed",
                        value = settings.sttSpeedPercent,
                        range = Settings.STT_SPEED_PERCENT_RANGE,
                        step = 10,
                        valueText = { "${it / 100f}x" }, // "1.0x" .. "2.0x"; Float.toString, no locale comma
                        supportingText = "Speeds up the audio sent (pitch kept), so less is billed. Higher speeds " +
                            "can lower accuracy; services with a per-request minimum may not save.",
                        onSave = { scope.launch { repository.setSttSpeedPercent(it) } }
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
                    ModelField(
                        value = settings.cleanupModel,
                        models = chatModelIds,
                        loading = modelsLoading,
                        error = modelsError,
                        supportingText = "Model must support structured output",
                        onLoad = ::loadModels,
                        onSave = { scope.launch { repository.setCleanupModel(it) } }
                    )
                    ReasoningEffortField(
                        value = settings.cleanupReasoningEffort,
                        efforts = models?.firstOrNull { it.id == settings.cleanupModel }?.reasoningEfforts,
                        onSelect = { scope.launch { repository.setCleanupReasoningEffort(it) } }
                    )
                    SliderSetting(
                        label = "Timeout",
                        value = settings.cleanupTimeoutSeconds,
                        range = Settings.CLEANUP_TIMEOUT_RANGE,
                        step = 1,
                        valueText = { "$it s" },
                        supportingText = "If cleanup takes longer, the original text is inserted.",
                        onSave = { scope.launch { repository.setCleanupTimeoutSeconds(it) } }
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

            Section("Text panel") {
                FieldsColumn {
                    SliderSetting(
                        label = "Lines",
                        value = settings.panelLines,
                        range = Settings.PANEL_LINES_RANGE,
                        step = 1,
                        valueText = { "$it" },
                        supportingText = "Notices show at least 4 lines.",
                        onSave = { scope.launch { repository.setPanelLines(it) } }
                    )
                    SliderSetting(
                        label = "Max width",
                        value = settings.panelWidthPercent,
                        range = Settings.PANEL_WIDTH_PERCENT_RANGE,
                        step = 10,
                        valueText = { "$it %" },
                        onSave = { scope.launch { repository.setPanelWidthPercent(it) } }
                    )
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

/** Icon (on the tinted circle used by interactive rows) with a title and a secondary line. */
@Composable
private fun LabeledRow(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon)
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

/** Integer slider over [range] in [step]s; [onSave] runs when the user lets go, with a changed value. */
@Composable
private fun SliderSetting(
    label: String,
    value: Int,
    range: IntRange,
    step: Int,
    valueText: (Int) -> String,
    onSave: (Int) -> Unit,
    supportingText: String? = null,
) {
    // Keyed on the stored value, so it follows saves.
    var position by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                valueText(position.roundToInt()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        supportingText?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = position,
            onValueChange = { position = it },
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = valueText(position.roundToInt())
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / step - 1,
            onValueChangeFinished = { position.roundToInt().takeIf { it != value }?.let(onSave) }
        )
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
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewAboveIme()
    )
}

/**
 * Model combobox: type any model id, or pick one from the provider's list (filtered by what was typed).
 * The list ([models], [loading], [error]) is passed in; [onLoad] fetches it, on first open if not loaded yet or on
 * refresh. Menu items of models that [streams] end with the audio-lines icon. Saves like [SavableTextField].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelField(
    value: String,
    models: List<String>?,
    loading: Boolean,
    error: String?,
    supportingText: String,
    onLoad: () -> Unit,
    onSave: (String) -> Unit,
    streams: (String) -> Boolean = { false },
) {
    var input by rememberSaveable(value) { mutableStateOf(value) }
    // What the user typed to filter the list; empty shows the whole list. Cleared when the field opens the menu.
    var query by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var menuWidth by remember { mutableStateOf(0.dp) }
    var fetchedOnOpen by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    val filtered = remember(models, query) {
        models.orEmpty().filter { it.contains(query.trim(), ignoreCase = true) }
    }
    val expanded = menuOpen && filtered.isNotEmpty()

    fun close() {
        menuOpen = false
        query = ""
    }

    // Auto-fetch on the first open. An effect, so a toggle undone in the same event (see below) fetches nothing.
    LaunchedEffect(menuOpen) {
        if (menuOpen && models == null && !loading && !fetchedOnOpen) {
            fetchedOnOpen = true
            onLoad()
        }
    }

    // The box handles back only while it's expanded (see below), so close a menu opened by refresh here.
    BackHandler(enabled = expanded && !focused) { menuOpen = false }

    // Centres the 48dp refresh button on the field's box, which sits half the floating label's line height lower.
    val refreshTop = with(density) { MaterialTheme.typography.bodySmall.lineHeight.toDp() } / 2 +
        (OutlinedTextFieldDefaults.MinHeight - 48.dp) / 2

    Row {
        // The box focuses the field (opening the IME) while expanded, so it only gets `expanded` while the field
        // is focused: refresh then opens the menu without focusing it. The field's anchor also sees taps on the
        // trailing Save button (it toggles the menu before its onClick runs), so Save closes the menu explicitly.
        ExposedDropdownMenuBox(
            expanded = expanded && focused,
            onExpandedChange = {
                if (it && !expanded) {
                    query = ""
                    menuOpen = true
                } else {
                    menuOpen = false
                }
            },
            modifier = Modifier
                .weight(1f)
                .onSizeChanged { menuWidth = with(density) { it.width.toDp() } }
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    query = it
                    menuOpen = true
                },
                label = { Text("Model") },
                supportingText = {
                    Column {
                        Text(supportingText)
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None
                ),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ExposedDropdownMenuDefaults.TrailingIcon(
                            expanded = expanded,
                            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.SecondaryEditable)
                        )
                        if (input.trim() != value) {
                            TextButton(
                                onClick = {
                                    close()
                                    onSave(input)
                                },
                                enabled = input.isNotBlank()
                            ) { Text("Save") }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                    .onFocusChanged { focused = it.isFocused }
                    .bringIntoViewAboveIme(contentKey = error)
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { menuOpen = false }) {
                // Lazy, as the list has hundreds of models. The menu measures its content's intrinsic size, which
                // lazy lists don't support, so the list gets a fixed size: the field's width and its items' height
                // (one line each), capped.
                LazyColumn(
                    modifier = Modifier
                        .width(menuWidth)
                        .height(minOf(MODEL_MENU_ITEM_HEIGHT * filtered.size, MODEL_MENU_MAX_HEIGHT))
                ) {
                    items(filtered) { model ->
                        DropdownMenuItem(
                            text = { Text(model, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = {
                                input = model
                                close()
                            },
                            trailingIcon = if (streams(model)) {
                                {
                                    Icon(
                                        painterResource(R.drawable.ic_audio_lines),
                                        contentDescription = "Streams",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            } else {
                                null
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                        )
                    }
                }
            }
        }
        if (loading) {
            Box(
                modifier = Modifier
                    .padding(top = refreshTop)
                    .size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        } else {
            IconButton(
                onClick = {
                    onLoad()
                    menuOpen = true
                },
                modifier = Modifier.padding(top = refreshTop)
            ) {
                Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Refresh models")
            }
        }
    }
}

private val MODEL_MENU_MAX_HEIGHT = 280.dp

/** The saved transcription model's mode, for the model field's hint; [streams] without [live]: text after stop. */
private fun sttModeText(live: Boolean, streams: Boolean = live): String = when {
    live -> "Mode: live"
    streams -> "Mode: streams, text after stop"
    else -> "Mode: after stop"
}

/** A one-line [DropdownMenuItem]'s height (its minimum, M3 menu list item token). */
private val MODEL_MENU_ITEM_HEIGHT = 48.dp

/**
 * Read-only dropdown of reasoning levels: "Model default" plus [efforts] (the model's own list), or all levels the
 * Gateway accepts if that's unknown. Saves on selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReasoningEffortField(value: String?, efforts: List<String>?, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = reasoningEffortLabel(value),
            onValueChange = {},
            readOnly = true,
            label = { Text("Reasoning level") },
            supportingText = if (value != null && efforts != null && value !in efforts) {
                { Text("Not listed for this model", color = MaterialTheme.colorScheme.error) }
            } else {
                null
            },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (listOf(null) + (efforts ?: Settings.REASONING_EFFORTS)).forEach { option ->
                DropdownMenuItem(
                    text = { Text(reasoningEffortLabel(option)) },
                    onClick = {
                        expanded = false
                        if (option != value) onSelect(option)
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

private fun reasoningEffortLabel(effort: String?): String = when (effort) {
    null -> "Model default"
    "xhigh" -> "Extra high"
    else -> effort.replaceFirstChar { it.uppercase() }
}

/**
 * While focused, scrolls the whole field (incl. supporting text) into view once the IME inset stops changing,
 * and again when [contentKey] changes (e.g. an error grows the supporting text).
 */
@Composable
private fun Modifier.bringIntoViewAboveIme(contentKey: Any? = null): Modifier {
    val bringIntoView = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    LaunchedEffect(focused, contentKey) {
        if (focused) {
            // collectLatest restarts the wait on every inset change, so this runs after the IME animation ends.
            snapshotFlow { ime.getBottom(density) }.collectLatest {
                delay(IME_SETTLE_MS)
                bringIntoView.bringIntoView()
            }
        }
    }
    return bringIntoViewRequester(bringIntoView).onFocusChanged { focused = it.isFocused }
}

private const val IME_SETTLE_MS = 100L

private const val TAG = "SettingsScreen"

/** A speech-to-text provider choice; if selected, it flags its key as not set when [keyMissing]. */
@Composable
private fun ProviderRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    keyMissing: Boolean,
    onSelect: () -> Unit,
) {
    val errorColor = MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                buildAnnotatedString {
                    append(subtitle)
                    if (selected && keyMissing) withStyle(SpanStyle(color = errorColor)) { append(" · not set") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

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
