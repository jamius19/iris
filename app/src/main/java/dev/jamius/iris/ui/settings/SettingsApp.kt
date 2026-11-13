/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.settings

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.jamius.iris.cleanup.GatewayModel
import dev.jamius.iris.data.settings.CustomCleanupProvider
import dev.jamius.iris.data.settings.SettingsRepository
import dev.jamius.iris.stt.soniox.SonioxCatalog
import dev.jamius.iris.ui.components.Saver
import dev.jamius.iris.ui.theme.IrisTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

enum class Page { Home, Transcription, PostProcessing, Appearance }

/**
 * The settings UI: Home and its three sub-pages (own navigation state, slide transitions, predictive back),
 * the model catalogues and their load triggers. Leaving a page, the app (ON_STOP) or tapping empty space clears focus,
 * which commits a focused text field.
 */
@Composable
fun SettingsApp(
    repository: SettingsRepository,
    saver: Saver,
    gatewayModels: suspend () -> List<GatewayModel>,
    openRouterModels: suspend () -> List<GatewayModel>,
    openAiModels: suspend () -> List<String>,
    customModels: suspend (CustomCleanupProvider) -> List<GatewayModel>,
    sonioxCatalog: SonioxCatalog,
    favicons: Favicons,
) {
    val settings = repository.settings.collectAsState(initial = null).value
    // App-level (sessions use it too); it reloads itself when the Soniox key or region changes.
    val sonioxState by sonioxCatalog.state.collectAsState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val catalogs = remember { ModelCatalogs(scope, gatewayModels, openRouterModels, openAiModels, customModels) }

    // Gateway catalog: when the app opens with Gateway cleanup or Gateway transcription on, and when either is turned
    // on before it has loaded.
    val cleanupEnabled = settings?.cleanupEnabled == true
    val custom = settings?.activeCustomCleanup
    val cleanupGateway = cleanupEnabled && settings?.cleanupOpenRouter != true && custom == null
    val sttGateway = settings?.sttGateway == true
    LaunchedEffect(cleanupGateway, sttGateway) {
        if ((cleanupGateway || sttGateway) && catalogs.gatewayModels == null) catalogs.loadGateway()
    }
    // OpenRouter catalog (public, no key): when OpenRouter cleanup is on.
    val cleanupOpenRouter = cleanupEnabled && settings?.cleanupOpenRouter == true
    LaunchedEffect(cleanupOpenRouter) {
        if (cleanupOpenRouter && catalogs.openRouterModels == null) catalogs.loadOpenRouter()
    }
    // The selected custom provider's list (with its key): when it is selected with cleanup on.
    LaunchedEffect(cleanupEnabled, custom?.id) {
        if (cleanupEnabled && custom != null && catalogs.customModelsFor(custom.id) == null) catalogs.loadCustom(custom)
    }
    // OpenAI list: when OpenAI transcription is selected. Without a key nothing is requested and the error asks for
    // one.
    val sttOpenAi = settings != null && !settings.sttGateway && !settings.sttDeepgram && !settings.sttSoniox
    LaunchedEffect(sttOpenAi) {
        if (sttOpenAi && catalogs.openAiModels == null) catalogs.loadOpenAi()
    }
    // Soniox: when it is selected, if not loaded (or loading) yet.
    val sttSoniox = settings?.sttSoniox == true
    LaunchedEffect(sttSoniox) {
        if (sttSoniox && sonioxState.models == null && !sonioxState.loading) sonioxCatalog.refresh()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { focusManager.clearFocus() }

    var page by rememberSaveable { mutableStateOf(Page.Home) }
    val pageState = remember { SeekableTransitionState(page) }
    LaunchedEffect(page) {
        // Composing the new page takes a frame or more; seeking to 0 first starts the clock after it, else animateTo
        // counts that time and skips the start. Not after a back gesture, which already seeked towards Home, nor while
        // a transition runs: seekTo would wait for it to finish, then play the reverse.
        if (pageState.currentState == pageState.targetState && pageState.targetState != page) {
            pageState.seekTo(0f, targetState = page)
        }
        pageState.animateTo(page)
    }
    PredictiveBackHandler(enabled = page != Page.Home) { events ->
        focusManager.clearFocus()
        try {
            events.collect { pageState.seekTo(it.progress, targetState = Page.Home) }
            page = Page.Home
        } catch (e: CancellationException) {
            // Gesture cancelled: settle back on the sub-page.
            scope.launch { pageState.animateTo(page) }
            throw e
        }
    }

    val stateHolder = rememberSaveableStateHolder()
    val transition = rememberTransition(pageState, label = "page")
    // Sub-pages open fresh (top, no drafts) after a return Home; Home keeps its scroll.
    LaunchedEffect(transition.currentState, transition.targetState) {
        if (transition.currentState == Page.Home && transition.targetState == Page.Home) {
            Page.entries.filter { it != Page.Home }.forEach { stateHolder.removeState(it.name) }
        }
    }
    if (settings == null) return
    val back = { page = Page.Home }
    Box(Modifier.fillMaxSize()) {
        transition.AnimatedContent(
            transitionSpec = {
                // One spec for both pages, no fades: nothing disappears before the slide ends.
                val slide = tween<IntOffset>(PAGE_MS, easing = FastOutSlowInEasing)
                if (targetState != Page.Home) {
                    // The sub-page slides in over Home, which shifts a quarter left underneath.
                    slideInHorizontally(slide) { it } togetherWith slideOutHorizontally(slide) { -it / 4 }
                } else {
                    // The sub-page slides off on top (Home's zIndex -1); Home comes back underneath.
                    (slideInHorizontally(slide) { -it / 4 } togetherWith slideOutHorizontally(slide) { it })
                        .apply { targetContentZIndex = -1f }
                }.using(SizeTransform(clip = false))
            }
        ) { current ->
            stateHolder.SaveableStateProvider(current.name) {
                when (current) {
                    Page.Home -> HomePage(onOpen = { page = it })
                    Page.Transcription -> TranscriptionPage(
                        settings,
                        repository,
                        saver,
                        catalogs,
                        sonioxState,
                        sonioxCatalog,
                        onBack = back
                    )
                    Page.PostProcessing -> PostProcessingPage(
                        settings,
                        repository,
                        saver,
                        catalogs,
                        favicons,
                        onBack = back
                    )
                    Page.Appearance -> AppearancePage(settings, repository, saver, onBack = back)
                }
            }
        }
        // 3-button nav only (tappableElement is 0 with gesture nav): a page-coloured scrim behind the buttons.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsBottomHeight(WindowInsets.tappableElement)
                .background(IrisTheme.colors.background.copy(alpha = 0.9f))
        )
    }
}

private const val PAGE_MS = 300
