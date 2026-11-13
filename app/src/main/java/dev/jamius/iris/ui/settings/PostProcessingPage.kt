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
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
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
import androidx.compose.ui.layout.ContentScale
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
import dev.jamius.iris.data.settings.CustomCleanupProvider
import dev.jamius.iris.data.settings.Secret
import dev.jamius.iris.data.settings.Settings
import dev.jamius.iris.data.settings.SettingsRepository
import dev.jamius.iris.ui.components.ApiKeyField
import dev.jamius.iris.ui.components.AutosaveTextField
import dev.jamius.iris.ui.components.BrandIcon
import dev.jamius.iris.ui.components.Card
import dev.jamius.iris.ui.components.ControlHeight
import dev.jamius.iris.ui.components.ControlShape
import dev.jamius.iris.ui.components.DISABLED_ALPHA
import dev.jamius.iris.ui.components.DangerButton
import dev.jamius.iris.ui.components.DraftApiKeyField
import dev.jamius.iris.ui.components.DraftTextField
import dev.jamius.iris.ui.components.FieldGroup
import dev.jamius.iris.ui.components.Icon
import dev.jamius.iris.ui.components.IconAction
import dev.jamius.iris.ui.components.IconRow
import dev.jamius.iris.ui.components.ModelCombobox
import dev.jamius.iris.ui.components.PageScaffold
import dev.jamius.iris.ui.components.ProviderIconBox
import dev.jamius.iris.ui.components.Saver
import dev.jamius.iris.ui.components.Section
import dev.jamius.iris.ui.components.SelectDropdown
import dev.jamius.iris.ui.components.SelectField
import dev.jamius.iris.ui.components.Separator
import dev.jamius.iris.ui.components.SliderGroup
import dev.jamius.iris.ui.components.SmallButton
import dev.jamius.iris.ui.components.SwitchRow
import dev.jamius.iris.ui.components.Text
import dev.jamius.iris.ui.components.rememberSaveState
import dev.jamius.iris.ui.theme.IrisTheme
import java.util.UUID

/**
 * Post Processing Provider: the AI Cleanup switch, then (dimmed while it's off, still editable) the provider (plus a
 * new custom one), its key, model, reasoning level, timeout and provider options, and the prompts. A new custom
 * provider is an in-memory draft until saved (never used for cleanup); picking another provider or leaving the page
 * discards it.
 */
@Composable
fun PostProcessingPage(
    settings: Settings,
    repository: SettingsRepository,
    saver: Saver,
    catalogs: ModelCatalogs,
    favicons: Favicons,
    onBack: () -> Unit,
) {
    val colors = IrisTheme.colors
    // Not disabled, so cleanup can be configured before it is turned on; dimmed to show it is inactive.
    val dim by animateFloatAsState(if (settings.cleanupEnabled) 1f else DISABLED_ALPHA, tween(150))
    val pickState = rememberSaveState(saver)
    var newDraft by remember { mutableStateOf<CustomProviderForm?>(null) }
    val savedIds = settings.customCleanupProviders.map { it.id }
    // Once saved, the draft is the saved provider (dropped here, not on the save result, which can come before the
    // new settings: the previous provider would flash).
    val draft = newDraft?.takeIf { it.id !in savedIds }
    LaunchedEffect(draft) { if (draft == null) newDraft = null }
    val selectedId = draft?.id ?: settings.cleanupProviderId
    val custom = settings.activeCustomCleanup

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
                    FieldGroup("Post Processing Provider", error = pickState.error, spacing = 9.dp) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SelectDropdown(
                                label = "Post Processing Provider",
                                options = CLEANUP_PROVIDERS + savedIds + listOfNotNull(draft?.id),
                                selected = selectedId,
                                optionLabel = { cleanupProviderLabel(it, settings.customCleanupProviders) },
                                onSelect = {
                                    newDraft = null
                                    if (it != settings.cleanupProviderId) {
                                        pickState.save { repository.setCleanupProvider(it) }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                optionIcon = { ProviderIconBox { ProviderIcon(it, settings, favicons) } }
                            )
                            IconAction(
                                R.drawable.lucide_plus,
                                contentDescription = "New provider",
                                tint = colors.text,
                                onClick = {
                                    if (draft == null) {
                                        newDraft = CustomProviderForm(UUID.randomUUID().toString(), isNew = true)
                                    } else {
                                        draft.nameFocus.requestFocus()
                                    }
                                },
                                modifier = Modifier.border(1.dp, colors.fieldBorder, ControlShape),
                                width = ControlHeight
                            )
                        }
                    }
                }
            }
            // Separate calls per provider, so unsaved input and menu state never carry over.
            if (draft != null) {
                key(draft.id) {
                    Section("Provider Settings") {
                        CustomProviderSettings(draft, keyStored = false, repository, saver, catalogs)
                    }
                    LaunchedEffect(Unit) { draft.nameFocus.requestFocus() }
                }
            } else if (custom != null) {
                key(custom.id) {
                    val form = remember { CustomProviderForm(custom.id, isNew = false, custom.name, custom.baseUrl) }
                    val ownCatalog = catalogs.customId == custom.id
                    ProviderSections(
                        providerSettings = {
                            CustomProviderSettings(
                                form,
                                keyStored = custom.id in settings.customCleanupKeyIds,
                                repository,
                                saver,
                                catalogs,
                                storedBaseUrl = custom.baseUrl
                            )
                        },
                        modelField = {
                            ModelCombobox(
                                label = MODEL_LABEL,
                                stored = custom.model,
                                models = catalogs.customModelsFor(custom.id)?.map { it.id },
                                loading = ownCatalog && catalogs.customLoading,
                                loadError = catalogs.customError.takeIf { ownCatalog },
                                helper = "Must support structured output while Send JSON Schema is on.",
                                onLoad = { catalogs.loadCustom(custom) },
                                onSave = { model ->
                                    repository.updateCustomCleanupProvider(custom.id) { it.copy(model = model) }
                                },
                                saver = saver
                            )
                        },
                        reasoningField = {
                            ReasoningField(
                                value = custom.reasoningEffort,
                                model = custom.model,
                                catalog = catalogs.customModelsFor(custom.id),
                                onSave = { effort ->
                                    repository.updateCustomCleanupProvider(custom.id) {
                                        it.copy(reasoningEffort = effort)
                                    }
                                },
                                saver = saver
                            )
                        },
                        settings = settings,
                        repository = repository,
                        saver = saver,
                        extraField = {
                            SwitchRow(
                                title = "Send JSON Schema",
                                subtitle = "Requests structured output (strict JSON schema).",
                                checked = custom.sendJsonSchema,
                                onSave = { on ->
                                    repository.updateCustomCleanupProvider(custom.id) { it.copy(sendJsonSchema = on) }
                                },
                                saver = saver
                            )
                        }
                    )
                }
            } else if (settings.cleanupOpenRouter) {
                ProviderSections(
                    providerSettings = {
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
                    extraField = {
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
                    providerSettings = {
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
                    extraField = {
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

/**
 * A custom provider's Provider Settings form (type, name, base URL, key): a draft for saved providers too, committed
 * by Save; the key trash only marks a stored key for removal (Save then needs a new key).
 */
private class CustomProviderForm(val id: String, val isNew: Boolean, name: String = "", baseUrl: String = "") {
    val name = TextFieldState(name)
    val baseUrl = TextFieldState(baseUrl)
    val key = TextFieldState()
    val nameFocus = FocusRequester()
    var type by mutableStateOf(CustomCleanupProvider.TYPE_OPENAI_COMPATIBLE)
    var removeStoredKey by mutableStateOf(false)
    var errors by mutableStateOf(ProviderDraftErrors(null, null, null))
}

/**
 * The custom provider form's fields, then Save (checks, saves and selects the provider) and, for a saved provider,
 * Delete Provider (with its key, no confirmation; the Gateway is selected). [keyStored]: the provider has a key.
 * [storedBaseUrl]: the saved provider's base URL (null for a new one); Save needs a new key if the host changes.
 */
@Composable
private fun CustomProviderSettings(
    form: CustomProviderForm,
    keyStored: Boolean,
    repository: SettingsRepository,
    saver: Saver,
    catalogs: ModelCatalogs,
    storedBaseUrl: String? = null,
) {
    val colors = IrisTheme.colors
    val saveState = rememberSaveState(saver)
    val keyShown = keyStored && !form.removeStoredKey

    fun save() {
        val name = form.name.text.toString().trim()
        val baseUrl = form.baseUrl.text.toString().trim()
        val keyInput = form.key.text.toString()
        // A new host needs a new key: the stored one must never be sent to another server.
        val keyUsable = keyShown && (storedBaseUrl == null || sameHost(baseUrl, storedBaseUrl))
        form.errors = providerDraftErrors(name, baseUrl, keyInput, keyUsable)
        if (!form.errors.ok) return
        saver.save(
            onResult = {
                saveState.error = it
                if (it == null) {
                    form.key.clearText()
                    form.removeStoredKey = false
                    catalogs.onCustomProviderSaved(
                        CustomCleanupProvider(id = form.id, name = name, baseUrl = baseUrl.trimEnd('/'))
                    )
                }
            }
        ) { repository.saveCustomCleanupProvider(form.id, name, baseUrl, keyInput.ifBlank { null }) }
    }

    FieldGroup("Provider Type") {
        SelectDropdown(
            label = "Provider Type",
            options = listOf(CustomCleanupProvider.TYPE_OPENAI_COMPATIBLE),
            selected = form.type,
            optionLabel = { "OpenAI Compatible" },
            onSelect = { form.type = it }
        )
    }
    Separator()
    DraftTextField(
        label = "Provider Name",
        state = form.name,
        placeholder = "My provider",
        error = form.errors.name,
        onEdited = { form.errors = form.errors.copy(name = null) },
        focusRequester = form.nameFocus
    )
    Separator()
    DraftTextField(
        label = "Provider Base URL",
        state = form.baseUrl,
        placeholder = "https://api.example.com/v1",
        error = form.errors.baseUrl,
        onEdited = { form.errors = form.errors.copy(baseUrl = null) },
        helper = "Enter your OpenAI-compatible base URL here."
    )
    Separator()
    DraftApiKeyField(
        state = form.key,
        storedShown = keyShown,
        onRemoveStored = { form.removeStoredKey = true },
        error = form.errors.apiKey,
        onEdited = { form.errors = form.errors.copy(apiKey = null) }
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("Save", onClick = ::save)
            if (!form.isNew) {
                DangerButton(
                    "Delete Provider",
                    onClick = { saveState.save { repository.deleteCustomCleanupProvider(form.id) } }
                )
            }
        }
        saveState.error?.let { Text(it, IrisTheme.type.helper, color = colors.failure) }
    }
}

/** A provider's icon in the provider menu: the brand marks, a custom provider's favicon, else the cloud. */
@Composable
private fun ProviderIcon(id: String, settings: Settings, favicons: Favicons) {
    val brand = when (id) {
        Settings.CLEANUP_PROVIDER_VERCEL_GATEWAY -> R.drawable.provider_vercel
        Settings.CLEANUP_PROVIDER_OPENROUTER -> R.drawable.provider_openrouter
        else -> null
    }
    if (brand != null) {
        BrandIcon(brand)
        return
    }
    val favicon = settings.customCleanupProviders.firstOrNull { it.id == id }
        ?.let { rememberFavicon(it.baseUrl, favicons) }
    if (favicon != null) {
        Image(favicon, null, Modifier.size(14.dp), contentScale = ContentScale.Fit)
    } else {
        Icon(R.drawable.lucide_cloud, null, IrisTheme.colors.onControl, Modifier.size(14.dp))
    }
}

/**
 * "Provider Settings" (the key, or a custom provider's form) and "Model Settings" (model, reasoning, timeout, and
 * [extraField] if any).
 */
@Composable
private fun ProviderSections(
    providerSettings: @Composable ColumnScope.() -> Unit,
    modelField: @Composable () -> Unit,
    reasoningField: @Composable () -> Unit,
    settings: Settings,
    repository: SettingsRepository,
    saver: Saver,
    extraField: (@Composable () -> Unit)? = null,
) {
    Section("Provider Settings", providerSettings)
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
        if (extraField != null) {
            Separator()
            extraField()
        }
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
                modifier = Modifier.border(1.dp, colors.fieldBorder, ControlShape),
                width = ControlHeight
            )
            IconAction(
                R.drawable.lucide_trash,
                contentDescription = "Delete prompt ${active.name}",
                tint = colors.failure,
                // Clears focus first (default), so a pending edit commits before the delete.
                onClick = { pickState.save { repository.deletePrompt(active.id) } },
                modifier = Modifier.border(1.dp, colors.fieldBorder, ControlShape),
                enabled = settings.prompts.size > 1,
                width = ControlHeight
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
            subtitle = "Instructions for the post-processing model.",
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
