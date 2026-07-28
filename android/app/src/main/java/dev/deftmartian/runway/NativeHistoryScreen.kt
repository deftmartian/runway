package dev.deftmartian.runway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun HistoryScreen(
    payload: NativeHistoryPayload?,
    loading: Boolean,
    onLoadMore: () -> Unit,
    onOpenPlan: (String) -> Unit,
) {
    val history = payload?.history
    NativeList(loading) {
        item {
            ScreenIntro(
                "History",
                "Plan phases, recorded work, and the decisions that closed each phase.",
            )
        }
        when {
            payload == null -> item { EmptyCard("Loading history…") }
            payload.onboardingRequired == true ->
                item { EmptyCard("Create a plan to start a training record.") }
            history?.items.isNullOrEmpty() ->
                item { EmptyCard("No plan history yet.") }
            else -> {
                items(
                    requireNotNull(history).items,
                    key = { item -> item.plan?.id.orEmpty() },
                ) { item ->
                    PlanHistoryRecord(item, onOpenPlan)
                }
            }
        }
        if (history?.nextOffset != null) {
            item {
                OutlinedButton(
                    onClick = onLoadMore,
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (loading) "Loading…" else "Load earlier plans")
                }
            }
        }
    }
}

@Composable
private fun PlanHistoryRecord(item: NativePlanHistoryItem, onOpenPlan: (String) -> Unit) {
    val plan = item.plan
    val summary = item.summary
    val state = plan?.status.orEmpty().replaceFirstChar(Char::uppercase).ifBlank { "Recorded" }
    val closedOn = plan?.completedAt?.takeIf(String::isNotBlank)?.let { "Completed $it" }
        ?: plan?.archivedAt?.takeIf(String::isNotBlank)?.let { "Stopped $it" }
        ?: if (plan?.status == "active") "Current plan" else null
    SettingCard(item.goal?.title.orDash()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                listOfNotNull(plan?.startDate, plan?.targetDate).joinToString(" → ").orDash(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
            LedgerState(
                state,
                if (plan?.status == "active") LedgerEmphasis.Planned else LedgerEmphasis.Neutral,
            )
        }
        closedOn?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            MeasurementReadout(
                "Recorded / planned",
                "${summary?.completedRuns ?: 0} / ${summary?.plannedRuns ?: 0}",
                LedgerEmphasis.Actual,
                Modifier.weight(1f),
            )
            MeasurementReadout(
                "Actual distance",
                summary?.completedDistanceMeters?.let(::formatDistance).orDash(),
                LedgerEmphasis.Actual,
                Modifier.weight(1f),
            )
        }
        val exceptions = buildList {
            summary?.missedRuns?.takeIf { it > 0 }?.let { add("$it missed") }
            summary?.skippedRuns?.takeIf { it > 0 }?.let { add("$it skipped") }
            summary?.painFlags?.takeIf { it > 0 }?.let { add("$it pain reports") }
        }
        if (exceptions.isNotEmpty()) {
            Text(
                exceptions.joinToString(" · "),
                color = if ((summary?.painFlags ?: 0) > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = FontWeight.SemiBold,
            )
        }
        plan?.lifecycleReason?.takeIf(String::isNotBlank)?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        plan?.id?.takeIf(String::isNotBlank)?.let { planId ->
            TextButton(onClick = { onOpenPlan(planId) }) { Text("Open plan record") }
        }
    }
}
