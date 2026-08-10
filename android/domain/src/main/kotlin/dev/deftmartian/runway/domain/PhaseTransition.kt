package dev.deftmartian.runway.domain

object PhaseTransitions {
    fun deriveBaseline(observations: List<BaselineObservation>, weeksObserved: Double): PhaseBaseline {
        require(weeksObserved.isFinite() && weeksObserved > 0) { "Observed week count must be positive." }
        val completed = observations.filter { it.completed }
        fun nonnegative(value: Int?) = value?.takeIf { it > 0 } ?: 0
        val distance = completed.sumOf { nonnegative(it.distanceMeters) }
        return PhaseBaseline(
            completed.size,
            completed.sumOf { nonnegative(it.durationSeconds) },
            distance,
            completed.maxOfOrNull { nonnegative(it.distanceMeters) } ?: 0,
            roundTrainingValueToInt(distance / weeksObserved),
            roundTrainingValueToOneDecimal(completed.size / weeksObserved),
        )
    }
    fun canUseDistancePlannerBaseline(baseline: PhaseBaseline) = baseline.weeklyDistanceMeters >= 3000 && baseline.runsPerWeek >= 2 && baseline.longestActivityMeters > 0
    fun options(phase: PlanPhase, goalKind: GoalKind, baseline: PhaseBaseline, raceRampSupported: Boolean): PhaseTransition {
        require(phase == PlanPhase.CALIBRATION) {
            "Only a completed baseline-learning phase has a phase transition."
        }
        require(goalKind == GoalKind.RACE) { "Only distance goals use a baseline transition." }
        val continuation = PhaseTransitionOption.CONTINUE_CALIBRATION
        return if (canUseDistancePlannerBaseline(baseline) && raceRampSupported) PhaseTransition(PhaseTransitionOption.CONFIRM_RACE_BASELINE, listOf(PhaseTransitionOption.CONFIRM_RACE_BASELINE, continuation, PhaseTransitionOption.LATER_DATE, PhaseTransitionOption.SHORTER_GOAL)) else PhaseTransition(continuation, listOf(continuation, PhaseTransitionOption.LATER_DATE, PhaseTransitionOption.SHORTER_GOAL))
    }
}
