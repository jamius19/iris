/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import dev.jamius.iris.R
import dev.jamius.iris.cleanup.GatewayModel
import dev.jamius.iris.data.settings.CleanupPrompt
import dev.jamius.iris.data.settings.Secret
import dev.jamius.iris.data.settings.Settings
import dev.jamius.iris.data.settings.SettingsRepository
import dev.jamius.iris.ui.components.ApiKeyField
import dev.jamius.iris.ui.components.AutosaveTextField
import dev.jamius.iris.ui.components.Card
import dev.jamius.iris.ui.components.ControlShape
import dev.jamius.iris.ui.components.DISABLED_ALPHA
import dev.jamius.iris.ui.components.FieldGroup
import dev.jamius.iris.ui.components.Icon
import dev.jamius.iris.ui.components.IconAction
import dev.jamius.iris.ui.components.IconRow
import dev.jamius.iris.ui.components.ModelCombobox
import dev.jamius.iris.ui.components.PageScaffold
import dev.jamius.iris.ui.components.Saver
import dev.jamius.iris.ui.components.Section
import dev.jamius.iris.ui.components.SelectDropdown
import dev.jamius.iris.ui.components.SelectField
import dev.jamius.iris.ui.components.Separator
import dev.jamius.iris.ui.components.SliderGroup
import dev.jamius.iris.ui.components.SwitchRow
import dev.jamius.iris.ui.components.Text
import dev.jamius.iris.ui.components.rememberSaveState
import dev.jamius.iris.ui.theme.IrisTheme
import java.util.UUID

/**
 * Post Processing Provider: the AI Cleanup switch, then (dimmed while it's off, still editable) the provider, its
 * key, model, reasoning level, timeout and provider options, and the prompts.
 */
@Composable
fun PostProcessingPage(
    settings: Settings,
    repository: SettingsRepository,
    saver: Saver,
    catalogs: ModelCatalogs,
    onBack: () -> Unit,
) {
    val provider = if (settings.cleanupOpenRouter) Settings.CLEANUP_PROVIDER_OPENROUTER
    else Settings.CLEANUP_PROVIDER_VERCEL_GATEWAY
    // Not disabled, so cleanup can be configured before it is turned on; dimmed to show it is inactive.
    val dim by animateFloatAsState(if (settings.cleanupEnabled) 1f else DISABLED_ALPHA, tween(150))

    PageScaffold(title = "Post Processing Provider", onBack = onBack) {
        Card(verticalPadding = 15.dp) {
            SwitchRow(
                title = "AI Cleanup",
                subtitle = "Polish the transcript with an LLM before it's inserted.",
                checked = settings.cleanupEnabled,
                onSave = { repository.setCleanupEnabled(it) },
                saver = saver,
                icon = R.drawable.lucide_sparkles
            )
        }
        Column(Modifier.alpha(dim), verticalArrangement = Arrangement.spacedBy(15.dp)) {
            Card(verticalPadding = 15.dp) {
                IconRow(R.drawable.lucide_cloud, iconTopPadding = 0.dp) {
                    SelectField(
                        label = "Post Processing Provider",
                        options = CLEANUP_PROVIDERS,
                        selected = provider,
                        optionLabel = ::cleanupProviderLabel,
                        onSave = { repository.setCleanupProvider(it) },
                        saver = saver,
                        spacing = 9.dp
                    )
                }
            }
            // Separate calls per provider, so unsaved input and menu state never carry over.
            if (settings.cleanupOpenRouter) {
                ProviderSections(
                    keyField = {
                        ApiKeyField(
                            isSet = settings.openRouterKeySet,
                            placeholder = "sk-or-…",
                            setHelper = apiKeyHelper(true),
                            unsetHelper = apiKeyHelper(false),
                            onSave = { repository.setApiKey(Secret.OPENROUTER_API_KEY, it) },
                            onClear = { repository.clearApiKey(Secret.OPENROUTER_API_KEY) },
                            saver = saver
                        )
                    },
                    modelField = {
                        ModelCombobox(
                            label = MODEL_LABEL,
                            stored = settings.openRouterCleanupModel,
                            models = catalogs.openRouterModelIds,
                            loading = catalogs.openRouterLoading,
                            loadError = catalogs.openRouterError,
                            helper = MODEL_HELPER,
                            onLoad = catalogs::loadOpenRouter,
                            onSave = { repository.setOpenRouterCleanupModel(it) },
                            saver = saver
                        )
                    },
                    reasoningField = {
                        ReasoningField(
                            value = settings.openRouterCleanupReasoningEffort,
                            model = settings.openRouterCleanupModel,
                            catalog = catalogs.openRouterModels,
                            onSave = { repository.setOpenRouterCleanupReasoningEffort(it) },
                            saver = saver
                        )
                    },
                    settings = settings,
                    repository = repository,
                    saver = saver,
                    optionsField = {
                        ProviderOptionsField(
                            stored = settings.openRouterCleanupProviderOptions,
                            hint = OPENROUTER_OPTIONS_HINT,
                            onSave = { repository.setOpenRouterCleanupProviderOptions(it) },
                            saver = saver
                        )
                    }
                )
            } else {
                ProviderSections(
                    keyField = {
                        ApiKeyField(
                            isSet = settings.gatewayKeySet,
                            placeholder = "Paste your API key",
                            setHelper = apiKeyHelper(true, "transcription"),
                            unsetHelper = apiKeyHelper(false, "transcription"),
                            onSave = { repository.setApiKey(Secret.GATEWAY_API_KEY, it) },
                            onClear = { repository.clearApiKey(Secret.GATEWAY_API_KEY) },
                            saver = saver
                        )
                    },
                    modelField = {
                        ModelCombobox(
                            label = MODEL_LABEL,
                            stored = settings.cleanupModel,
                            models = catalogs.chatModelIds,
                            loading = catalogs.gatewayLoading,
                            loadError = catalogs.gatewayError,
                            helper = MODEL_HELPER,
                            onLoad = catalogs::loadGateway,
                            onSave = { repository.setCleanupModel(it) },
                            saver = saver
                        )
                    },
                    reasoningField = {
                        ReasoningField(
                            value = settings.cleanupReasoningEffort,
                            model = settings.cleanupModel,
                            catalog = catalogs.gatewayModels,
                            onSave = { repository.setCleanupReasoningEffort(it) },
                            saver = saver
                        )
                    },
                    settings = settings,
                    repository = repository,
                    saver = saver,
                    optionsField = {
                        ProviderOptionsField(
                            stored = settings.cleanupProviderOptions,
                            hint = GATEWAY_OPTIONS_HINT,
                            onSave = { repository.setCleanupProviderOptions(it) },
                            saver = saver
                        )
                    }
                )
            }
            Section("Prompts") { PromptsCard(settings, repository, saver) }
        }
    }
}

/** "Provider Settings" (the key) and "Model Settings" (model, reasoning, timeout, provider options). */
@Composable
private fun ProviderSections(
    keyField: @Composable () -> Unit,
    modelField: @Composable () -> Unit,
    reasoningField: @Composable () -> Unit,
    settings: Settings,
    repository: SettingsRepository,
    saver: Saver,
    optionsField: @Composable () -> Unit,
) {
    Section("Provider Settings") { keyField() }
    Section("Model Settings") {
        modelField()
        Separator()
        reasoningField()
        Separator()
        SliderGroup(
            label = "Timeout",
            subtitle = "If cleanup takes longer, the original text is inserted.",
            value = settings.cleanupTimeoutSeconds,
            range = Settings.CLEANUP_TIMEOUT_RANGE,
            step = 1,
            valueText = ::secondsText,
            onSave = { repository.setCleanupTimeoutSeconds(it) },
            saver = saver
        )
        Separator()
        optionsField()
    }
}

/**
 * Reasoning level: "Model default" plus the saved [model]'s efforts from [catalog], or every level the APIs accept
 * when that's unknown. A saved value the model's known list lacks is flagged (and still sent).
 */
@Composable
private fun ReasoningField(
    value: String?,
    model: String,
    catalog: List<GatewayModel>?,
    onSave: suspend (String?) -> Unit,
    saver: Saver,
) {
    val efforts = catalog?.firstOrNull { it.id == model }?.reasoningEfforts
    SelectField(
        label = "Reasoning Level",
        options = listOf(null) + (efforts ?: Settings.REASONING_EFFORTS),
        selected = value,
        optionLabel = ::reasoningEffortLabel,
        onSave = onSave,
        saver = saver,
        helper = "Model default lets the provider decide.",
        error = if (value != null && efforts != null && value !in efforts) "Not listed for this model" else null
    )
}

/**
 * The "Provider Options" accordion (collapsed by default): a JSON text area that autosaves; blank clears, invalid
 * JSON shows an error and isn't saved.
 */
@Composable
private fun ProviderOptionsField(stored: String, hint: String, onSave: suspend (String) -> Unit, saver: Saver) {
    val colors = IrisTheme.colors
    val type = IrisTheme.type
    val focusManager = LocalFocusManager.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, tween(200))
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The press area reaches past the content by the inner padding, so the text stays aligned with
                // the card's other items and never touches the rounded clip.
                .layout { measurable, constraints ->
                    val x = HEADER_BLEED_X.roundToPx()
                    val y = HEADER_BLEED_Y.roundToPx()
                    val placeable = measurable.measure(constraints.offset(horizontal = 2 * x))
                    layout(placeable.width - 2 * x, placeable.height - 2 * y) { placeable.place(-x, -y) }
                }
                .clip(ControlShape)
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse" else "Expand") {
                    focusManager.clearFocus()
                    expanded = !expanded
                }
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .padding(horizontal = HEADER_BLEED_X, vertical = HEADER_BLEED_Y),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Provider Options", type.title)
                Text(
                    if (stored.isBlank()) "No provider option" else "Provider option set",
                    type.helper,
                    color = colors.muted
                )
            }
            Icon(R.drawable.lucide_chevron_down, null, colors.text, Modifier.rotate(chevron))
        }
        AnimatedVisibility(
            expanded,
            enter = expandVertically(tween(200)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(200)) + fadeOut(tween(150))
        ) {
            AutosaveTextField(
                label = "JSON",
                stored = stored,
                onSave = onSave,
                saver = saver,
                modifier = Modifier.padding(top = 15.dp),
                placeholder = "{ }",
                helper = hint,
                validate = ::providerOptionsError,
                multiLine = true,
                mono = true
            )
        }
    }
}

/**
 * The prompts card: the active prompt (also the one being edited) with "New prompt" and delete (not the last one),
 * then its name and text, which autosave.
 */
@Composable
private fun PromptsCard(settings: Settings, repository: SettingsRepository, saver: Saver) {
    val colors = IrisTheme.colors
    val focusManager = LocalFocusManager.current
    val active = settings.activePrompt ?: return
    val pickState = rememberSaveState(saver)
    val nameFocus = remember { FocusRequester() }
    // Set by "New prompt": once the new prompt shows, its name field gets focus with the text selected.
    var focusNewId by remember { mutableStateOf<String?>(null) }

    FieldGroup("Active Prompt", helper = "Cleanup uses this prompt.", error = pickState.error) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SelectDropdown(
                label = "Active Prompt",
                options = settings.prompts,
                selected = active,
                optionLabel = { it.name },
                onSelect = { pickState.save { repository.setActivePrompt(it.id) } },
                modifier = Modifier.weight(1f)
            )
            IconAction(
                R.drawable.lucide_plus,
                contentDescription = "New prompt",
                tint = colors.text,
                onClick = {
                    val prompt = CleanupPrompt(
                        UUID.randomUUID().toString(),
                        newPromptName(settings.prompts.map { it.name }),
                        Settings.DEFAULT_PROMPT.systemPrompt
                    )
                    pickState.save {
                        repository.savePrompt(prompt)
                        repository.setActivePrompt(prompt.id)
                    }
                    focusNewId = prompt.id
                },
                modifier = Modifier.border(1.dp, colors.fieldBorder, ControlShape)
            )
            IconAction(
                R.drawable.lucide_trash,
                contentDescription = "Delete prompt ${active.name}",
                tint = colors.failure,
                // Clears focus first (default), so a pending edit commits before the delete.
                onClick = { pickState.save { repository.deletePrompt(active.id) } },
                modifier = Modifier.border(1.dp, colors.fieldBorder, ControlShape),
                enabled = settings.prompts.size > 1
            )
        }
    }
    key(active.id) {
        val latest by rememberUpdatedState(active)
        Separator()
        AutosaveTextField(
            label = "Name",
            stored = active.name,
            onSave = { repository.savePrompt(latest.copy(name = it)) },
            saver = saver,
            placeholder = "Prompt name",
            validate = { if (it.isBlank()) "Name can't be empty." else null },
            clearable = false,
            focusRequester = nameFocus,
            selectAll = focusNewId == active.id
        )
        Separator()
        AutosaveTextField(
            label = "Prompt",
            stored = active.systemPrompt,
            onSave = { repository.savePrompt(latest.copy(systemPrompt = it)) },
            saver = saver,
            subtitle = "Instructions the model gets; your transcript is sent with them.",
            placeholder = "Instructions for the model",
            validate = { if (it.isBlank()) "Prompt can't be empty." else null },
            clearable = false,
            multiLine = true
        )
        LaunchedEffect(Unit) {
            if (focusNewId == active.id) {
                focusNewId = null
                nameFocus.requestFocus()
            }
        }
    }
}

private const val MODEL_LABEL = "Cleanup Model"
private const val MODEL_HELPER = "Model must support structured output."

/** How far the Provider Options header's press area reaches past its text on each side. */
private val HEADER_BLEED_X = 12.dp
private val HEADER_BLEED_Y = 10.dp
