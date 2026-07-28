package dev.deftmartian.runway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun NextcloudConnectionDialog(
    actionPending: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (ConnectNextcloudCommand) -> Unit,
) {
    var label by rememberSaveable { mutableStateOf("Nextcloud GPX folder") }
    var shareUrl by rememberSaveable { mutableStateOf("") }
    var sharePassword by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect a Nextcloud folder") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(
                        "Use a password-protected public folder share. Runway stores the token and password encrypted on the server.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it.take(120) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Folder name") },
                        singleLine = true,
                    )
                }
                item {
                    OutlinedTextField(
                        value = shareUrl,
                        onValueChange = { shareUrl = it.take(2_048) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Public share URL") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true,
                    )
                }
                item {
                    OutlinedTextField(
                        value = sharePassword,
                        onValueChange = { sharePassword = it.take(1_024) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Share password") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSubmit(
                        ConnectNextcloudCommand(
                            label = label.trim(),
                            shareUrl = shareUrl.trim(),
                            sharePassword = sharePassword,
                        ),
                    )
                },
                enabled = !actionPending && shareUrl.isNotBlank() && sharePassword.isNotEmpty(),
            ) {
                Text("Connect")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
@Composable
internal fun HealthContextDialog(
    injuryFlags: NativeInjuryFlags?,
    actionPending: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (UpdateHealthContextCommand) -> Unit,
) {
    var recentInjury by rememberSaveable { mutableStateOf(injuryFlags?.recentInjury ?: false) }
    var currentPain by rememberSaveable { mutableStateOf(injuryFlags?.currentPain ?: false) }
    var recurringPain by rememberSaveable { mutableStateOf(injuryFlags?.recurringPain ?: false) }
    var medicalRestriction by rememberSaveable {
        mutableStateOf(injuryFlags?.medicalRestriction ?: false)
    }
    var notes by rememberSaveable { mutableStateOf(injuryFlags?.notes.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Health context") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(
                        "These answers can make the plan more conservative. They are not a diagnosis.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    CheckRow("Recent injury affects current training", recentInjury) {
                        recentInjury = it
                    }
                    CheckRow("Pain is present now", currentPain) { currentPain = it }
                    CheckRow("Pain tends to recur while running", recurringPain) {
                        recurringPain = it
                    }
                    CheckRow("A clinician has limited current training", medicalRestriction) {
                        medicalRestriction = it
                    }
                }
                item {
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it.take(240) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Optional private context") },
                        minLines = 2,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSubmit(
                        UpdateHealthContextCommand(
                            recentInjury = recentInjury,
                            currentPain = currentPain,
                            recurringPain = recurringPain,
                            medicalRestriction = medicalRestriction,
                            injuryNotes = notes,
                        ),
                    )
                },
                enabled = !actionPending,
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
@Composable
internal fun HeartRateProfileDialog(
    profile: NativeProfile?,
    actionPending: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (UpdateTrainingProfileCommand) -> Unit,
) {
    var sex by rememberSaveable { mutableStateOf(profile?.sexForEstimates.orEmpty().ifBlank { "not_specified" }) }
    var age by rememberSaveable {
        mutableStateOf(profile?.ageYears?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var source by rememberSaveable {
        mutableStateOf(profile?.heartRateSettingsSource.orEmpty().takeIf { it in setOf("estimated", "custom") } ?: "custom")
    }
    var max by rememberSaveable { mutableStateOf((profile?.maxHeartRateBpm ?: 190).toString()) }
    var z2 by rememberSaveable { mutableStateOf((profile?.zone2FloorBpm ?: 114).toString()) }
    var z3 by rememberSaveable { mutableStateOf((profile?.zone3FloorBpm ?: 133).toString()) }
    var z4 by rememberSaveable { mutableStateOf((profile?.zone4FloorBpm ?: 152).toString()) }
    var z5 by rememberSaveable { mutableStateOf((profile?.zone5FloorBpm ?: 171).toString()) }
    fun applyEstimate() {
        val ageNumber = age.toIntOrNull()?.takeIf { it in 18..100 } ?: return
        val estimatedMax =
            if (sex == "female") (206 - 0.88 * ageNumber).toInt() else (208 - 0.7 * ageNumber).toInt()
        max = estimatedMax.toString()
        z2 = (estimatedMax * 0.60).toInt().toString()
        z3 = (estimatedMax * 0.70).toInt().toString()
        z4 = (estimatedMax * 0.80).toInt().toString()
        z5 = (estimatedMax * 0.90).toInt().toString()
    }
    val ageValue = age.toIntOrNull()
    val maxValue = max.toIntOrNull()
    val floors = listOf(z2, z3, z4, z5).map { it.toIntOrNull() }
    val valid =
        (age.isBlank() || (ageValue != null && ageValue in 18..100)) &&
            maxValue != null &&
            maxValue in 120..230 &&
            floors.all { it != null } &&
            floors.filterNotNull().zipWithNext().all { (left, right) -> right > left } &&
            (floors.lastOrNull() ?: Int.MAX_VALUE) <= maxValue
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Heart-rate profile") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(
                        "Heart rate is descriptive context. It does not turn a run into a medical assessment.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Text("Estimate profile", style = MaterialTheme.typography.labelLarge)
                    listOf(
                        "not_specified" to "Not specified",
                        "female" to "Female estimate",
                        "male" to "Male estimate",
                    ).forEach { (value, label) ->
                        ChoiceRow(label, sex == value) { sex = value }
                    }
                    NumberField("Age (optional)", age) { age = it.substringBefore(".") }
                    OutlinedButton(
                        onClick = {
                            source = "estimated"
                            applyEstimate()
                        },
                        enabled = ageValue != null && ageValue in 18..100,
                    ) {
                        Text("Apply estimate")
                    }
                }
                item {
                    NumberField("Max heart rate", max) { max = it.substringBefore("."); source = "custom" }
                    NumberField("Easy zone starts", z2) { z2 = it.substringBefore("."); source = "custom" }
                    NumberField("Steady zone starts", z3) { z3 = it.substringBefore("."); source = "custom" }
                    NumberField("Hard zone starts", z4) { z4 = it.substringBefore("."); source = "custom" }
                    NumberField("Max zone starts", z5) { z5 = it.substringBefore("."); source = "custom" }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSubmit(
                        UpdateTrainingProfileCommand(
                            sexForEstimates = sex,
                            ageYears = age.toIntOrNull(),
                            heartRateSettingsSource = source,
                            maxHeartRateBpm = max.toInt(),
                            zone2FloorBpm = z2.toInt(),
                            zone3FloorBpm = z3.toInt(),
                            zone4FloorBpm = z4.toInt(),
                            zone5FloorBpm = z5.toInt(),
                        ),
                    )
                },
                enabled = !actionPending && valid,
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
