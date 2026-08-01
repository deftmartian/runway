package dev.deftmartian.runway.data

import dev.deftmartian.runway.domain.GeneratedPlan
import dev.deftmartian.runway.domain.GoalKind
import dev.deftmartian.runway.domain.GoalPriority
import dev.deftmartian.runway.domain.PlanPhase
import dev.deftmartian.runway.domain.RiskRating
import dev.deftmartian.runway.domain.StartMode
import dev.deftmartian.runway.domain.WorkoutPrescription
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Immutable records required to give a generated domain plan a local identity and goal context.
 * IDs and timestamps are caller supplied so repeated mapping is deterministic and persistence does
 * not invent a second goal or plan.
 */
data class GeneratedPlanGoalMetadata(
    val goalId: String,
    val planId: String,
    val title: String,
    val goalKind: GoalKind,
    val startMode: StartMode,
    /** Race-goal date; phase plans have their own generated end date. */
    val goalTargetDate: String? = null,
    val targetDistanceMeters: Int? = null,
    val priority: GoalPriority? = null,
    val goalState: String = "active",
    val planState: String = "active",
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long = createdAtEpochMillis,
)

/** All rows needed to persist a newly generated plan atomically through the ledger DAOs. */
data class GeneratedPlanPersistenceGraph(
    val goal: GoalEntity,
    val plan: PlanEntity,
    val weeks: List<PlanWeekEntity>,
    val workouts: List<WorkoutEntity>,
    val blocks: List<WorkoutBlockEntity>,
    val segments: List<WorkoutSegmentEntity>,
    val planSummaryWarnings: List<PlanSummaryWarningEntity>,
    val planSourceReferences: List<PlanSourceReferenceEntity>,
    val workoutSourceReferences: List<WorkoutSourceReferenceEntity>,
)

object GeneratedPlanPersistenceMapper {
    fun map(plan: GeneratedPlan, metadata: GeneratedPlanGoalMetadata): GeneratedPlanPersistenceGraph {
        val targetEpochDay = epochDay(plan.targetDate)
        val goal = GoalEntity(
            goalId = metadata.goalId,
            title = metadata.title,
            targetDateEpochDay = metadata.goalTargetDate?.let(::epochDay),
            state = metadata.goalState,
            createdAtEpochMillis = metadata.createdAtEpochMillis,
            updatedAtEpochMillis = metadata.updatedAtEpochMillis,
            kind = metadata.goalKind.storageName(),
            startMode = metadata.startMode.storageName(),
            raceDistanceMeters = metadata.targetDistanceMeters,
            priority = metadata.priority.storageValue(),
        )
        val persistedPlan = PlanEntity(
            planId = metadata.planId,
            goalId = metadata.goalId,
            phaseType = plan.phase.storageName(),
            state = metadata.planState,
            startEpochDay = epochDay(plan.startDate),
            endEpochDay = targetEpochDay,
            createdAtEpochMillis = metadata.createdAtEpochMillis,
            updatedAtEpochMillis = metadata.updatedAtEpochMillis,
            riskAssessment = plan.risk.storageName(),
            summaryBaselineMeters = (plan as? dev.deftmartian.runway.domain.GeneratedDistancePlan)?.summary?.baselineMeters,
            summaryPeakMeters = (plan as? dev.deftmartian.runway.domain.GeneratedDistancePlan)?.summary?.peakMeters,
            summaryRequiredWeeklyIncreasePercent = (plan as? dev.deftmartian.runway.domain.GeneratedDistancePlan)?.summary?.requiredWeeklyIncreasePercent,
            summaryDefaultWeeklyIncreasePercent = (plan as? dev.deftmartian.runway.domain.GeneratedDistancePlan)?.summary?.defaultWeeklyIncreasePercent,
            summaryLongRunPeakMeters = (plan as? dev.deftmartian.runway.domain.GeneratedDistancePlan)?.summary?.longRunPeakMeters,
            summaryProgramWeeks = when (plan) { is dev.deftmartian.runway.domain.GeneratedFoundationPlan -> plan.summary.programWeeks; is dev.deftmartian.runway.domain.GeneratedCalibrationPlan -> plan.summary.programWeeks; else -> null },
            summarySessionsPerWeek = when (plan) { is dev.deftmartian.runway.domain.GeneratedFoundationPlan -> plan.summary.sessionsPerWeek; is dev.deftmartian.runway.domain.GeneratedCalibrationPlan -> plan.summary.sessionsPerWeek; else -> null },
            summaryContinuousRunTargetSeconds = (plan as? dev.deftmartian.runway.domain.GeneratedFoundationPlan)?.summary?.continuousRunTargetSeconds,
            summarySessionDurationSeconds = (plan as? dev.deftmartian.runway.domain.GeneratedCalibrationPlan)?.summary?.sessionDurationSeconds,
        )
        val weeks = plan.weeks.map { week ->
            PlanWeekEntity(
                weekId = stableId("week", metadata.planId, week.weekNumber.toString()),
                planId = metadata.planId,
                ordinal = week.weekNumber,
                startEpochDay = epochDay(week.startDate),
                generatedLoadMeters = week.targetDistanceMeters,
                generatedLoadDurationSeconds = week.targetDurationSeconds,
                riskAssessment = week.risk.storageName(),
                isDownWeek = week.isDownWeek,
                isTaperWeek = week.isTaper,
                eventName = week.workouts.firstOrNull { it.type.name == "RACE" }?.purpose,
                eventEpochDay = week.workouts.firstOrNull { it.type.name == "RACE" }
                    ?.scheduledDate?.let(::epochDay),
                generatedLongRunDistanceMeters = week.longRunMeters,
            )
        }
        val rows = plan.weeks.flatMap { week ->
            val weekId = stableId("week", metadata.planId, week.weekNumber.toString())
            week.workouts.mapIndexed { position, workout ->
                val workoutId = stableId(
                    "workout",
                    metadata.planId,
                    week.weekNumber.toString(),
                    position.toString(),
                    workout.scheduledDate,
                )
                PersistedWorkout(workoutId, weekId, position, workout)
            }
        }
        val workouts = rows.map { row -> row.toEntity(metadata.planId, metadata.updatedAtEpochMillis) }
        val blocks = mutableListOf<WorkoutBlockEntity>()
        val segments = mutableListOf<WorkoutSegmentEntity>()
        rows.forEach { row ->
            val timed = row.workout.prescription as? WorkoutPrescription.Timed ?: return@forEach
            listOf("generated", "current").forEach { version ->
                timed.blocks.forEachIndexed { blockOrdinal, block ->
                    val blockId = stableId("block", row.workoutId, version, blockOrdinal.toString())
                    blocks += WorkoutBlockEntity(
                        blockId = blockId,
                        workoutId = row.workoutId,
                        prescriptionVersion = version,
                        ordinal = blockOrdinal,
                        blockType = "timed",
                        repetitions = block.repetitions,
                    )
                    block.segments.forEachIndexed { segmentOrdinal, segment ->
                        segments += WorkoutSegmentEntity(
                            segmentId = stableId("segment", blockId, segmentOrdinal.toString()),
                            blockId = blockId,
                            ordinal = segmentOrdinal,
                            segmentType = segment.kind.name.lowercase(),
                            targetDistanceMeters = null,
                            targetDurationSeconds = segment.durationSeconds,
                        )
                    }
                }
            }
        }
        return GeneratedPlanPersistenceGraph(
            goal = goal,
            plan = persistedPlan,
            weeks = weeks,
            workouts = workouts,
            blocks = blocks,
            segments = segments,
            planSummaryWarnings = plan.summaryWarnings().mapIndexed { ordinal, warning ->
                PlanSummaryWarningEntity(stableId("plan-warning", metadata.planId, ordinal.toString(), warning), metadata.planId, ordinal, warning)
            },
            planSourceReferences = plan.sourceRefs.mapIndexed { ordinal, source ->
                PlanSourceReferenceEntity(
                    referenceId = stableId("plan-source", metadata.planId, ordinal.toString(), source),
                    planId = metadata.planId,
                    ordinal = ordinal,
                    sourceName = source,
                    sourceLocator = source,
                )
            },
            workoutSourceReferences = rows.flatMap { row ->
                row.workout.sourceRefs.flatMapIndexed { ordinal, source ->
                    listOf("generated", "current").map { version ->
                        WorkoutSourceReferenceEntity(
                            referenceId = stableId("workout-source", row.workoutId, version, ordinal.toString(), source),
                            workoutId = row.workoutId,
                            prescriptionVersion = version,
                            ordinal = ordinal,
                            sourceName = source,
                            sourceLocator = source,
                        )
                    }
                }
            },
        )
    }

    private data class PersistedWorkout(
        val workoutId: String,
        val weekId: String,
        val position: Int,
        val workout: dev.deftmartian.runway.domain.GeneratedWorkout,
    ) {
        fun toEntity(planId: String, updatedAtEpochMillis: Long): WorkoutEntity {
            val timed = workout.prescription as? WorkoutPrescription.Timed
            val kind = when (workout.prescription) {
                is WorkoutPrescription.Distance -> "distance"
                is WorkoutPrescription.Timed -> "timed"
                WorkoutPrescription.Open -> "open"
                WorkoutPrescription.Rest -> "rest"
            }
            val storedDistance = workout.targetDistanceMeters
                .takeUnless { workout.prescription in setOf(WorkoutPrescription.Open, WorkoutPrescription.Rest) }
            return WorkoutEntity(
                workoutId = workoutId,
                planId = planId,
                weekId = weekId,
                position = position,
                generatedPurpose = workout.purpose,
                generatedDistanceMeters = storedDistance,
                generatedDurationSeconds = workout.targetDurationSeconds,
                currentPurpose = workout.purpose,
                currentDistanceMeters = storedDistance,
                currentDurationSeconds = workout.targetDurationSeconds,
                tombstonedAtEpochMillis = null,
                updatedAtEpochMillis = updatedAtEpochMillis,
                generatedScheduledEpochDay = epochDay(workout.scheduledDate),
                currentScheduledEpochDay = epochDay(workout.scheduledDate),
                generatedWorkoutType = workout.type.name.lowercase(),
                currentWorkoutType = workout.type.name.lowercase(),
                generatedPrescriptionKind = kind,
                currentPrescriptionKind = kind,
                generatedIntensity = workout.intensity,
                currentIntensity = workout.intensity,
                generatedReason = workout.reason,
                currentReason = workout.reason,
                currentStatus = "planned",
                generatedWarmupSeconds = timed?.warmupSeconds,
                generatedCooldownSeconds = timed?.cooldownSeconds,
                currentWarmupSeconds = timed?.warmupSeconds,
                currentCooldownSeconds = timed?.cooldownSeconds,
            )
        }
    }

    private fun epochDay(value: String): Long = LocalDate.parse(value).toEpochDay()

    private fun stableId(prefix: String, vararg parts: String): String {
        val payload = parts.joinToString(separator = "\u0000")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return "$prefix-$digest"
    }

    private fun GoalKind.storageName(): String = name.lowercase()
    private fun StartMode.storageName(): String = name.lowercase()
    private fun PlanPhase.storageName(): String = name.lowercase()
    private fun RiskRating.storageName(): String = name.lowercase()
    private fun GoalPriority?.storageValue(): String = when (this) {
        GoalPriority.FINISH_HEALTHY -> "finish_healthy"
        GoalPriority.CONSISTENCY -> "consistency"
        null -> "finish_healthy"
    }
    private fun GeneratedPlan.summaryWarnings(): List<String> = when (this) {
        is dev.deftmartian.runway.domain.GeneratedDistancePlan -> summary.warnings
        is dev.deftmartian.runway.domain.GeneratedFoundationPlan -> summary.warnings
        is dev.deftmartian.runway.domain.GeneratedCalibrationPlan -> summary.warnings
    }
}
