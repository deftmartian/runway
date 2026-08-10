package dev.deftmartian.runway.domain

enum class RaceDistance(val meters: Int) {
    FIVE_K(5_000),
    TEN_K(10_000),
    HALF(21_100),
    MARATHON(42_200),
    ;

    companion object {
        /**
         * Accepts canonical values and the half/marathon values persisted by released builds.
         * Callers retain the stored row unchanged and use the returned typed value for planning.
         */
        fun fromStoredMeters(meters: Int?): RaceDistance? = when (meters) {
            FIVE_K.meters -> FIVE_K
            TEN_K.meters -> TEN_K
            HALF.meters, 21_097 -> HALF
            MARATHON.meters, 42_195 -> MARATHON
            else -> null
        }
    }
}
enum class GoalKind { RACE, ROUTINE }
enum class PlanPhase { DISTANCE, CALIBRATION, ROUTINE }
enum class StartMode { ESTABLISHED, CALIBRATION, ROUTINE }
enum class GoalPriority { FINISH_HEALTHY, CONSISTENCY }
enum class RiskRating { CONSERVATIVE, MODERATE, AGGRESSIVE, UNSAFE }
enum class WorkoutType { EASY, LONG, RECOVERY, REST, RACE }
enum class SegmentKind { RUN, WALK }

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
    /** A scheduled run with no prescribed amount. Actual distance and duration remain optional observations. */
    data object Open : WorkoutPrescription
    data object Rest : WorkoutPrescription
}

sealed interface PlannerIntake

data class EstablishedTrainingIntake(
    val priority: GoalPriority,
    val availability: List<Int>,
    val raceDistance: RaceDistance,
    val targetDate: String,
    val currentWeeklyDistanceMeters: Int,
    val currentRunsPerWeek: Int,
    val longestRecentRunMeters: Int,
    val preferredLongRunDay: Int,
    val startDate: String? = null
) : PlannerIntake

data class CalibrationIntake(
    val goalKind: GoalKind,
    val raceDistance: RaceDistance?,
    val availability: List<Int>,
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

data class CalibrationSummary(
    val programWeeks: Int = 2,
    val sessionsPerWeek: Int = 2,
    val sessionDurationSeconds: Int? = null,
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
data class GeneratedCalibrationPlan(override val startDate: String, override val targetDate: String, override val weeks: List<GeneratedWeek>, override val risk: RiskRating, val summary: CalibrationSummary, override val sourceRefs: List<String>) : GeneratedPlan { override val phase = PlanPhase.CALIBRATION }

data class PhaseBaseline(val activityCount: Int, val totalDurationSeconds: Int, val totalDistanceMeters: Int, val longestActivityMeters: Int, val weeklyDistanceMeters: Int, val runsPerWeek: Double)
data class BaselineObservation(val distanceMeters: Int?, val durationSeconds: Int?, val completed: Boolean)
enum class PhaseTransitionOption { CONFIRM_RACE_BASELINE, CONTINUE_CALIBRATION, LATER_DATE, SHORTER_GOAL }
data class PhaseTransition(val recommended: PhaseTransitionOption, val options: List<PhaseTransitionOption>)

/** Stable source identifiers from docs/TRAINING_SOURCES.md; source text does not confer medical authority. */
object TrainingSourceRefs {
    const val MAYO_INJURY_AVOIDANCE = "mayo-running-injury-avoidance"
    const val MAYO_TAPER = "mayo-taper-guidance"
    const val REI_5K = "rei-5k-training"
    const val REI_10K = "rei-10k-training"
    const val REI_HALF = "rei-half-marathon-training"
    const val REI_MARATHON = "rei-marathon-training"
    const val RRCA = "rrca-runner-guidance"
}
