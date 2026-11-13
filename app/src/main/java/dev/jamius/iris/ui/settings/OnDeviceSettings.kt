/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import dev.jamius.iris.data.settings.Settings
import dev.jamius.iris.data.settings.SettingsRepository
import dev.jamius.iris.stt.ondevice.ON_DEVICE_MODELS
import dev.jamius.iris.stt.ondevice.OnDeviceModelStore
import dev.jamius.iris.stt.ondevice.onDeviceModel
import dev.jamius.iris.ui.components.DangerButton
import dev.jamius.iris.ui.components.FieldGroup
import dev.jamius.iris.ui.components.ProgressBar
import dev.jamius.iris.ui.components.Saver
import dev.jamius.iris.ui.components.SelectField
import dev.jamius.iris.ui.components.Separator
import dev.jamius.iris.ui.components.SmallButton
import dev.jamius.iris.ui.components.Text
import dev.jamius.iris.ui.theme.IrisTheme

/**
 * On device's model settings: the model menu (sizes, or "Downloaded"), its download (progress + Cancel, or Delete)
 * and its licence link.
 */
@Composable
fun OnDeviceModelSettings(
    settings: Settings,
    repository: SettingsRepository,
    saver: Saver,
    store: OnDeviceModelStore,
    state: OnDeviceModelStore.State,
) {
    val colors = IrisTheme.colors
    val type = IrisTheme.type
    val model = onDeviceModel(settings.onDeviceSttModel)
    SelectField(
        label = "Speech-to-text Model",
        options = ON_DEVICE_MODELS.map { it.id },
        selected = model.id,
        optionLabel = { onDeviceModelLabel(onDeviceModel(it), it in state.downloaded) },
        onSave = { repository.setOnDeviceSttModel(it) },
        saver = saver,
        helper = "${model.languages}. Runs offline on this phone."
    )
    Separator()
    val downloaded = model.id in state.downloaded
    val otherDownloading = state.downloading != null && state.downloading != model.id
    FieldGroup(
        "Download",
        error = state.error?.takeIf { it.modelId == model.id }?.message,
        helper = if (otherDownloading && !downloaded) "Another model is downloading." else null
    ) {
        when {
            state.downloading == model.id -> {
                val fraction = if (state.totalBytes > 0) state.doneBytes.toFloat() / state.totalBytes else 0f
                ProgressBar(fraction)
                StatusLine {
                    Text(downloadProgressText(state.doneBytes, state.totalBytes), type.subtitle, color = colors.muted)
                    SmallButton("Cancel", onClick = store::cancel)
                }
            }

            state.deleting == model.id -> Text("Deleting…", type.subtitle, color = colors.muted)

            downloaded -> StatusLine {
                Text("Downloaded", type.subtitle, color = colors.enabled)
                DangerButton("Delete", onClick = { store.delete(model.id) })
            }

            !otherDownloading -> SmallButton(
                "Download (${megabytesText(model.sizeBytes)})",
                onClick = { store.download(model.id) }
            )
        }
    }
    val link = buildAnnotatedString {
        withLink(
            LinkAnnotation.Url(
                model.licenceUrl,
                TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))
            )
        ) {
            append(model.licence)
        }
    }
    BasicText(link, style = type.helper.copy(color = colors.muted))
}

/** A status text on the left, its action on the right. */
@Composable
private fun StatusLine(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}
