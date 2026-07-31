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
        require(phase != PlanPhase.DISTANCE) { "Distance plans do not have a phase transition." }
        val continuation = if (phase == PlanPhase.FOUNDATION) PhaseTransitionOption.ANOTHER_FOUNDATION_WEEK else PhaseTransitionOption.CONTINUE_CALIBRATION
        if (goalKind == GoalKind.FOUNDATION) return PhaseTransition(continuation, listOf(continuation))
        return if (canUseDistancePlannerBaseline(baseline) && raceRampSupported) PhaseTransition(PhaseTransitionOption.CONFIRM_RACE_BASELINE, listOf(PhaseTransitionOption.CONFIRM_RACE_BASELINE, continuation, PhaseTransitionOption.LATER_DATE, PhaseTransitionOption.SHORTER_GOAL)) else PhaseTransition(continuation, listOf(continuation, PhaseTransitionOption.LATER_DATE, PhaseTransitionOption.SHORTER_GOAL))
    }
}
