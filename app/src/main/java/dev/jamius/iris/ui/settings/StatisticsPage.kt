/*
* Author: Jamius Siam
* Since: 14/11/2026
*/

package dev.jamius.iris.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jamius.iris.dictation.StatsSnapshot
import dev.jamius.iris.dictation.Totals
import dev.jamius.iris.dictation.percentileMs
import dev.jamius.iris.stt.ondevice.SpeedVerdict
import dev.jamius.iris.stt.ondevice.median
import dev.jamius.iris.stt.ondevice.medianWaitMs
import dev.jamius.iris.stt.ondevice.modelsWithStats
import dev.jamius.iris.stt.ondevice.speedFactor
import dev.jamius.iris.stt.ondevice.speedVerdict
import dev.jamius.iris.ui.components.Card
import dev.jamius.iris.ui.components.DangerButton
import dev.jamius.iris.ui.components.PageScaffold
import dev.jamius.iris.ui.components.Section
import dev.jamius.iris.ui.components.SectionTopPadding
import dev.jamius.iris.ui.components.Separator
import dev.jamius.iris.ui.components.Text
import dev.jamius.iris.ui.theme.IrisTheme

/**
 * Statistics: the all-time summary card; per on-device model, its speed against real time (with a verdict), the
 * median transcription time and the last load time; per cloud provider + model, the median transcription time and the
 * audio sent; per cleanup provider + model, its p75 / p90 time; each from its last 20 samples. Reset clears them all.
 */
@Composable
fun StatisticsPage(stats: StatsSnapshot?, onReset: () -> Unit, onBack: () -> Unit) {
    val colors = IrisTheme.colors
    val type = IrisTheme.type
    PageScaffold(title = "Statistics", onBack = onBack) {
        // Null until the first read: no empty state flashes in.
        if (stats == null) return@PageScaffold
        val models = modelsWithStats(stats.onDevice)
        val cloud = stats.cloud.values.filter { it.samples.isNotEmpty() }.sortedBy { it.title }
        val cleanup = stats.cleanup.values.filter { it.samplesMs.isNotEmpty() }.sortedBy { it.title }
        val hasTotals = stats.totals.recordedMs > 0
        if (models.isEmpty() && cloud.isEmpty() && cleanup.isEmpty() && !hasTotals) {
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("No statistics yet", type.title)
                    Text(
                        "Dictate to see transcription and post-processing times, audio sent and how on-device models " +
                            "run on this phone.",
                        type.subtitle,
                        color = colors.muted
                    )
                }
            }
            return@PageScaffold
        }
        if (hasTotals) {
            SummaryCard(stats.totals)
            Text(TOTALS_HELPER, type.helper, color = colors.muted)
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
                    "Typical time the transcription took."
                )
                s.lastLoadMs?.let {
                    Separator()
                    StatRow("Model Load", tenthsText(it), colors.text, "Last time the model was loaded into memory.")
                }
                Text(statsBasisText(s.samples.size), type.helper, color = colors.muted)
            }
        }
        for (c in cloud) {
            Section(c.title) {
                val recordedMs = c.samples.sumOf { it.recordedMs }
                val billedMs = c.samples.sumOf { it.billedMs }
                StatRow(
                    "Transcription Time",
                    tenthsText(checkNotNull(median(c.samples.map { it.waitMs }))),
                    colors.text,
                    "Typical time the transcription took."
                )
                Separator()
                StatRow(
                    "Audio Sent",
                    minutesText(billedMs),
                    colors.text,
                    audioSavedText(savedPercent(recordedMs, billedMs))
                )
                Text(statsBasisText(c.samples.size), type.helper, color = colors.muted)
            }
        }
        for (c in cleanup) {
            Section(c.title) {
                StatRow(
                    "Post Processing Time (p75)",
                    tenthsText(checkNotNull(percentileMs(c.samplesMs, 75))),
                    colors.text,
                    "3 in 4 requests finished within this time."
                )
                Separator()
                StatRow(
                    "Post Processing Time (p90)",
                    tenthsText(checkNotNull(percentileMs(c.samplesMs, 90))),
                    colors.text,
                    "9 in 10 requests finished within this time."
                )
                Text(statsBasisText(c.samplesMs.size, "post processing request"), type.helper, color = colors.muted)
            }
        }
        // Spaced from the last section like a section, so they don't read as part of its card.
        if (models.isNotEmpty() || cloud.isNotEmpty() || cleanup.isNotEmpty()) {
            Column(Modifier.padding(top = SectionTopPadding), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                if (models.isNotEmpty()) Text(SPEED_HELPER, type.helper, color = colors.muted)
                if (cloud.isNotEmpty()) Text(CLOUD_HELPER, type.helper, color = colors.muted)
                if (cleanup.isNotEmpty()) Text(CLEANUP_HELPER, type.helper, color = colors.muted)
            }
        }
        DangerButton("Reset Statistics", onClick = onReset)
    }
}

/** The all-time totals on the brand blue (both modes), white text; each figure is read as one item. */
@Composable
private fun SummaryCard(totals: Totals) {
    Card(color = IrisTheme.colors.statsCard) {
        Row {
            SummaryFigure(durationText(totals.recordedMs), "Time Transcribed", Modifier.weight(1f))
            // Net per dictation, so the sum may be negative: shown as "0 s".
            SummaryFigure(durationText(totals.savedMs), "Time Saved", Modifier.weight(1f))
        }
    }
}

@Composable
private fun SummaryFigure(value: String, label: String, modifier: Modifier) {
    val type = IrisTheme.type
    Column(
        modifier.semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(value, type.pageTitle, color = Color.White)
        Text(label, type.subtitle, color = Color.White)
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

private const val TOTALS_HELPER = "Time saved compared to typing on a keyboard."

private const val CLOUD_HELPER = "Audio Sent is roughly what the service bills, after skipping silences and " +
    "applying audio speed. Soniox real-time bills the whole stream, so nothing is saved there."

private const val CLEANUP_HELPER = "Only post processing requests that finished count. Failed and timed-out ones " +
    "are left out."

private const val SPEED_HELPER = "Speed compares how long you spoke with how long the model took to transcribe it. " +
    "At 1.5× or more it keeps up. Below 1× the transcription falls behind."
