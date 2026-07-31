package dev.deftmartian.runway.data

import androidx.room.withTransaction
import dev.deftmartian.runway.domain.Consequence
import dev.deftmartian.runway.domain.Consequences
import dev.deftmartian.runway.domain.Deviation
import dev.deftmartian.runway.domain.FeedbackInput
import dev.deftmartian.runway.domain.FeedbackStatus
import dev.deftmartian.runway.domain.LoadMetric
import dev.deftmartian.runway.domain.PlanDecision
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Atomic persistence for factual workout feedback and manual activity candidates. */
class LocalTrainingMutationRepository(
    private val database: RunwayLedgerDatabase,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /**
     * Removes runner-entered feedback only when it has not become evidence for a later decision.
     * Linked-import feedback and an already-applied consequence need their own reversal boundary.
     */
    suspend fun deleteWorkoutFeedback(workoutId: String): LocalWorkoutFeedbackDeletionResult =
        database.withTransaction {
            if (workoutId.isBlank()) {
                return@withTransaction LocalWorkoutFeedbackDeletionResult.Rejected(
                    LocalWorkoutFeedbackDeletionIssue.WORKOUT_NOT_FOUND,
                )
            }
            val planDao = database.goalPlanDao()
            val activityDao = database.activityLedgerDao()
            val workout = planDao.workout(workoutId)
                ?: return@withTransaction LocalWorkoutFeedbackDeletionResult.Rejected(
                    LocalWorkoutFeedbackDeletionIssue.WORKOUT_NOT_FOUND,
                )
            val feedback = activityDao.workoutFeedback(workoutId)
                ?: return@withTransaction LocalWorkoutFeedbackDeletionResult.Rejected(
                    LocalWorkoutFeedbackDeletionIssue.FEEDBACK_NOT_FOUND,
                )
            if (feedback.sourceActivityId != null) {
                return@withTransaction LocalWorkoutFeedbackDeletionResult.Rejected(
                    LocalWorkoutFeedbackDeletionIssue.NOT_DIRECT_FEEDBACK,
                )
            }
            val plan = planDao.plan(workout.planId)
                ?: return@withTransaction LocalWorkoutFeedbackDeletionResult.Rejected(
                    LocalWorkoutFeedbackDeletionIssue.PLAN_NOT_FOUND,
                )
            if (plan.state != ACTIVE_STATE) {
                return@withTransaction LocalWorkoutFeedbackDeletionResult.Rejected(
                    LocalWorkoutFeedbackDeletionIssue.PLAN_NOT_ACTIVE,
                )
            }
            val today = currentToday()
                ?: return@withTransaction LocalWorkoutFeedbackDeletionResult.Rejected(
                    LocalWorkoutFeedbackDeletionIssue.PROFILE_NOT_CONFIGURED,
                )
            if (workout.currentScheduledEpochDay > today.toEpochDay()) {
                return@withTransaction LocalWorkoutFeedbackDeletionResult.Rejected(
                    LocalWorkoutFeedbackDeletionIssue.WORKOUT_IN_FUTURE,
                )
            }
            if (activityDao.activitiesLinkedToWorkout(workoutId).any { it.reviewState == ACTIVITY_REVIEW_STATE_ACCEPTED }) {
                return@withTransaction LocalWorkoutFeedbackDeletionResult.Rejected(
                    LocalWorkoutFeedbackDeletionIssue.LINKED_ACCEPTED_ACTIVITY,
                )
            }
            if (activityDao.workoutFeedbackConsequence(feedback.feedbackId)?.appliedDecision != null) {
                return@withTransaction LocalWorkoutFeedbackDeletionResult.Rejected(
                    LocalWorkoutFeedbackDeletionIssue.CONSEQUENCE_APPLIED,
                )
            }
            if (activityDao.deleteDirectWorkoutFeedback(feedback.feedbackId) != 1) {
                return@withTransaction LocalWorkoutFeedbackDeletionResult.Rejected(
                    LocalWorkoutFeedbackDeletionIssue.FEEDBACK_NOT_FOUND,
                )
            }
            planDao.saveWorkout(
                workout.copy(currentStatus = PLANNED_STATE, updatedAtEpochMillis = nowEpochMillis()),
            )
            LocalWorkoutFeedbackDeletionResult.Deleted(feedback.feedbackId, workoutId)
        }

    suspend fun recordWorkoutFeedback(command: LocalWorkoutFeedbackCommand): LocalTrainingMutationResult =
        database.withTransaction {
            val planDao = database.goalPlanDao()
            val activityDao = database.activityLedgerDao()
            val workout = planDao.workout(command.workoutId)
                ?: return@withTransaction rejected(LocalTrainingMutationIssue.WORKOUT_NOT_FOUND)
            val plan = planDao.plan(workout.planId)
                ?: return@withTransaction rejected(LocalTrainingMutationIssue.PLAN_NOT_FOUND)
            val today = currentToday()
                ?: return@withTransaction rejected(LocalTrainingMutationIssue.PROFILE_NOT_CONFIGURED)
            if (plan.state != ACTIVE_STATE) {
                return@withTransaction rejected(LocalTrainingMutationIssue.PLAN_NOT_ACTIVE)
            }
            if (workout.currentScheduledEpochDay > today.toEpochDay()) {
                return@withTransaction rejected(LocalTrainingMutationIssue.WORKOUT_IN_FUTURE)
            }
            if (workout.currentWorkoutType == REST_TYPE) {
                return@withTransaction rejected(LocalTrainingMutationIssue.REST_DOES_NOT_ACCEPT_FEEDBACK)
            }
            if (
                workout.currentStatus != PLANNED_STATE ||
                workout.tombstonedAtEpochMillis != null ||
                activityDao.workoutFeedback(workout.workoutId) != null
            ) {
                return@withTransaction rejected(LocalTrainingMutationIssue.FEEDBACK_ALREADY_RECORDED)
            }
            feedbackMeasurementIssue(workout, command)?.let {
                return@withTransaction rejected(it)
            }
            if (command.notes.trim().length > MAX_NOTES_LENGTH) {
                return@withTransaction rejected(LocalTrainingMutationIssue.NOTES_TOO_LONG)
            }

            val recent = activityDao.workoutFeedbackInPlanDateRange(
                planId = workout.planId,
                fromEpochDay = workout.currentScheduledEpochDay - RECENT_HISTORY_DAYS,
                beforeEpochDay = workout.currentScheduledEpochDay,
                limit = MAX_RECENT_FEEDBACK,
            )
            val weekWorkouts = planDao.workoutsForWeek(workout.weekId, limit = MAX_WEEK_WORKOUTS)
            val consequence = Consequences.calculate(
                FeedbackInput(
                    targetDistanceMeters = workout.currentDistanceMeters ?: 0,
                    targetDurationSeconds = workout.currentDurationSeconds,
                    weekTargetDistanceMeters = weekWorkouts.sumOfCurrentDistance(),
                    completedDistanceMeters = command.completedDistanceMeters,
                    completedDurationSeconds = command.completedDurationSeconds,
                    status = command.status,
                    feltHard = command.feltHard,
                    pain = command.pain,
                    choice = command.skipChoice,
                    recentSkippedWorkouts = recent.count { it.completionState == SKIPPED_STATE },
                    recentShortenedWorkouts = recent.count { it.completionState == SHORTENED_STATE },
                ),
            )
            val completionState = when {
                command.status == FeedbackStatus.SKIPPED -> SKIPPED_STATE
                consequence.deviation == Deviation.SHORT -> SHORTENED_STATE
                else -> DONE_STATE
            }
            val now = nowEpochMillis()
            val feedbackId = stableFeedbackId(workout.workoutId)
            planDao.saveWorkout(
                workout.copy(currentStatus = completionState, updatedAtEpochMillis = now),
            )
            activityDao.saveWorkoutFeedback(
                WorkoutFeedbackEntity(
                    feedbackId = feedbackId,
                    workoutId = workout.workoutId,
                    completionState = completionState,
                    feltHard = command.feltHard,
                    pain = command.pain,
                    notes = command.notes.trim().takeIf(String::isNotEmpty),
                    recordedAtEpochMillis = now,
                    completedDistanceMeters = command.completedDistanceMeters,
                    completedDurationSeconds = command.completedDurationSeconds,
                ),
            )
            persistWorkoutConsequence(activityDao, feedbackId, consequence, weekWorkouts)
            if (command.pain && workout.currentScheduledEpochDay >= today.minusDays(7).toEpochDay()) {
                markCurrentPain(now)
            }
            LocalTrainingMutationResult.WorkoutFeedbackRecorded(feedbackId, consequence)
        }

    suspend fun recordManualRun(command: LocalManualRunCommand): LocalTrainingMutationResult =
        database.withTransaction {
            manualMeasurementIssue(command)?.let { return@withTransaction rejected(it) }
            if (command.notes.trim().length > MAX_NOTES_LENGTH) {
                return@withTransaction rejected(LocalTrainingMutationIssue.NOTES_TOO_LONG)
            }
            val profile = database.profileSettingsDao().get()
                ?: return@withTransaction rejected(LocalTrainingMutationIssue.PROFILE_NOT_CONFIGURED)
            val zone = profile.timeZone.asZone()
                ?: return@withTransaction rejected(LocalTrainingMutationIssue.PROFILE_NOT_CONFIGURED)
            val today = Instant.ofEpochMilli(nowEpochMillis()).atZone(zone).toLocalDate()
            if (command.occurredDate > today) {
                return@withTransaction rejected(LocalTrainingMutationIssue.ACTIVITY_IN_FUTURE)
            }

            val now = nowEpochMillis()
            val activityId = newId()
            if (activityId.isBlank()) {
                return@withTransaction rejected(LocalTrainingMutationIssue.IDENTITY_GENERATION_FAILED)
            }
            val activity = ActivityEntity(
                activityId = activityId,
                source = MANUAL_SOURCE,
                sourceRecordId = null,
                // Manual entry is evidence, not an implicit claim that it was extra work. The
                // runner must still choose its role (link or extra) before it affects actuals.
                reviewState = REVIEW_STATE,
                occurredAtEpochMillis = command.occurredDate.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
                durationSeconds = command.durationSeconds,
                distanceMeters = command.distanceMeters,
                averageHeartRateBpm = null,
                averageCadenceSpm = null,
                linkedWorkoutId = null,
                acceptedAtEpochMillis = null,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
                extraPlanImpactConfirmed = false,
            )
            val activityDao = database.activityLedgerDao()
            activityDao.saveActivity(activity)
            activityDao.saveFeedback(
                ActivityFeedbackEntity(
                    feedbackId = "activity-feedback-$activityId",
                    activityId = activityId,
                    feltHard = command.feltHard,
                    pain = command.pain,
                    notes = command.notes.trim().takeIf(String::isNotEmpty),
                    recordedAtEpochMillis = now,
                ),
            )
            LocalTrainingMutationResult.ManualRunRecorded(activity)
        }

    private suspend fun persistWorkoutConsequence(
        dao: ActivityLedgerDao,
        feedbackId: String,
        consequence: Consequence,
        week: List<WorkoutEntity>,
    ) {
        dao.saveWorkoutFeedbackConsequence(
            consequence.toWorkoutConsequence(
                feedbackId,
                week.sumOfCurrentDistance(),
                week.sumOfCurrentDuration(),
            ),
        )
        dao.clearWorkoutFeedbackConsequenceOptions(feedbackId)
        consequence.options.forEach { decision ->
            dao.saveWorkoutFeedbackConsequenceOption(
                WorkoutFeedbackConsequenceOptionEntity(feedbackId, decision.toStorageValue()),
            )
        }
    }

    private suspend fun currentToday(): LocalDate? {
        val profile = database.profileSettingsDao().get() ?: return null
        val zone = profile.timeZone.asZone() ?: return null
        return Instant.ofEpochMilli(nowEpochMillis()).atZone(zone).toLocalDate()
    }

    private suspend fun markCurrentPain(now: Long) {
        val dao = database.profileSettingsDao()
        val profile = dao.get() ?: return
        if (!profile.currentPain) dao.save(profile.copy(currentPain = true, updatedAtEpochMillis = now))
    }

    private fun rejected(issue: LocalTrainingMutationIssue) = LocalTrainingMutationResult.Rejected(issue)

    private companion object {
        const val ACTIVE_STATE = "active"
        const val PLANNED_STATE = "planned"
        const val DONE_STATE = "done"
        const val SHORTENED_STATE = "shortened"
        const val SKIPPED_STATE = "skipped"
        const val REST_TYPE = "rest"
        const val MANUAL_SOURCE = "manual"
        const val REVIEW_STATE = "review"
        const val RECENT_HISTORY_DAYS = 28L
        const val MAX_RECENT_FEEDBACK = 128
        const val MAX_WEEK_WORKOUTS = 32
        const val MAX_NOTES_LENGTH = 240
    }
}

data class LocalWorkoutFeedbackCommand(
    val workoutId: String,
    val status: FeedbackStatus,
    val completedDistanceMeters: Int? = null,
    val completedDurationSeconds: Int? = null,
    val feltHard: Boolean = false,
    val pain: Boolean = false,
    val notes: String = "",
    val skipChoice: PlanDecision? = null,
)

data class LocalManualRunCommand(
    val occurredDate: LocalDate,
    val distanceMeters: Int? = null,
    val durationSeconds: Int? = null,
    val feltHard: Boolean = false,
    val pain: Boolean = false,
    val notes: String = "",
)

sealed interface LocalTrainingMutationResult {
    data class WorkoutFeedbackRecorded(val feedbackId: String, val consequence: Consequence) : LocalTrainingMutationResult
    data class ManualRunRecorded(val activity: ActivityEntity) : LocalTrainingMutationResult
    data class Rejected(val issue: LocalTrainingMutationIssue) : LocalTrainingMutationResult
}

sealed interface LocalWorkoutFeedbackDeletionResult {
    data class Deleted(val feedbackId: String, val workoutId: String) : LocalWorkoutFeedbackDeletionResult
    data class Rejected(val issue: LocalWorkoutFeedbackDeletionIssue) : LocalWorkoutFeedbackDeletionResult
}

enum class LocalWorkoutFeedbackDeletionIssue {
    WORKOUT_NOT_FOUND,
    FEEDBACK_NOT_FOUND,
    NOT_DIRECT_FEEDBACK,
    PLAN_NOT_FOUND,
    PLAN_NOT_ACTIVE,
    PROFILE_NOT_CONFIGURED,
    WORKOUT_IN_FUTURE,
    LINKED_ACCEPTED_ACTIVITY,
    CONSEQUENCE_APPLIED,
}

enum class LocalTrainingMutationIssue {
    WORKOUT_NOT_FOUND,
    PLAN_NOT_FOUND,
    PLAN_NOT_ACTIVE,
    WORKOUT_IN_FUTURE,
    REST_DOES_NOT_ACCEPT_FEEDBACK,
    FEEDBACK_ALREADY_RECORDED,
    SKIPPED_WITH_MEASUREMENTS,
    COMPLETED_DISTANCE_REQUIRED,
    COMPLETED_DURATION_REQUIRED,
    INVALID_MEASUREMENT,
    ACTIVITY_IN_FUTURE,
    PROFILE_NOT_CONFIGURED,
    NOTES_TOO_LONG,
    IDENTITY_GENERATION_FAILED,
}

internal fun feedbackMeasurementIssue(
    workout: WorkoutEntity,
    command: LocalWorkoutFeedbackCommand,
): LocalTrainingMutationIssue? {
    if (command.status == FeedbackStatus.SKIPPED) {
        return if (command.completedDistanceMeters == null && command.completedDurationSeconds == null) null
        else LocalTrainingMutationIssue.SKIPPED_WITH_MEASUREMENTS
    }
    if (command.completedDistanceMeters != null && command.completedDistanceMeters <= 0) {
        return LocalTrainingMutationIssue.INVALID_MEASUREMENT
    }
    if (command.completedDurationSeconds != null && command.completedDurationSeconds <= 0) {
        return LocalTrainingMutationIssue.INVALID_MEASUREMENT
    }
    return if ((workout.currentDurationSeconds ?: 0) > 0) {
        LocalTrainingMutationIssue.COMPLETED_DURATION_REQUIRED.takeIf { command.completedDurationSeconds == null }
    } else {
        LocalTrainingMutationIssue.COMPLETED_DISTANCE_REQUIRED.takeIf { command.completedDistanceMeters == null }
    }
}

internal fun manualMeasurementIssue(command: LocalManualRunCommand): LocalTrainingMutationIssue? = when {
    command.distanceMeters != null && command.distanceMeters !in 100..100_000 -> LocalTrainingMutationIssue.INVALID_MEASUREMENT
    command.durationSeconds != null && command.durationSeconds !in 60..36_000 -> LocalTrainingMutationIssue.INVALID_MEASUREMENT
    command.distanceMeters == null && command.durationSeconds == null -> LocalTrainingMutationIssue.INVALID_MEASUREMENT
    else -> null
}

private fun stableFeedbackId(workoutId: String) = "workout-feedback-$workoutId"
private fun String.asZone(): ZoneId? = runCatching { ZoneId.of(this) }.getOrNull()
private fun List<WorkoutEntity>.sumOfCurrentDistance() = sumOf { workout ->
    workout.currentDistanceMeters?.takeIf {
        workout.currentStatus != WORKOUT_STATE_TOMBSTONED && workout.currentWorkoutType != "rest"
    } ?: 0
}
private fun List<WorkoutEntity>.sumOfCurrentDuration() = sumOf { workout ->
    workout.currentDurationSeconds?.takeIf {
        workout.currentStatus != WORKOUT_STATE_TOMBSTONED && workout.currentWorkoutType != "rest"
    } ?: 0
}

private fun Consequence.toWorkoutConsequence(
    feedbackId: String,
    currentWeekDistanceMeters: Int,
    currentWeekDurationSeconds: Int,
): WorkoutFeedbackConsequenceEntity {
    val distanceAdjustment = nextRunAdjustment?.takeIf { it.metric == LoadMetric.DISTANCE }?.value ?: 0
    val durationAdjustment = nextRunAdjustment?.takeIf { it.metric == LoadMetric.DURATION }?.value ?: 0
    return WorkoutFeedbackConsequenceEntity(
        feedbackId = feedbackId,
        classification = kind.name.lowercase(),
        distanceDifferenceMeters = actualDifference.takeIf { metric == LoadMetric.DISTANCE },
        durationDifferenceSeconds = actualDifference.takeIf { metric == LoadMetric.DURATION },
        currentWeekLoadMeters = currentWeekDistanceMeters,
        projectedWeekLoadMeters = (currentWeekDistanceMeters + distanceAdjustment).coerceAtLeast(0),
        assessment = risk.name.lowercase(),
        recoveryConflictCount = 0,
        recommendedDecision = recommendedDecision.toStorageValue(),
        nextWorkoutAction = recommendedDecision.toStorageValue(),
        requiresExplicitConfirmation = recommendedDecision != PlanDecision.KEEP_PLAN,
        deviation = deviation.name.lowercase(),
        loadMetric = metric.name.lowercase(),
        risk = risk.name.lowercase(),
        appliedDecision = null,
        comparisonStatus = comparisonStatus,
        planChangeAvailable = planChangeAvailable,
        currentWeekLoadDurationSeconds = currentWeekDurationSeconds,
        projectedWeekLoadDurationSeconds = (currentWeekDurationSeconds + durationAdjustment).coerceAtLeast(0),
    )
}

private fun Consequence.toActivityConsequence(activity: ActivityEntity) = ActivityConsequenceEntity(
    activityId = activity.activityId,
    classification = kind.name.lowercase(),
    distanceDifferenceMeters = actualDifference.takeIf { metric == LoadMetric.DISTANCE },
    durationDifferenceSeconds = actualDifference.takeIf { metric == LoadMetric.DURATION },
    actualLoadMeters = activity.distanceMeters,
    assessment = risk.name.lowercase(),
    recommendedDecision = recommendedDecision.toStorageValue(),
    resolvedAtEpochMillis = null,
    deviation = deviation.name.lowercase(),
    loadMetric = metric.name.lowercase(),
    risk = risk.name.lowercase(),
    appliedDecision = null,
    comparisonStatus = comparisonStatus,
    planChangeAvailable = planChangeAvailable,
    actualLoadDurationSeconds = activity.durationSeconds,
)
