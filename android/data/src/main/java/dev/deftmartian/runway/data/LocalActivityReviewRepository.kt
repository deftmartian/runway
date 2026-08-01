package dev.deftmartian.runway.data

import androidx.room.withTransaction
import dev.deftmartian.runway.domain.ACTIVITY_WORKOUT_MATCH_WINDOW_DAYS
import dev.deftmartian.runway.domain.Consequence
import dev.deftmartian.runway.domain.Consequences
import dev.deftmartian.runway.domain.Deviation
import dev.deftmartian.runway.domain.ExtraActivityInput
import dev.deftmartian.runway.domain.ExtraActivityTargets
import dev.deftmartian.runway.domain.FeedbackInput
import dev.deftmartian.runway.domain.FeedbackStatus
import dev.deftmartian.runway.domain.LoadMetric
import dev.deftmartian.runway.domain.PlanDecision
import dev.deftmartian.runway.domain.calculateExtraActivityConsequence
import dev.deftmartian.runway.domain.historicalExtraActivityReview
import dev.deftmartian.runway.domain.isHistoricalExtraActivity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * The explicit Review boundary for activity candidates.
 *
 * Factual activity data can be inspected while it is in Review, but only [link] or
 * [confirmAsExtra] moves it into calendar actuals and statistics. Future plan changes remain a
 * separate decision; this repository records the consequence and available choices only.
 */
class LocalActivityReviewRepository(
    private val database: RunwayLedgerDatabase,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun link(
        activityId: String,
        workoutId: String,
        feltHard: Boolean? = null,
        pain: Boolean? = null,
    ): LocalActivityReviewResult =
        database.withTransaction {
            val activityDao = database.activityLedgerDao()
            val planDao = database.goalPlanDao()
            val activity = activityDao.activity(activityId)
                ?: return@withTransaction rejected(LocalActivityReviewIssue.ACTIVITY_NOT_FOUND)
            if (activity.reviewState != REVIEW_STATE) {
                return@withTransaction rejected(LocalActivityReviewIssue.ACTIVITY_ALREADY_RESOLVED)
            }
            val workout = planDao.workout(workoutId)
                ?: return@withTransaction rejected(LocalActivityReviewIssue.WORKOUT_NOT_FOUND)
            val plan = planDao.plan(workout.planId)
                ?: return@withTransaction rejected(LocalActivityReviewIssue.PLAN_NOT_FOUND)
            if (plan.state != ACTIVE_STATE) {
                return@withTransaction rejected(LocalActivityReviewIssue.PLAN_NOT_ACTIVE)
            }
            if (
                workout.currentStatus != PLANNED_STATE ||
                workout.tombstonedAtEpochMillis != null ||
                workout.currentWorkoutType == REST_TYPE
            ) {
                return@withTransaction rejected(LocalActivityReviewIssue.WORKOUT_NOT_AVAILABLE)
            }
            if (activityDao.workoutFeedback(workoutId) != null) {
                return@withTransaction rejected(LocalActivityReviewIssue.WORKOUT_ALREADY_HAS_RESULT)
            }
            val linked = activityDao.activitiesLinkedToWorkout(workoutId)
            if (linked.size > 1) {
                return@withTransaction rejected(LocalActivityReviewIssue.AMBIGUOUS_LEDGER_STATE)
            }
            if (linked.isNotEmpty()) {
                return@withTransaction rejected(LocalActivityReviewIssue.WORKOUT_ALREADY_HAS_RESULT)
            }
            val profile = database.profileSettingsDao().get()
                ?: return@withTransaction rejected(LocalActivityReviewIssue.PROFILE_NOT_CONFIGURED)
            val zone = profile.timeZone.asZone()
                ?: return@withTransaction rejected(LocalActivityReviewIssue.PROFILE_NOT_CONFIGURED)
            val activityDate = activity.localDate(zone)
            val workoutDate = LocalDate.ofEpochDay(workout.currentScheduledEpochDay)
            if (
                kotlin.math.abs(ChronoUnit.DAYS.between(activityDate, workoutDate)) >
                ACTIVITY_WORKOUT_MATCH_WINDOW_DAYS
            ) {
                return@withTransaction rejected(LocalActivityReviewIssue.OUTSIDE_MATCH_WINDOW)
            }

            val now = nowEpochMillis()
            val existingFeedback = activityDao.activityFeedback(activity.activityId)
            val feedback = ActivityFeedbackEntity(
                feedbackId = "activity-feedback-${activity.activityId}",
                activityId = activity.activityId,
                feltHard = feltHard ?: existingFeedback?.feltHard ?: false,
                pain = pain ?: existingFeedback?.pain ?: false,
                notes = existingFeedback?.notes,
                recordedAtEpochMillis = now,
            )
            activityDao.saveFeedback(feedback)
            val accepted = activity.copy(
                reviewState = ACTIVITY_REVIEW_STATE_ACCEPTED,
                linkedWorkoutId = workout.workoutId,
                acceptedAtEpochMillis = now,
                updatedAtEpochMillis = now,
                extraPlanImpactConfirmed = false,
            )
            activityDao.saveActivity(accepted)
            val consequence = recordLinkedOutcome(
                activity = accepted,
                workout = workout,
                feedback = feedback,
                now = now,
            )
            LocalActivityReviewResult.Linked(activity.activityId, workout.workoutId, consequence)
        }

    suspend fun confirmAsExtra(
        activityId: String,
        feltHard: Boolean? = null,
        pain: Boolean? = null,
    ): LocalActivityReviewResult =
        database.withTransaction {
            val dao = database.activityLedgerDao()
            val activity = dao.activity(activityId)
                ?: return@withTransaction rejected(LocalActivityReviewIssue.ACTIVITY_NOT_FOUND)
            if (activity.reviewState != REVIEW_STATE) {
                return@withTransaction rejected(LocalActivityReviewIssue.ACTIVITY_ALREADY_RESOLVED)
            }
            val profile = database.profileSettingsDao().get()
                ?: return@withTransaction rejected(LocalActivityReviewIssue.PROFILE_NOT_CONFIGURED)
            val zone = profile.timeZone.asZone()
                ?: return@withTransaction rejected(LocalActivityReviewIssue.PROFILE_NOT_CONFIGURED)
            val now = nowEpochMillis()
            val existingFeedback = dao.activityFeedback(activity.activityId)
            val feedback = ActivityFeedbackEntity(
                feedbackId = "activity-feedback-${activity.activityId}",
                activityId = activity.activityId,
                feltHard = feltHard ?: existingFeedback?.feltHard ?: false,
                pain = pain ?: existingFeedback?.pain ?: false,
                notes = existingFeedback?.notes,
                recordedAtEpochMillis = now,
            )
            dao.saveFeedback(feedback)
            val accepted = activity.copy(
                reviewState = ACTIVITY_REVIEW_STATE_ACCEPTED,
                linkedWorkoutId = null,
                acceptedAtEpochMillis = now,
                updatedAtEpochMillis = now,
                extraPlanImpactConfirmed = true,
            )
            dao.saveActivity(accepted)
            val consequence = recordExtraOutcome(
                activity = accepted,
                feedback = feedback,
                profileZone = zone,
            )
            LocalActivityReviewResult.AcceptedExtra(activity.activityId, consequence)
        }

    /**
     * Returns an accepted, unlinked extra activity to Review without losing its factual record.
     * A claimed consequence or persisted adjustment is immutable plan history and must be reversed
     * through its own boundary before this role can change.
     */
    suspend fun returnExtraToReview(activityId: String): LocalActivityReviewResult =
        database.withTransaction {
            val dao = database.activityLedgerDao()
            val activity = dao.activity(activityId)
                ?: return@withTransaction rejected(LocalActivityReviewIssue.ACTIVITY_NOT_FOUND)
            if (
                activity.reviewState != ACTIVITY_REVIEW_STATE_ACCEPTED ||
                activity.linkedWorkoutId != null ||
                !activity.extraPlanImpactConfirmed
            ) {
                return@withTransaction rejected(LocalActivityReviewIssue.ACTIVITY_NOT_ACCEPTED_EXTRA)
            }
            if (
                dao.activityConsequence(activityId)?.appliedDecision != null ||
                database.adjustmentDao().appliedAdjustmentCountForActivity(activityId) > 0
            ) {
                return@withTransaction rejected(LocalActivityReviewIssue.DERIVED_PLAN_CHANGE_REQUIRES_REVERSAL)
            }
            dao.clearActivityConsequenceOptions(activityId)
            dao.clearUnappliedActivityConsequence(activityId)
            dao.saveActivity(
                activity.copy(
                    reviewState = REVIEW_STATE,
                    acceptedAtEpochMillis = null,
                    extraPlanImpactConfirmed = false,
                    updatedAtEpochMillis = nowEpochMillis(),
                ),
            )
            LocalActivityReviewResult.ReturnedToReview(activityId)
        }

    suspend fun updateFeedback(
        activityId: String,
        feltHard: Boolean,
        pain: Boolean,
        notes: String = "",
    ): LocalActivityReviewResult = database.withTransaction {
        val normalizedNotes = notes.trim()
        if (normalizedNotes.length > MAX_PRIVATE_NOTES_LENGTH) {
            return@withTransaction rejected(LocalActivityReviewIssue.NOTES_TOO_LONG)
        }
        val dao = database.activityLedgerDao()
        val activity = dao.activity(activityId)
            ?: return@withTransaction rejected(LocalActivityReviewIssue.ACTIVITY_NOT_FOUND)
        val accepted = activity.reviewState == ACTIVITY_REVIEW_STATE_ACCEPTED
        val alreadyApplied = when {
            !accepted -> false
            activity.linkedWorkoutId != null -> dao.workoutFeedbackForActivity(activity.activityId)
                ?.let { dao.workoutFeedbackConsequence(it.feedbackId)?.appliedDecision != null } == true
            activity.extraPlanImpactConfirmed -> dao.activityConsequence(activity.activityId)
                ?.appliedDecision != null
            else -> false
        }
        val now = nowEpochMillis()
        val feedback = ActivityFeedbackEntity(
            feedbackId = "activity-feedback-${activity.activityId}",
            activityId = activity.activityId,
            feltHard = feltHard,
            pain = pain,
            notes = normalizedNotes.takeIf(String::isNotEmpty),
            recordedAtEpochMillis = now,
        )
        dao.saveFeedback(feedback)
        // Review is a factual-data boundary: its health feedback remains attached to the record
        // until the runner explicitly accepts a plan role for that activity.
        if (accepted && pain) markCurrentPainIfRecent(activity, now)
        // Preserve an already applied consequence as immutable decision history. Recalculating it
        // would clear its claim and make a second future-plan mutation possible from one activity.
        if (alreadyApplied) {
            return@withTransaction LocalActivityReviewResult.FeedbackUpdated(
                activityId = activity.activityId,
                consequence = null,
                appliedDecisionPreserved = true,
            )
        }

        val consequence = when {
            activity.reviewState != ACTIVITY_REVIEW_STATE_ACCEPTED -> null
            activity.linkedWorkoutId != null -> {
                val workout = database.goalPlanDao().workout(activity.linkedWorkoutId)
                    ?: return@withTransaction rejected(LocalActivityReviewIssue.AMBIGUOUS_LEDGER_STATE)
                recordLinkedOutcome(activity, workout, feedback, now)
            }
            activity.extraPlanImpactConfirmed -> {
                val profile = database.profileSettingsDao().get()
                    ?: return@withTransaction rejected(LocalActivityReviewIssue.PROFILE_NOT_CONFIGURED)
                val zone = profile.timeZone.asZone()
                    ?: return@withTransaction rejected(LocalActivityReviewIssue.PROFILE_NOT_CONFIGURED)
                recordExtraOutcome(activity, feedback, zone)
            }
            else -> null
        }
        LocalActivityReviewResult.FeedbackUpdated(
            activityId = activity.activityId,
            consequence = consequence,
            appliedDecisionPreserved = false,
        )
    }

    suspend fun unlink(activityId: String): LocalActivityReviewResult = database.withTransaction {
        val dao = database.activityLedgerDao()
        val activity = dao.activity(activityId)
            ?: return@withTransaction rejected(LocalActivityReviewIssue.ACTIVITY_NOT_FOUND)
        val workoutId = activity.linkedWorkoutId
            ?: return@withTransaction rejected(LocalActivityReviewIssue.ACTIVITY_NOT_LINKED)
        if (database.adjustmentDao().appliedAdjustmentCountForActivity(activityId) > 0) {
            return@withTransaction rejected(LocalActivityReviewIssue.DERIVED_PLAN_CHANGE_REQUIRES_REVERSAL)
        }
        val workout = database.goalPlanDao().workout(workoutId)
            ?: return@withTransaction rejected(LocalActivityReviewIssue.AMBIGUOUS_LEDGER_STATE)
        dao.deleteWorkoutFeedbackForActivity(activityId)
        database.goalPlanDao().saveWorkout(workout.copy(currentStatus = PLANNED_STATE))
        // A linked manual record is a candidate until the runner explicitly accepts it as extra.
        // Older accepted manual rows are left untouched unless the runner actively unlinks one.
        val remainsAccepted = activity.extraPlanImpactConfirmed
        dao.saveActivity(
            activity.copy(
                reviewState = if (remainsAccepted) ACTIVITY_REVIEW_STATE_ACCEPTED else REVIEW_STATE,
                linkedWorkoutId = null,
                acceptedAtEpochMillis = activity.acceptedAtEpochMillis.takeIf { remainsAccepted },
                updatedAtEpochMillis = nowEpochMillis(),
            ),
        )
        LocalActivityReviewResult.Unlinked(
            activityId = activity.activityId,
            returnedToReview = !remainsAccepted,
        )
    }

    suspend fun delete(activityId: String): LocalActivityReviewResult = database.withTransaction {
        val dao = database.activityLedgerDao()
        val activity = dao.activity(activityId)
            ?: return@withTransaction rejected(LocalActivityReviewIssue.ACTIVITY_NOT_FOUND)
        if (database.adjustmentDao().appliedAdjustmentCountForActivity(activityId) > 0) {
            return@withTransaction rejected(LocalActivityReviewIssue.DERIVED_PLAN_CHANGE_REQUIRES_REVERSAL)
        }
        activity.linkedWorkoutId?.let { workoutId ->
            val workout = database.goalPlanDao().workout(workoutId)
                ?: return@withTransaction rejected(LocalActivityReviewIssue.AMBIGUOUS_LEDGER_STATE)
            dao.deleteWorkoutFeedbackForActivity(activityId)
            database.goalPlanDao().saveWorkout(workout.copy(currentStatus = PLANNED_STATE))
        }
        val now = nowEpochMillis()
        if (activity.source == MANUAL_SOURCE) {
            dao.deleteActivity(activity.activityId)
        } else {
            val sourceIdentity = activity.sourceRecordId
                ?: return@withTransaction rejected(LocalActivityReviewIssue.IMPORT_IDENTITY_MISSING)
            database.importLedgerDao().deleteImportedActivityToTombstone(
                activityId = activity.activityId,
                source = activity.source,
                digest = sourceIdentity,
                tombstonedAtEpochMillis = now,
            )
        }
        LocalActivityReviewResult.Deleted(activity.activityId)
    }

    private suspend fun recordLinkedOutcome(
        activity: ActivityEntity,
        workout: WorkoutEntity,
        feedback: ActivityFeedbackEntity?,
        now: Long,
    ): Consequence {
        val planDao = database.goalPlanDao()
        val activityDao = database.activityLedgerDao()
        val weekWorkouts = planDao.workoutsForWeek(workout.weekId, limit = MAX_WORKOUTS_PER_WEEK_READ)
        val recent = activityDao.workoutFeedbackInPlanDateRange(
            planId = workout.planId,
            fromEpochDay = workout.currentScheduledEpochDay - RECENT_DEVIATION_DAYS,
            beforeEpochDay = workout.currentScheduledEpochDay,
            limit = MAX_RECENT_FEEDBACK_READ,
        )
        val routine = planDao.plan(workout.planId)?.phaseType == ROUTINE_PHASE
        val consequence = if (routine) {
            Consequences.recordRoutine(
                FeedbackStatus.DONE,
                feltHard = feedback?.feltHard == true,
                pain = feedback?.pain == true,
            )
        } else {
            Consequences.calculate(
                FeedbackInput(
                    targetDistanceMeters = workout.currentDistanceMeters ?: 0,
                    targetDurationSeconds = workout.currentDurationSeconds,
                    weekTargetDistanceMeters = weekWorkouts.sumOfCurrentDistance(),
                    completedDistanceMeters = activity.distanceMeters,
                    completedDurationSeconds = activity.durationSeconds,
                    status = FeedbackStatus.DONE,
                    feltHard = feedback?.feltHard == true,
                    pain = feedback?.pain == true,
                    recentSkippedWorkouts = recent.count { it.completionState == "skipped" },
                    recentShortenedWorkouts = recent.count { it.completionState == "shortened" },
                ),
            )
        }
        val completionState = if (!routine && consequence.deviation == Deviation.SHORT) "shortened" else "done"
        val feedbackId = "workout-feedback-${activity.activityId}"
        activityDao.saveWorkoutFeedback(
            WorkoutFeedbackEntity(
                feedbackId = feedbackId,
                workoutId = workout.workoutId,
                completionState = completionState,
                feltHard = feedback?.feltHard == true,
                pain = feedback?.pain == true,
                notes = feedback?.notes,
                recordedAtEpochMillis = now,
                completedDistanceMeters = activity.distanceMeters,
                completedDurationSeconds = activity.durationSeconds,
                sourceActivityId = activity.activityId,
            ),
        )
        activityDao.saveWorkoutFeedbackConsequence(
            consequence.toWorkoutEntity(
                feedbackId = feedbackId,
                currentWeekDistanceMeters = weekWorkouts.sumOfCurrentDistance(),
                currentWeekDurationSeconds = weekWorkouts.sumOfCurrentDuration(),
            ),
        )
        activityDao.clearWorkoutFeedbackConsequenceOptions(feedbackId)
        consequence.options.forEach { decision ->
            activityDao.saveWorkoutFeedbackConsequenceOption(
                WorkoutFeedbackConsequenceOptionEntity(feedbackId, decision.toStorageValue()),
            )
        }
        planDao.saveWorkout(workout.copy(currentStatus = completionState, updatedAtEpochMillis = now))
        if (feedback?.pain == true) markCurrentPainIfRecent(activity, now)
        return consequence
    }

    private suspend fun recordExtraOutcome(
        activity: ActivityEntity,
        feedback: ActivityFeedbackEntity?,
        profileZone: ZoneId,
    ): Consequence {
        val planDao = database.goalPlanDao()
        val activePlans = planDao.activePlans(limit = 2)
        val activityDate = activity.localDate(profileZone)
        val today = Instant.ofEpochMilli(nowEpochMillis()).atZone(profileZone).toLocalDate()
        val activePlan = activePlans.singleOrNull()
        val visible = activePlan?.let {
            planDao.visibleWorkoutsForPlan(it.planId, limit = MAX_PLAN_WORKOUTS_READ)
        }.orEmpty()
        val routine = activePlan?.phaseType == ROUTINE_PHASE
        val next = eligibleFutureDecisionWorkouts(
            candidates = visible,
            originEpochDay = activityDate.toEpochDay(),
            todayEpochDay = today.toEpochDay(),
        ).firstOrNull()
        val weekStart = activityDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekEnd = weekStart.plusDays(6)
        val activityWeek = visible.filter {
            it.currentScheduledEpochDay in weekStart.toEpochDay()..weekEnd.toEpochDay()
        }
        val raw = if (routine) {
            Consequences.recordRoutine(
                FeedbackStatus.DONE,
                feltHard = feedback?.feltHard == true,
                pain = feedback?.pain == true,
            )
        } else {
            calculateExtraActivityConsequence(
                ExtraActivityInput(
                    distanceMeters = activity.distanceMeters ?: 0,
                    durationSeconds = activity.durationSeconds,
                    feltHard = feedback?.feltHard == true,
                    pain = feedback?.pain == true,
                ),
                ExtraActivityTargets(
                    nextRunTargetDistanceMeters = next?.currentDistanceMeters ?: 0,
                    nextRunTargetDurationSeconds = next?.currentDurationSeconds,
                    weekTargetDistanceMeters = activityWeek.sumOfCurrentDistance(),
                    weekTargetDurationSeconds = activityWeek.sumOfCurrentDuration(),
                ),
            )
        }
        val consequence =
            if (!routine && (next == null || isHistoricalExtraActivity(activityDate, today))) {
                historicalExtraActivityReview(raw)
            } else {
                raw
            }
        val dao = database.activityLedgerDao()
        dao.saveActivityConsequence(consequence.toActivityEntity(activity))
        dao.clearActivityConsequenceOptions(activity.activityId)
        consequence.options.forEach { decision ->
            dao.saveActivityConsequenceOption(
                ActivityConsequenceOptionEntity(activity.activityId, decision.toStorageValue()),
            )
        }
        if (feedback?.pain == true) markCurrentPainIfRecent(activity, nowEpochMillis())
        return consequence
    }

    private suspend fun markCurrentPainIfRecent(activity: ActivityEntity, now: Long) {
        val profile = database.profileSettingsDao().get() ?: return
        val zone = profile.timeZone.asZone() ?: return
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        if (!isHistoricalExtraActivity(activity.localDate(zone), today)) {
            markCurrentPain(now)
        }
    }

    private suspend fun markCurrentPain(now: Long) {
        val profileDao = database.profileSettingsDao()
        val profile = profileDao.get() ?: return
        if (!profile.currentPain) {
            profileDao.save(profile.copy(currentPain = true, updatedAtEpochMillis = now))
        }
    }

    private fun rejected(issue: LocalActivityReviewIssue) =
        LocalActivityReviewResult.Rejected(issue)

    private companion object {
        const val REVIEW_STATE = "review"
        const val ACTIVE_STATE = "active"
        const val PLANNED_STATE = "planned"
        const val REST_TYPE = "rest"
        const val ROUTINE_PHASE = "routine"
        const val MANUAL_SOURCE = "manual"
        const val RECENT_DEVIATION_DAYS = 28L
        const val MAX_PRIVATE_NOTES_LENGTH = 240
        const val MAX_RECENT_FEEDBACK_READ = 128
        const val MAX_WORKOUTS_PER_WEEK_READ = 32
        const val MAX_PLAN_WORKOUTS_READ = 1_024
    }
}

sealed interface LocalActivityReviewResult {
    data class Linked(
        val activityId: String,
        val workoutId: String,
        val consequence: Consequence,
    ) : LocalActivityReviewResult

    data class AcceptedExtra(
        val activityId: String,
        val consequence: Consequence,
    ) : LocalActivityReviewResult

    data class FeedbackUpdated(
        val activityId: String,
        val consequence: Consequence?,
        val appliedDecisionPreserved: Boolean,
    ) : LocalActivityReviewResult

    data class Unlinked(
        val activityId: String,
        val returnedToReview: Boolean,
    ) : LocalActivityReviewResult

    data class ReturnedToReview(val activityId: String) : LocalActivityReviewResult

    data class Deleted(val activityId: String) : LocalActivityReviewResult
    data class Rejected(val issue: LocalActivityReviewIssue) : LocalActivityReviewResult
}

enum class LocalActivityReviewIssue {
    ACTIVITY_NOT_FOUND,
    ACTIVITY_ALREADY_RESOLVED,
    ACTIVITY_NOT_ACCEPTED_EXTRA,
    ACTIVITY_NOT_LINKED,
    WORKOUT_NOT_FOUND,
    PLAN_NOT_FOUND,
    PLAN_NOT_ACTIVE,
    WORKOUT_NOT_AVAILABLE,
    WORKOUT_ALREADY_HAS_RESULT,
    OUTSIDE_MATCH_WINDOW,
    PROFILE_NOT_CONFIGURED,
    NOTES_TOO_LONG,
    IMPORT_IDENTITY_MISSING,
    DERIVED_PLAN_CHANGE_REQUIRES_REVERSAL,
    AMBIGUOUS_LEDGER_STATE,
}

private fun String.asZone(): ZoneId? = runCatching { ZoneId.of(this) }.getOrNull()

private fun ActivityEntity.localDate(zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(occurredAtEpochMillis).atZone(zone).toLocalDate()

private fun List<WorkoutEntity>.sumOfCurrentDistance(): Int = sumOf { workout ->
    workout.currentDistanceMeters?.takeIf {
        workout.currentStatus != WORKOUT_STATE_TOMBSTONED && workout.currentWorkoutType != "rest"
    } ?: 0
}

private fun List<WorkoutEntity>.sumOfCurrentDuration(): Int = sumOf { workout ->
    workout.currentDurationSeconds?.takeIf {
        workout.currentStatus != WORKOUT_STATE_TOMBSTONED && workout.currentWorkoutType != "rest"
    } ?: 0
}

private fun Consequence.toWorkoutEntity(
    feedbackId: String,
    currentWeekDistanceMeters: Int,
    currentWeekDurationSeconds: Int,
): WorkoutFeedbackConsequenceEntity {
    val distanceAdjustment =
        nextRunAdjustment?.takeIf { it.metric == LoadMetric.DISTANCE }?.value ?: 0
    val durationAdjustment =
        nextRunAdjustment?.takeIf { it.metric == LoadMetric.DURATION }?.value ?: 0
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
        appliedDecision = appliedDecision?.toStorageValue(),
        comparisonStatus = comparisonStatus,
        planChangeAvailable = planChangeAvailable,
        currentWeekLoadDurationSeconds = currentWeekDurationSeconds,
        projectedWeekLoadDurationSeconds =
            (currentWeekDurationSeconds + durationAdjustment).coerceAtLeast(0),
    )
}

private fun Consequence.toActivityEntity(activity: ActivityEntity): ActivityConsequenceEntity =
    ActivityConsequenceEntity(
        activityId = activity.activityId,
        classification = kind.name.lowercase(),
        distanceDifferenceMeters = actualDifference.takeIf { metric == LoadMetric.DISTANCE },
        durationDifferenceSeconds = actualDifference.takeIf { metric == LoadMetric.DURATION },
        actualLoadMeters = activity.distanceMeters,
        assessment = risk.name.lowercase(),
        recommendedDecision = recommendedDecision.toStorageValue(),
        resolvedAtEpochMillis = appliedDecision?.let { activity.updatedAtEpochMillis },
        deviation = deviation.name.lowercase(),
        loadMetric = metric.name.lowercase(),
        risk = risk.name.lowercase(),
        appliedDecision = appliedDecision?.toStorageValue(),
        comparisonStatus = comparisonStatus,
        planChangeAvailable = planChangeAvailable,
        actualLoadDurationSeconds = activity.durationSeconds,
    )
