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
internal fun RoutePrivacyDialog(current: NativeRoutePrivacy, actionPending: Boolean, onDismiss: () -> Unit, onSubmit: (NativeRoutePrivacy) -> Unit) {
    var selection by rememberSaveable { mutableStateOf(current) }
    val deletesStoredRoutes = routePrivacyChangeDeletesStoredRoutes(current, selection)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Route privacy") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("Routes can reveal where you train. Choose what this phone retains after import.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item {
                    ChoiceRow("Discard route points", selection == NativeRoutePrivacy.Discard) { selection = NativeRoutePrivacy.Discard }
                    Text("Keep activity totals and heart rate, without a route trace.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    ChoiceRow("Keep route traces privately", selection == NativeRoutePrivacy.KeepPrivate) { selection = NativeRoutePrivacy.KeepPrivate }
                    Text("Keep route traces only in local runway storage.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                if (deletesStoredRoutes) {
                    item {
                        Text(
                            "This permanently removes every route trace already stored by runway, including routes waiting in review. Activity totals and heart rate stay.",
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
internal fun HealthContextDialog(current: NativeHealthContext, actionPending: Boolean, onDismiss: () -> Unit, onSubmit: (NativeHealthContext) -> Unit) {
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
                item { Text("This can make training suggestions more conservative. It is not medical advice or a diagnosis.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item {
                    CheckRow("Recent injury affects current training", recentInjury) { recentInjury = it }
                    CheckRow("Pain is present now", currentPain) { currentPain = it }
                    CheckRow("Pain tends to recur while running", recurringPain) { recurringPain = it }
                    CheckRow("A clinician has limited current training", clinicianRestriction) { clinicianRestriction = it }
                }
                item { TextEntryField("Optional private context", notes, { notes = it.take(240) }) }
            }
        },
        confirmButton = { Button(onClick = { onSubmit(NativeHealthContext(recentInjury, currentPain, recurringPain, clinicianRestriction, notes)) }, enabled = !actionPending) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun HeartRateProfileDialog(current: NativeHeartRateProfile, actionPending: Boolean, onDismiss: () -> Unit, onSubmit: (NativeHeartRateProfile) -> Unit) {
    var max by rememberSaveable { mutableStateOf((current.maxHeartRateBpm ?: 190).toString()) }
    var z2 by rememberSaveable { mutableStateOf((current.zone2FloorBpm ?: 114).toString()) }
    var z3 by rememberSaveable { mutableStateOf((current.zone3FloorBpm ?: 133).toString()) }
    var z4 by rememberSaveable { mutableStateOf((current.zone4FloorBpm ?: 152).toString()) }
    var z5 by rememberSaveable { mutableStateOf((current.zone5FloorBpm ?: 171).toString()) }
    val floors = listOf(z2, z3, z4, z5).map { it.toIntOrNull() }
    val maxValue = max.toIntOrNull()
    val valid = maxValue != null && maxValue in 120..230 && floors.all { it != null } && floors.filterNotNull().zipWithNext().all { (a, b) -> b > a } && (floors.lastOrNull() ?: Int.MAX_VALUE) <= maxValue
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Heart-rate profile") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("Heart rate is descriptive training context. It does not assess health or safety.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item {
                    OutlinedButton(onClick = {
                        onSubmit(NativeHeartRateProfile())
                    }, enabled = !actionPending) { Text("Clear profile") }
                }
                item {
                    NumberField("Max heart rate", max) { max = it.substringBefore(".") }
                    NumberField("Easy zone starts", z2) { z2 = it.substringBefore(".") }
                    NumberField("Steady zone starts", z3) { z3 = it.substringBefore(".") }
                    NumberField("Hard zone starts", z4) { z4 = it.substringBefore(".") }
                    NumberField("Max zone starts", z5) { z5 = it.substringBefore(".") }
                }
            }
        },
        confirmButton = { Button(onClick = { onSubmit(NativeHeartRateProfile(NativeHeartRateSource.Custom, max.toInt(), z2.toInt(), z3.toInt(), z4.toInt(), z5.toInt())) }, enabled = !actionPending && valid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
