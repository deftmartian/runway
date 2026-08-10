package dev.deftmartian.runway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
private fun TextEntryField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        minLines = 2,
    )
}

@Composable
internal fun RoutePrivacyDialog(
    current: NativeRoutePrivacy,
    actionPending: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (NativeRoutePrivacy) -> Unit,
) {
    var selection by rememberSaveable { mutableStateOf(current) }
    val deletesStoredRoutes = routePrivacyChangeDeletesStoredRoutes(current, selection)
    val selectionChanged = routePrivacySelectionChanged(current, selection)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Route privacy") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(
                        "Routes can reveal where you train. Choose what this phone retains after import.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    ChoiceRow(
                        "Discard route points",
                        selection == NativeRoutePrivacy.Discard,
                    ) { selection = NativeRoutePrivacy.Discard }
                    Text(
                        "Keep non-route activity details without a route trace.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ChoiceRow(
                        "Keep route traces privately",
                        selection == NativeRoutePrivacy.KeepPrivate,
                    ) { selection = NativeRoutePrivacy.KeepPrivate }
                    Text(
                        "Keep route traces only in local runway storage.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (deletesStoredRoutes) {
                    item {
                        Text(
                            "This permanently removes every route trace already stored by runway, including routes waiting in review. Other retained activity details stay.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(selection) },
                enabled = !actionPending && selectionChanged,
                colors =
                    if (deletesStoredRoutes) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
            ) {
                Text(if (deletesStoredRoutes) "Discard stored routes" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun routePrivacyChangeDeletesStoredRoutes(
    current: NativeRoutePrivacy,
    selected: NativeRoutePrivacy,
): Boolean = current == NativeRoutePrivacy.KeepPrivate && selected == NativeRoutePrivacy.Discard

internal fun routePrivacySelectionChanged(
    current: NativeRoutePrivacy,
    selected: NativeRoutePrivacy,
): Boolean = current != selected

@Composable
internal fun HeartRatePrivacyDialog(
    current: NativeHeartRatePrivacy,
    actionPending: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (NativeHeartRatePrivacy) -> Unit,
) {
    var selection by rememberSaveable { mutableStateOf(current) }
    val deletesStoredHeartRate =
        heartRatePrivacyChangeDeletesStoredData(current, selection)
    val selectionChanged = heartRatePrivacySelectionChanged(current, selection)
    AlertDialog(
        modifier = Modifier.testTag("heart-rate-privacy-dialog"),
        onDismissRequest = onDismiss,
        title = { Text("Heart-rate privacy") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    ChoiceRow(
                        "Discard imported heart-rate values",
                        selection == NativeHeartRatePrivacy.Discard,
                    ) { selection = NativeHeartRatePrivacy.Discard }
                }
                item {
                    Text(
                        "Distance, duration, cadence, and elevation remain.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                item {
                    ChoiceRow(
                        "Keep imported heart-rate values privately",
                        selection == NativeHeartRatePrivacy.KeepPrivate,
                    ) { selection = NativeHeartRatePrivacy.KeepPrivate }
                }
                item {
                    Text(
                        "Summaries and samples stay in private local storage.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                item {
                    Text(
                        "Training-profile heart-rate zones are separate from imported run data.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (deletesStoredHeartRate) {
                    item {
                        Text(
                            "This permanently removes imported heart-rate summaries and samples already stored by runway, including values waiting in review.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(selection) },
                enabled = !actionPending && selectionChanged,
                colors = if (deletesStoredHeartRate) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(if (deletesStoredHeartRate) "Discard stored heart rate" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun heartRatePrivacyChangeDeletesStoredData(
    current: NativeHeartRatePrivacy,
    selected: NativeHeartRatePrivacy,
): Boolean =
    current == NativeHeartRatePrivacy.KeepPrivate &&
        selected == NativeHeartRatePrivacy.Discard

internal fun heartRatePrivacySelectionChanged(
    current: NativeHeartRatePrivacy,
    selected: NativeHeartRatePrivacy,
): Boolean = current != selected

@Composable
internal fun HeartRateProfileDialog(
    current: NativeHeartRateProfile,
    actionPending: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (NativeHeartRateProfile) -> Unit,
) {
    var source by rememberSaveable {
        mutableStateOf(
            current.source.takeUnless { it == NativeHeartRateSource.NotConfigured }
                ?: NativeHeartRateSource.Estimated,
        )
    }
    var sexForEstimate by rememberSaveable { mutableStateOf(current.sexForEstimates) }
    var age by rememberSaveable { mutableStateOf(current.ageYears?.toString().orEmpty()) }
    var max by rememberSaveable { mutableStateOf((current.maxHeartRateBpm ?: 190).toString()) }
    var z2 by rememberSaveable { mutableStateOf((current.zone2FloorBpm ?: 114).toString()) }
    var z3 by rememberSaveable { mutableStateOf((current.zone3FloorBpm ?: 133).toString()) }
    var z4 by rememberSaveable { mutableStateOf((current.zone4FloorBpm ?: 152).toString()) }
    var z5 by rememberSaveable { mutableStateOf((current.zone5FloorBpm ?: 171).toString()) }
    val maxValue = max.toIntOrNull()
    val customValid = customHeartRateValuesAreValid(
        maxHeartRateBpm = maxValue,
        zone2FloorBpm = z2.toIntOrNull(),
        zone3FloorBpm = z3.toIntOrNull(),
        zone4FloorBpm = z4.toIntOrNull(),
        zone5FloorBpm = z5.toIntOrNull(),
    )
    val estimate = estimatedHeartRateProfile(age.toIntOrNull(), sexForEstimate)
    val canSave = when (source) {
        NativeHeartRateSource.Estimated -> estimate != null
        NativeHeartRateSource.Custom -> customValid
        NativeHeartRateSource.NotConfigured -> false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Heart-rate profile") },
        text = {
            HeartRateProfileContent(
                source, { source = it }, sexForEstimate, { sexForEstimate = it },
                age, { age = it }, estimate, max, { max = it }, z2, { z2 = it },
                z3, { z3 = it }, z4, { z4 = it }, z5, { z5 = it },
                actionPending, onSubmit,
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onSubmit(
                        heartRateProfileSubmission(source, estimate, current, max, z2, z3, z4, z5),
                    )
                },
                enabled = !actionPending && canSave,
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun HeartRateProfileContent(
    source: NativeHeartRateSource,
    onSourceChange: (NativeHeartRateSource) -> Unit,
    sex: NativeSexForEstimate,
    onSexChange: (NativeSexForEstimate) -> Unit,
    age: String,
    onAgeChange: (String) -> Unit,
    estimate: NativeHeartRateProfile?,
    max: String,
    onMaxChange: (String) -> Unit,
    z2: String,
    onZ2Change: (String) -> Unit,
    z3: String,
    onZ3Change: (String) -> Unit,
    z4: String,
    onZ4Change: (String) -> Unit,
    z5: String,
    onZ5Change: (String) -> Unit,
    actionPending: Boolean,
    onSubmit: (NativeHeartRateProfile) -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "This optional display profile never changes a plan. Age-based values are population estimates, not measured limits or a health assessment.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            ChoiceRow("Estimate zones from age", source == NativeHeartRateSource.Estimated) {
                onSourceChange(NativeHeartRateSource.Estimated)
            }
            ChoiceRow("Enter zones manually", source == NativeHeartRateSource.Custom) {
                onSourceChange(NativeHeartRateSource.Custom)
            }
        }
        if (source == NativeHeartRateSource.Estimated) {
            item { EstimatedHeartRateFields(age, onAgeChange, sex, onSexChange) }
            item { EstimatedHeartRateSummary(estimate) }
        } else {
            item {
                CustomHeartRateFields(
                    max, onMaxChange, z2, onZ2Change, z3, onZ3Change,
                    z4, onZ4Change, z5, onZ5Change,
                )
            }
        }
        item {
            OutlinedButton(
                onClick = { onSubmit(NativeHeartRateProfile()) },
                enabled = !actionPending,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Clear profile") }
        }
    }
}

@Composable
private fun EstimatedHeartRateFields(
    age: String,
    onAgeChange: (String) -> Unit,
    sex: NativeSexForEstimate,
    onSexChange: (NativeSexForEstimate) -> Unit,
) {
    NumberField("Age", age) { onAgeChange(it.substringBefore(".")) }
    Text(
        "Sex is optional and only changes the age-based estimate.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
    ChoiceRow("Not specified", sex == NativeSexForEstimate.NotSpecified) {
        onSexChange(NativeSexForEstimate.NotSpecified)
    }
    ChoiceRow("Female", sex == NativeSexForEstimate.Female) { onSexChange(NativeSexForEstimate.Female) }
    ChoiceRow("Male", sex == NativeSexForEstimate.Male) { onSexChange(NativeSexForEstimate.Male) }
}

@Composable
private fun EstimatedHeartRateSummary(estimate: NativeHeartRateProfile?) {
    if (estimate == null) {
        Text("Enter an age from 18 to 100.", color = MaterialTheme.colorScheme.error)
    } else {
        Text(
            "Estimated maximum ${estimate.maxHeartRateBpm} bpm. Zone starts: " +
                "${estimate.zone2FloorBpm}, ${estimate.zone3FloorBpm}, " +
                "${estimate.zone4FloorBpm}, ${estimate.zone5FloorBpm} bpm.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CustomHeartRateFields(
    max: String,
    onMaxChange: (String) -> Unit,
    z2: String,
    onZ2Change: (String) -> Unit,
    z3: String,
    onZ3Change: (String) -> Unit,
    z4: String,
    onZ4Change: (String) -> Unit,
    z5: String,
    onZ5Change: (String) -> Unit,
) {
    NumberField("Max heart rate", max) { onMaxChange(it.substringBefore(".")) }
    NumberField("Easy zone starts", z2) { onZ2Change(it.substringBefore(".")) }
    NumberField("Steady zone starts", z3) { onZ3Change(it.substringBefore(".")) }
    NumberField("Hard zone starts", z4) { onZ4Change(it.substringBefore(".")) }
    NumberField("Max zone starts", z5) { onZ5Change(it.substringBefore(".")) }
}

private fun heartRateProfileSubmission(
    source: NativeHeartRateSource,
    estimate: NativeHeartRateProfile?,
    current: NativeHeartRateProfile,
    max: String,
    z2: String,
    z3: String,
    z4: String,
    z5: String,
) = if (source == NativeHeartRateSource.Estimated) {
    requireNotNull(estimate)
} else {
    NativeHeartRateProfile(
        source = NativeHeartRateSource.Custom,
        sexForEstimates = current.sexForEstimates,
        ageYears = current.ageYears,
        maxHeartRateBpm = max.toInt(),
        zone2FloorBpm = z2.toInt(),
        zone3FloorBpm = z3.toInt(),
        zone4FloorBpm = z4.toInt(),
        zone5FloorBpm = z5.toInt(),
    )
}

internal fun estimatedHeartRateProfile(
    ageYears: Int?,
    sexForEstimate: NativeSexForEstimate,
): NativeHeartRateProfile? {
    val age = ageYears?.takeIf { it in 18..100 } ?: return null
    val maximum = when (sexForEstimate) {
        NativeSexForEstimate.Female -> 206.0 - (0.88 * age)
        NativeSexForEstimate.Male,
        NativeSexForEstimate.NotSpecified,
        -> 208.0 - (0.7 * age)
    }.roundToInt()
    return NativeHeartRateProfile(
        source = NativeHeartRateSource.Estimated,
        sexForEstimates = sexForEstimate,
        ageYears = age,
        maxHeartRateBpm = maximum,
        zone2FloorBpm = (maximum * 0.60).roundToInt(),
        zone3FloorBpm = (maximum * 0.70).roundToInt(),
        zone4FloorBpm = (maximum * 0.80).roundToInt(),
        zone5FloorBpm = (maximum * 0.90).roundToInt(),
    )
}

internal fun customHeartRateValuesAreValid(
    maxHeartRateBpm: Int?,
    zone2FloorBpm: Int?,
    zone3FloorBpm: Int?,
    zone4FloorBpm: Int?,
    zone5FloorBpm: Int?,
): Boolean {
    val maximum = maxHeartRateBpm ?: return false
    val zone2 = zone2FloorBpm ?: return false
    val zone3 = zone3FloorBpm ?: return false
    val zone4 = zone4FloorBpm ?: return false
    val zone5 = zone5FloorBpm ?: return false
    return heartRateValuesAreInRange(maximum, zone2, zone3, zone4, zone5) &&
        heartRateZonesAreOrdered(maximum, zone2, zone3, zone4, zone5)
}

private fun heartRateValuesAreInRange(maximum: Int, zone2: Int, zone3: Int, zone4: Int, zone5: Int) =
    maximum in 120..230 && zone2 in 60..220 && zone3 in 70..230 &&
        zone4 in 80..240 && zone5 in 90..250

private fun heartRateZonesAreOrdered(maximum: Int, zone2: Int, zone3: Int, zone4: Int, zone5: Int) =
    zone2 < zone3 && zone3 < zone4 && zone4 < zone5 && zone5 <= maximum
