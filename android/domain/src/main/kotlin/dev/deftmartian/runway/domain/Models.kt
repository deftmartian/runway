package dev.deftmartian.runway.domain

enum class RaceDistance { FIVE_K, TEN_K, HALF, MARATHON }
enum class GoalKind { RACE, FOUNDATION }
enum class PlanPhase { DISTANCE, FOUNDATION, CALIBRATION }
enum class StartMode { ESTABLISHED, FOUNDATION_TO_GOAL, FOUNDATION_ONLY, CALIBRATION }
enum class GoalPriority { FINISH_HEALTHY, CONSISTENCY }
enum class Experience { NEW, RETURNING, COMFORTABLE }
enum class RiskRating { CONSERVATIVE, MODERATE, AGGRESSIVE, UNSAFE }
enum class WorkoutType { EASY, LONG, RECOVERY, REST, RACE }
enum class SegmentKind { RUN, WALK }

data class InjuryFlags(
    val recentInjury: Boolean = false,
    val currentPain: Boolean = false,
    val recurringPain: Boolean = false,
    val medicalRestriction: Boolean = false,
    /** Private context is retained but intentionally never interpreted by planner arithmetic. */
    val notes: String = ""
)

data class PrescriptionSegment(
    val kind: SegmentKind,
    val durationSeconds: Int,
)

data class RunWalkBlock(
    val repetitions: Int,
    val segments: List<PrescriptionSegment>,
)
sealed interface WorkoutPrescription {
    data class Distance(val distanceMeters: Int) : WorkoutPrescription
    data class Timed(
        val totalDurationSeconds: Int,
        val warmupSeconds: Int,
        val cooldownSeconds: Int,
        val blocks: List<RunWalkBlock>
    ) : WorkoutPrescription
    data object Rest : WorkoutPrescription
}

sealed interface PlannerIntake

data class EstablishedTrainingIntake(
    val priority: GoalPriority,
    val experience: Experience,
    val availability: List<Int>,
    val injuryFlags: InjuryFlags,
    val raceDistance: RaceDistance,
    val targetDate: String,
    val currentWeeklyDistanceMeters: Int,
    val currentRunsPerWeek: Int,
    val longestRecentRunMeters: Int,
    val preferredLongRunDay: Int,
    val startDate: String? = null
) : PlannerIntake

data class FoundationIntake(
    val startMode: StartMode,
    val goalKind: GoalKind,
    val raceDistance: RaceDistance?,
    val availability: List<Int>,
    val injuryFlags: InjuryFlags,
    val startDate: String? = null
) : PlannerIntake {
    init {
        require(startMode == StartMode.FOUNDATION_TO_GOAL || startMode == StartMode.FOUNDATION_ONLY)
    }
}

data class CalibrationIntake(
    val goalKind: GoalKind,
    val raceDistance: RaceDistance?,
    val availability: List<Int>,
    val injuryFlags: InjuryFlags,
    val calibrationDurationSeconds: Int,
    val startDate: String? = null
) : PlannerIntake

data class GeneratedWorkout(
    val scheduledDate: String,
    val type: WorkoutType,
    val targetDistanceMeters: Int,
    val prescription: WorkoutPrescription,
    val intensity: String,
    val purpose: String,
    val reason: String,
    val sourceRefs: List<String>,
    val targetDurationSeconds: Int? = null
)

data class GeneratedWeek(
    val weekNumber: Int,
    val startDate: String,
    val trainingTargetDistanceMeters: Int,
    val eventDistanceMeters: Int,
    val targetDistanceMeters: Int,
    val targetDurationSeconds: Int,
    val longRunMeters: Int,
    val risk: RiskRating,
    val isDownWeek: Boolean,
    val isTaper: Boolean,
    val workouts: List<GeneratedWorkout>
)

sealed interface PlanSummary {
    val warnings: List<String>
}

data class DistanceSummary(
    val baselineMeters: Int,
    val peakMeters: Int,
    val requiredWeeklyIncreasePercent: Double,
    val defaultWeeklyIncreasePercent: Double,
    val longRunPeakMeters: Int,
    override val warnings: List<String>,
) : PlanSummary

data class FoundationSummary(
    val programWeeks: Int = 9,
    val sessionsPerWeek: Int = 3,
    val continuousRunTargetSeconds: Int = 1800,
    override val warnings: List<String>,
) : PlanSummary

data class CalibrationSummary(
    val programWeeks: Int = 2,
    val sessionsPerWeek: Int = 2,
    val sessionDurationSeconds: Int,
    override val warnings: List<String>,
) : PlanSummary

sealed interface GeneratedPlan {
    val phase: PlanPhase
    val startDate: String
    val targetDate: String
    val weeks: List<GeneratedWeek>
    val risk: RiskRating
    val sourceRefs: List<String>
}
data class GeneratedDistancePlan(override val startDate: String, override val targetDate: String, override val weeks: List<GeneratedWeek>, override val risk: RiskRating, val summary: DistanceSummary, override val sourceRefs: List<String>) : GeneratedPlan { override val phase = PlanPhase.DISTANCE }
data class GeneratedFoundationPlan(override val startDate: String, override val targetDate: String, override val weeks: List<GeneratedWeek>, override val risk: RiskRating, val startMode: StartMode, val summary: FoundationSummary, override val sourceRefs: List<String>) : GeneratedPlan { override val phase = PlanPhase.FOUNDATION }
data class GeneratedCalibrationPlan(override val startDate: String, override val targetDate: String, override val weeks: List<GeneratedWeek>, override val risk: RiskRating, val summary: CalibrationSummary, override val sourceRefs: List<String>) : GeneratedPlan { override val phase = PlanPhase.CALIBRATION }

data class PhaseBaseline(val activityCount: Int, val totalDurationSeconds: Int, val totalDistanceMeters: Int, val longestActivityMeters: Int, val weeklyDistanceMeters: Int, val runsPerWeek: Double)
data class BaselineObservation(val distanceMeters: Int?, val durationSeconds: Int?, val completed: Boolean)
enum class PhaseTransitionOption { CONFIRM_RACE_BASELINE, ANOTHER_FOUNDATION_WEEK, CONTINUE_CALIBRATION, LATER_DATE, SHORTER_GOAL }
data class PhaseTransition(val recommended: PhaseTransitionOption, val options: List<PhaseTransitionOption>)

/** Stable source identifiers from docs/TRAINING_SOURCES.md; source text does not confer medical authority. */
object TrainingSourceRefs {
    const val MAYO_INJURY_AVOIDANCE = "mayo-running-injury-avoidance"
    const val MAYO_BEGINNER_RUN_WALK = "mayo-beginner-run-walk"
    const val NHS_COUCH_TO_5K = "nhs-couch-to-5k"
    const val MAYO_TAPER = "mayo-taper-guidance"
    const val REI_5K = "rei-5k-training"
    const val REI_10K = "rei-10k-training"
    const val REI_HALF = "rei-half-marathon-training"
    const val REI_MARATHON = "rei-marathon-training"
    const val RRCA = "rrca-runner-guidance"
    const val NIAMS = "niams-sports-injury"
}
