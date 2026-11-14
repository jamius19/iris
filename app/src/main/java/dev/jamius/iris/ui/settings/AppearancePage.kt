/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.settings

import androidx.compose.runtime.Composable
import dev.jamius.iris.data.settings.Settings
import dev.jamius.iris.data.settings.SettingsRepository
import dev.jamius.iris.ui.components.PageScaffold
import dev.jamius.iris.ui.components.Saver
import dev.jamius.iris.ui.components.Section
import dev.jamius.iris.ui.components.Separator
import dev.jamius.iris.ui.components.SliderGroup

/** Appearance: the live-text panel's size (applied live by the overlay). */
@Composable
fun AppearancePage(settings: Settings, repository: SettingsRepository, saver: Saver, onBack: () -> Unit) {
    PageScaffold(title = "Appearance", onBack = onBack) {
        Section("Text Panel") {
            SliderGroup(
                label = "Lines",
                subtitle = "Lines of live text the panel shows.",
                value = settings.panelLines,
                range = Settings.PANEL_LINES_RANGE,
                step = 1,
                valueText = { "$it" },
                onSave = { repository.setPanelLines(it) },
                saver = saver,
                helper = "Notices show at least 4 lines."
            )
            Separator()
            SliderGroup(
                label = "Max Width",
                subtitle = "Share of the screen width the panel can use.",
                value = settings.panelWidthPercent,
                range = Settings.PANEL_WIDTH_PERCENT_RANGE,
                step = 10,
                valueText = ::percentText,
                onSave = { repository.setPanelWidthPercent(it) },
                saver = saver
            )
        }
    }
}
