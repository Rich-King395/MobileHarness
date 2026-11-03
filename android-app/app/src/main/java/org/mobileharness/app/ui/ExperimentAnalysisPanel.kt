package org.mobileharness.app.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mobileharness.app.R
import org.mobileharness.app.storage.ExperimentAnalysis
import java.text.NumberFormat
import kotlin.math.roundToLong

/** Metadata-only descriptive statistics. Never resolves task text or model configuration names. */
@Composable
internal fun ExperimentAnalysisPanel(
    analyses: List<ExperimentAnalysis.Analysis>,
    selectedScope: Boolean,
) {
    val context = LocalContext.current
    val ended = remember(analyses) { analyses.filter { it.ended } }
    val comparison = remember(ended) { ExperimentAnalysis.aggregate(ended) }
    var showComparison by remember { mutableStateOf(false) }
    val groups = listOf(comparison.on, comparison.off, comparison.unknown)
    val sessions = groups.sumOf { it.sessions }
    val rated = groups.sumOf { it.rated }
    val completed = groups.sumOf { it.completed }
    val unrated = groups.sumOf { it.unrated }
    fun meanOf(sample: (ExperimentAnalysis.Group) -> ExperimentAnalysis.Mean): ExperimentAnalysis.Mean {
        val known = groups.map(sample).filter { it.value != null && it.samples > 0 }
        val samples = known.sumOf { it.samples }
        return ExperimentAnalysis.Mean(if (samples > 0) known.sumOf { it.value!! * it.samples } / samples else null, samples)
    }
    val steps = meanOf { it.meanGuiSteps }
    val duration = meanOf { it.meanDurationMs }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(context.getString(R.string.analysis_title), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Text(context.getString(if (selectedScope) R.string.analysis_scope_selected else R.string.analysis_scope_all),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (sessions == 0) {
                Text(context.getString(R.string.analysis_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricCell(context.getString(R.string.analysis_total_runs), sessions.toString(),
                        context.getString(R.string.analysis_rated_counts, rated, unrated), Modifier.weight(1f))
                    MetricCell(context.getString(R.string.analysis_user_completion),
                        analysisPercent(context, if (rated > 0) completed.toDouble() / rated else null),
                        context.getString(R.string.analysis_completion_denominator, completed, rated), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricCell(context.getString(R.string.analysis_mean_steps), analysisNumber(context, steps.value),
                        context.getString(R.string.analysis_samples, steps.samples), Modifier.weight(1f))
                    MetricCell(context.getString(R.string.analysis_mean_duration), analysisDuration(context, duration.value?.roundToLong()),
                        context.getString(R.string.analysis_samples, duration.samples), Modifier.weight(1f))
                }
                TextButton(onClick = { showComparison = !showComparison }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(context.getString(if (showComparison) R.string.analysis_hide_comparison else R.string.analysis_compare))
                }
                if (showComparison) {
                    GroupSummary(context.getString(R.string.analysis_filter_on), comparison.on)
                    GroupSummary(context.getString(R.string.analysis_filter_off), comparison.off)
                    if (comparison.unknown.sessions > 0) GroupSummary(context.getString(R.string.analysis_filter_unknown), comparison.unknown)
                    Text(context.getString(R.string.analysis_descriptive_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String, caption: String, modifier: Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GroupSummary(label: String, group: ExperimentAnalysis.Group) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        HorizontalDivider()
        Text("$label · ${context.getString(R.string.analysis_group_runs, group.sessions)}",
            style = MaterialTheme.typography.labelLarge)
        Text(context.getString(R.string.analysis_group_completion, analysisPercent(context, group.completionRate),
            group.completed, group.rated), style = MaterialTheme.typography.bodySmall)
        Text(context.getString(R.string.analysis_rated_counts, group.rated, group.unrated),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(context.getString(R.string.analysis_group_steps, analysisNumber(context, group.meanGuiSteps.value),
            group.meanGuiSteps.samples), style = MaterialTheme.typography.bodySmall)
        Text(context.getString(R.string.analysis_group_duration, analysisDuration(context, group.meanDurationMs.value?.roundToLong()),
            group.meanDurationMs.samples), style = MaterialTheme.typography.bodySmall)
    }
}

internal fun analysisDuration(context: Context, durationMs: Long?): String =
    durationMs?.takeIf { it >= 0 }?.let { DateUtils.formatElapsedTime(it / 1_000) }
        ?: context.getString(R.string.analysis_unknown)

private fun analysisNumber(context: Context, number: Double?): String = number?.let {
    NumberFormat.getNumberInstance(context.resources.configuration.locales[0]).apply { maximumFractionDigits = 1 }.format(it)
} ?: context.getString(R.string.analysis_unknown)

private fun analysisPercent(context: Context, rate: Double?): String = rate?.let {
    NumberFormat.getPercentInstance(context.resources.configuration.locales[0]).apply { maximumFractionDigits = 1 }.format(it)
} ?: context.getString(R.string.analysis_unknown)
