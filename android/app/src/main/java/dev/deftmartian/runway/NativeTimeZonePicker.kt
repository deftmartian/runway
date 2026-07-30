package dev.deftmartian.runway

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.util.Locale

private const val MAX_TIME_ZONE_RESULTS = 50
private val userSelectableTimeZoneIds: Set<String> by lazy {
    ZoneId.getAvailableZoneIds()
        .filterTo(mutableSetOf()) { "/" in it || it == "UTC" }
}

/**
 * Finds IANA zone identifiers with stable ordering so callers can render a bounded picker.
 *
 * The collection is an argument to keep this selection policy deterministic and JVM-testable.
 */
internal fun filterTimeZoneIds(
    query: String,
    timeZoneIds: Set<String> = ZoneId.getAvailableZoneIds(),
    limit: Int = MAX_TIME_ZONE_RESULTS,
): List<String> {
    if (limit <= 0) return emptyList()
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    return timeZoneIds
        .asSequence()
        .filter { normalizedQuery.isEmpty() || it.lowercase(Locale.ROOT).contains(normalizedQuery) }
        .sorted()
        .take(limit)
        .toList()
}

/** A searchable, local-only IANA time-zone selector for setup and settings. */
@Composable
internal fun NativeTimeZonePicker(
    currentTimeZoneId: String,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
    deviceTimeZoneId: String = ZoneId.systemDefault().id,
    timeZoneIds: Set<String> = userSelectableTimeZoneIds,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val matchingZones =
        if (query.isBlank()) emptyList() else filterTimeZoneIds(query, timeZoneIds)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Training time zone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "This controls which calendar day a run belongs to.",
                )
                Text("Current: $currentTimeZoneId")
                OutlinedButton(
                    onClick = { onSelected(deviceTimeZoneId) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text("Use phone time zone ($deviceTimeZoneId)")
                }
                HorizontalDivider()
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search time zones") },
                    supportingText = { Text("Try a city or region, such as Halifax or Europe.") },
                    singleLine = true,
                )
                if (query.isBlank()) {
                    Text("Search only if training should use a different time zone.")
                } else if (matchingZones.isEmpty()) {
                    Text("No time zones match that search.")
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 280.dp),
                    ) {
                        items(matchingZones, key = { it }) { timeZoneId ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable(
                                        role = Role.Button,
                                        onClick = { onSelected(timeZoneId) },
                                    )
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = timeZoneId,
                                    modifier = Modifier.weight(1f),
                                )
                                if (timeZoneId == currentTimeZoneId) Text("Current")
                            }
                            HorizontalDivider()
                        }
                    }
                    if (matchingZones.size == MAX_TIME_ZONE_RESULTS) {
                        Text("Showing the first $MAX_TIME_ZONE_RESULTS matches. Refine your search to narrow the list.")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
