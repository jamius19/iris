/*
* Author: Jamius Siam
* Since: 14/11/2026
*/

package dev.jamius.iris.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jamius.iris.stt.ondevice.ModelStats
import dev.jamius.iris.stt.ondevice.SpeedVerdict
import dev.jamius.iris.stt.ondevice.medianWaitMs
import dev.jamius.iris.stt.ondevice.modelsWithStats
import dev.jamius.iris.stt.ondevice.speedFactor
import dev.jamius.iris.stt.ondevice.speedVerdict
import dev.jamius.iris.ui.components.Card
import dev.jamius.iris.ui.components.DangerButton
import dev.jamius.iris.ui.components.PageScaffold
import dev.jamius.iris.ui.components.Section
import dev.jamius.iris.ui.components.Separator
import dev.jamius.iris.ui.components.Text
import dev.jamius.iris.ui.theme.IrisTheme

/**
 * Statistics: per on-device model, its speed against real time (with a verdict), the median transcription time and
 * the last load time, from its last 20 dictations on this phone; Reset clears them all.
 */
@Composable
fun StatisticsPage(stats: Map<String, ModelStats>?, onReset: () -> Unit, onBack: () -> Unit) {
    val colors = IrisTheme.colors
    val type = IrisTheme.type
    PageScaffold(title = "Statistics", onBack = onBack) {
        // Null until the first read: no empty state flashes in.
        if (stats == null) return@PageScaffold
        val models = modelsWithStats(stats)
        if (models.isEmpty()) {
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("No statistics yet", type.title)
                    Text(
                        "Dictate with an on-device model to see how fast it runs on this phone.",
                        type.subtitle,
                        color = colors.muted
                    )
                }
            }
            return@PageScaffold
        }
        for ((model, s) in models) {
            Section(model.label) {
                val factor = checkNotNull(s.speedFactor())
                val verdict = speedVerdict(factor)
                val verdictColor = when (verdict) {
                    SpeedVerdict.KEEPS_UP -> colors.enabled
                    SpeedVerdict.BORDERLINE -> colors.warning
                    SpeedVerdict.FALLS_BEHIND -> colors.failure
                }
                StatRow("Speed", verdictLabel(verdict), verdictColor, speedFactorText(factor))
                Separator()
                StatRow(
                    "Transcription Time",
                    tenthsText(checkNotNull(s.medianWaitMs())),
                    colors.text,
                    "Median time from stopping to the final text."
                )
                s.lastLoadMs?.let {
                    Separator()
                    StatRow("Model Load", tenthsText(it), colors.text, "Last time the model was loaded into memory.")
                }
                Text(statsBasisText(s.samples.size), type.helper, color = colors.muted)
            }
        }
        Text(SPEED_HELPER, type.helper, color = colors.muted)
        DangerButton("Reset Statistics", onClick = onReset)
    }
}

/** A title with its value on the right, a subtitle 3 dp below; read as one item. */
@Composable
private fun StatRow(title: String, value: String, valueColor: Color, subtitle: String) {
    val type = IrisTheme.type
    Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row {
            Text(title, type.title, Modifier.weight(1f))
            Text(value, type.title, color = valueColor)
        }
        Text(subtitle, type.subtitle, color = IrisTheme.colors.muted)
    }
}

private const val SPEED_HELPER = "Speed compares how long you spoke with how long the model took to transcribe it. " +
    "At 1.5× or more it keeps up; below 1× the text falls behind you."
