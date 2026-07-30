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
                enabled = !actionPending,
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Heart-rate privacy") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(
                        "Choose whether imported runs keep heart-rate data. Training-profile zones are separate.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                enabled = !actionPending,
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

@Composable
internal fun HealthContextDialog(
    current: NativeHealthContext,
    actionPending: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (NativeHealthContext) -> Unit,
) {
    var recentInjury by rememberSaveable { mutableStateOf(current.recentInjury) }
    var currentPain by rememberSaveable { mutableStateOf(current.currentPain) }
    var recurringPain by rememberSaveable { mutableStateOf(current.recurringPain) }
    var clinicianRestriction by rememberSaveable { mutableStateOf(current.clinicianRestriction) }
    var notes by rememberSaveable { mutableStateOf(current.notes) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Health context") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(
                        "This can make training suggestions more conservative. It is not medical advice or a diagnosis.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    CheckRow("Recent injury affects current training", recentInjury) { recentInjury = it }
                    CheckRow("Pain is present now", currentPain) { currentPain = it }
                    CheckRow("Pain tends to recur while running", recurringPain) { recurringPain = it }
                    CheckRow("A clinician has limited current training", clinicianRestriction) { clinicianRestriction = it }
                }
                item {
                    TextEntryField(
                        "Optional private context",
                        notes,
                        { notes = it.take(240) },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSubmit(
                        NativeHealthContext(
                            recentInjury,
                            currentPain,
                            recurringPain,
                            clinicianRestriction,
                            notes,
                        ),
                    )
                },
                enabled = !actionPending,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

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
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(
                        "This optional display profile never changes a plan. Age-based values are population estimates, not measured limits or a health assessment.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    ChoiceRow(
                        "Estimate zones from age",
                        source == NativeHeartRateSource.Estimated,
                    ) { source = NativeHeartRateSource.Estimated }
                    ChoiceRow(
                        "Enter zones manually",
                        source == NativeHeartRateSource.Custom,
                    ) { source = NativeHeartRateSource.Custom }
                }
                if (source == NativeHeartRateSource.Estimated) {
                    item {
                        NumberField("Age", age) { age = it.substringBefore(".") }
                        Text(
                            "Sex is optional and only changes the age-based estimate.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        ChoiceRow(
                            "Not specified",
                            sexForEstimate == NativeSexForEstimate.NotSpecified,
                        ) { sexForEstimate = NativeSexForEstimate.NotSpecified }
                        ChoiceRow(
                            "Female",
                            sexForEstimate == NativeSexForEstimate.Female,
                        ) { sexForEstimate = NativeSexForEstimate.Female }
                        ChoiceRow(
                            "Male",
                            sexForEstimate == NativeSexForEstimate.Male,
                        ) { sexForEstimate = NativeSexForEstimate.Male }
                    }
                    item {
                        if (estimate == null) {
                            Text(
                                "Enter an age from 18 to 100.",
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            Text(
                                "Estimated maximum ${estimate.maxHeartRateBpm} bpm. Zone starts: " +
                                    "${estimate.zone2FloorBpm}, ${estimate.zone3FloorBpm}, " +
                                    "${estimate.zone4FloorBpm}, ${estimate.zone5FloorBpm} bpm.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    item {
                        NumberField("Max heart rate", max) { max = it.substringBefore(".") }
                        NumberField("Easy zone starts", z2) { z2 = it.substringBefore(".") }
                        NumberField("Steady zone starts", z3) { z3 = it.substringBefore(".") }
                        NumberField("Hard zone starts", z4) { z4 = it.substringBefore(".") }
                        NumberField("Max zone starts", z5) { z5 = it.substringBefore(".") }
                    }
                }
                item {
                    OutlinedButton(
                        onClick = { onSubmit(NativeHeartRateProfile()) },
                        enabled = !actionPending,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Clear profile")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSubmit(
                        if (source == NativeHeartRateSource.Estimated) {
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
                        },
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
    return maximum in 120..230 &&
        zone2 in 60..220 &&
        zone3 in 70..230 &&
        zone4 in 80..240 &&
        zone5 in 90..250 &&
        zone2 < zone3 &&
        zone3 < zone4 &&
        zone4 < zone5 &&
        zone5 <= maximum
}
