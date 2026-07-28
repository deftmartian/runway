package dev.deftmartian.runway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun NativeList(loading: Boolean, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (loading) item { LinearLoadingNotice() }
        content()
    }
}

@Composable
internal fun ScreenIntro(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun WorkoutCard(
    workout: NativeWorkout,
    onRecord: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onReset: (() -> Unit)? = null,
    onUndo: (() -> Unit)? = null,
) {
    val type = workout.type.orEmpty().replaceFirstChar { it.uppercase() }
    val distance = workout.targetDistanceMeters?.let(::formatDistance)
    val duration = workout.targetDurationSeconds?.let(::formatDuration)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(workout.scheduledDate.orEmpty(), style = MaterialTheme.typography.labelLarge)
            Text(type.ifBlank { "Run" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(distance, duration).joinToString(" · ").ifBlank { "Use the plan details" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            workout.purpose?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (workout.isEdited == true) {
                Text("Adjusted", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            if (onRecord != null || onEdit != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onRecord?.let {
                        Button(onClick = it) { Text("Record result") }
                    }
                    onEdit?.let {
                        OutlinedButton(onClick = it) { Text("Adjust") }
                    }
                }
            }
            if (onReset != null || onUndo != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onUndo?.let {
                        TextButton(onClick = it) { Text("Undo last change") }
                    }
                    onReset?.let {
                        TextButton(onClick = it) { Text("Restore recommendation") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ActivityCard(
    activity: NativeActivity,
    title: String? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    val distance = activity.distanceMeters?.let(::formatDistance)
    val duration = activity.durationSeconds?.let(::formatDuration)
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            Text(
                activity.occurredDate.orEmpty().ifBlank { activity.activityDate.orEmpty() }
                    .ifBlank { "Activity" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                listOfNotNull(distance, duration, activity.source?.takeIf(String::isNotBlank))
                    .joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (activity.pain == true) Text("Pain noted", color = MaterialTheme.colorScheme.error)
            else if (activity.feltHard == true) Text("Hard effort noted", color = MaterialTheme.colorScheme.primary)
            actions?.invoke()
        }
    }
}

@Composable
internal fun WeekCard(week: NativeWeek, summary: NativeWeekSummary?) {
    val target = week.targetDistanceMeters?.let(::formatDistance)
    val completed = summary?.completedDistanceMeters?.let(::formatDistance)
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Week ${week.weekNumber ?: 0}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${completed ?: "0 km"} completed · ${target ?: "—"} planned")
            Text(
                listOfNotNull(
                    week.startDate?.takeIf(String::isNotBlank),
                    week.risk?.takeIf(String::isNotBlank)?.replaceFirstChar { it.uppercase() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun SettingCard(title: String, content: @Composable () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
internal fun SettingRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun EmptyCard(message: String) {
    Card {
        Text(
            message,
            modifier = Modifier.padding(18.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun Notice(message: String, isError: Boolean = false) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            message,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun LinearLoadingNotice() {
    Text(
        "Refreshing…",
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.End,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun CenteredSurface(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        content()
    }
}
