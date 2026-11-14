/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import dev.jamius.iris.R
import dev.jamius.iris.ui.theme.IrisTheme

/** The update check's result, shown after the version in the footer ([UNKNOWN]: check failed, nothing shown). */
enum class UpdateStatus { CHECKING, UP_TO_DATE, AVAILABLE, UNKNOWN }

val LocalUpdateStatus = compositionLocalOf { UpdateStatus.UNKNOWN }

/**
 * A settings page: background, the logo (Home, [title] null) or a back chevron + title, [content] (items 15 dp
 * apart) and the version footer (with [LocalUpdateStatus]), pinned to the bottom while the content is short. Content
 * scrolls under the nav bar (its inset is bottom padding inside the scroll); the IME lifts the viewport by its full
 * height. Tapping empty space clears focus, which commits a focused text field.
 */
@Composable
fun PageScaffold(title: String?, onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    val colors = IrisTheme.colors
    val type = IrisTheme.type
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    val insets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(insets)
            .consumeWindowInsets(insets)
            .imePadding()
    ) {
        val viewport = maxHeight
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewport)
                .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
                .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = navBarBottom + 28.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
                if (title == null) {
                    Image(
                        painterResource(R.drawable.iris_logo),
                        contentDescription = "Iris",
                        modifier = Modifier
                            .padding(start = 7.dp, bottom = 7.dp) // 22 dp to the first card
                            .height(30.dp)
                    )
                } else {
                    Row(Modifier.heightIn(min = ControlHeight), verticalAlignment = Alignment.CenterVertically) {
                        if (onBack != null) {
                            IconAction(
                                R.drawable.lucide_chevron_left, "Back", colors.text, onBack,
                                iconModifier = Modifier.offset(y = 1.dp)   // positive = down
                            )
                            Spacer(Modifier.width(2.dp))
                        }
                        Text(title, type.pageTitle, Modifier.semantics { heading() })
                    }
                }
                content()
            }
            Spacer(Modifier.height(28.dp))
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Text("Version $version", type.footer, color = colors.muted)
                // A new status slides up over the old one while the width animates, so the centred footer glides to
                // its new place instead of jumping.
                AnimatedContent(
                    targetState = LocalUpdateStatus.current,
                    transitionSpec = {
                        (slideInVertically(tween(FOOTER_MS)) { it } + fadeIn(tween(FOOTER_MS))) togetherWith
                            (slideOutVertically(tween(FOOTER_MS)) { -it } + fadeOut(tween(FOOTER_MS)))
                    }
                ) { update ->
                    val updateText = when (update) {
                        UpdateStatus.CHECKING -> "Checking for update"
                        UpdateStatus.UP_TO_DATE -> "Up to date"
                        UpdateStatus.AVAILABLE -> "Update available"
                        UpdateStatus.UNKNOWN -> null
                    }
                    if (updateText != null) {
                        val status = buildAnnotatedString {
                            append(" · ")
                            if (update == UpdateStatus.AVAILABLE) {
                                // A link like Home's: opens the release page in the browser.
                                val link = SpanStyle(color = colors.failure, textDecoration = TextDecoration.Underline)
                                withLink(LinkAnnotation.Url(LATEST_RELEASE_PAGE, TextLinkStyles(link))) {
                                    append(updateText)
                                }
                            } else {
                                append(updateText)
                            }
                        }
                        Text(status, type.footer, color = colors.muted)
                    }
                }
            }
        }
    }
}

private const val FOOTER_MS = 200
private const val LATEST_RELEASE_PAGE = "https://github.com/jamius19/Iris/releases/latest"
