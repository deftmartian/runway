package dev.deftmartian.runway.data

import androidx.room.withTransaction
import java.time.ZoneId

enum class SexForEstimate(val storageValue: String) {
    Female("female"),
    Male("male"),
    NotSpecified("not_specified"),
}

enum class HeartRateSettingsSource(val storageValue: String) {
    Estimated("estimated"),
    Custom("custom"),
}

data class LocalHealthContext(
    val recentInjury: Boolean,
    val currentPain: Boolean,
    val recurringPain: Boolean,
    val medicalRestriction: Boolean,
    val privateNotes: String,
)

data class LocalHeartRateProfile(
    val sexForEstimate: SexForEstimate,
    val ageYears: Int?,
    val source: HeartRateSettingsSource,
    val maxHeartRateBpm: Int,
    val zone2FloorBpm: Int,
    val zone3FloorBpm: Int,
    val zone4FloorBpm: Int,
    val zone5FloorBpm: Int,
)

sealed interface LocalProfileUpdateResult {
    data object Updated : LocalProfileUpdateResult
    data object ProfileNotConfigured : LocalProfileUpdateResult
    data class Invalid(val issue: LocalProfileIssue) : LocalProfileUpdateResult
}

enum class LocalProfileIssue {
    INVALID_TIME_ZONE,
    HEALTH_NOTES_TOO_LONG,
    AGE_OUT_OF_RANGE,
    MAX_HEART_RATE_OUT_OF_RANGE,
    HEART_RATE_ZONE_OUT_OF_RANGE,
    HEART_RATE_ZONES_NOT_INCREASING,
    ZONE_FIVE_ABOVE_MAXIMUM,
}

/**
 * Typed, plan-neutral profile mutations for the local single-runner ledger.
 *
 * Updating current pain or another health flag records context; it never silently regenerates or
 * replaces the active plan.
 */
class LocalProfileRepository(
    private val database: RunwayLedgerDatabase,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun updateTimeZone(timeZone: String): LocalProfileUpdateResult {
        val normalized = timeZone.trim()
        if (normalized.length > 100 || runCatching { ZoneId.of(normalized) }.isFailure) {
            return LocalProfileUpdateResult.Invalid(LocalProfileIssue.INVALID_TIME_ZONE)
        }
        return update { it.copy(timeZone = normalized, updatedAtEpochMillis = nowEpochMillis()) }
    }

    suspend fun updateHealthContext(context: LocalHealthContext): LocalProfileUpdateResult {
        val notes = context.privateNotes.trim()
        if (notes.length > 240) {
            return LocalProfileUpdateResult.Invalid(LocalProfileIssue.HEALTH_NOTES_TOO_LONG)
        }
        return update {
            it.copy(
                recentInjury = context.recentInjury,
                currentPain = context.currentPain,
                recurringPain = context.recurringPain,
                medicalRestriction = context.medicalRestriction,
                privateNotes = notes.takeIf(String::isNotEmpty),
                updatedAtEpochMillis = nowEpochMillis(),
            )
        }
    }

    suspend fun updateHeartRateProfile(profile: LocalHeartRateProfile): LocalProfileUpdateResult {
        validateHeartRateProfile(profile)?.let { return LocalProfileUpdateResult.Invalid(it) }
        return update {
            it.copy(
                sexForEstimates = profile.sexForEstimate.storageValue,
                ageYears = profile.ageYears,
                heartRateSettingsSource = profile.source.storageValue,
                maxHeartRateBpm = profile.maxHeartRateBpm,
                zone2FloorBpm = profile.zone2FloorBpm,
                zone3FloorBpm = profile.zone3FloorBpm,
                zone4FloorBpm = profile.zone4FloorBpm,
                zone5FloorBpm = profile.zone5FloorBpm,
                updatedAtEpochMillis = nowEpochMillis(),
            )
        }
    }

    private suspend fun update(
        mutation: (ProfileSettingsEntity) -> ProfileSettingsEntity,
    ): LocalProfileUpdateResult = database.withTransaction {
        val dao = database.profileSettingsDao()
        val current = dao.get()
            ?: return@withTransaction LocalProfileUpdateResult.ProfileNotConfigured
        dao.save(mutation(current))
        LocalProfileUpdateResult.Updated
    }
}

fun validateHeartRateProfile(profile: LocalHeartRateProfile): LocalProfileIssue? {
    if (profile.ageYears != null && profile.ageYears !in 18..100) {
        return LocalProfileIssue.AGE_OUT_OF_RANGE
    }
    if (profile.maxHeartRateBpm !in 120..230) {
        return LocalProfileIssue.MAX_HEART_RATE_OUT_OF_RANGE
    }
    val floors = listOf(
        profile.zone2FloorBpm,
        profile.zone3FloorBpm,
        profile.zone4FloorBpm,
        profile.zone5FloorBpm,
    )
    if (
        profile.zone2FloorBpm !in 60..220 ||
        profile.zone3FloorBpm !in 70..230 ||
        profile.zone4FloorBpm !in 80..240 ||
        profile.zone5FloorBpm !in 90..250
    ) {
        return LocalProfileIssue.HEART_RATE_ZONE_OUT_OF_RANGE
    }
    if (floors.zipWithNext().any { (previous, next) -> next <= previous }) {
        return LocalProfileIssue.HEART_RATE_ZONES_NOT_INCREASING
    }
    if (profile.zone5FloorBpm > profile.maxHeartRateBpm) {
        return LocalProfileIssue.ZONE_FIVE_ABOVE_MAXIMUM
    }
    return null
}
