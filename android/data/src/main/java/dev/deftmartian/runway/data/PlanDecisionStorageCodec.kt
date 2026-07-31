package dev.deftmartian.runway.data

import dev.deftmartian.runway.domain.PlanDecision

internal fun PlanDecision.toStorageValue(): String =
    when (this) {
        PlanDecision.KEEP_PLAN -> "keep_plan"
        PlanDecision.REDUCE_NEXT -> "reduce_next"
        PlanDecision.NEXT_REST -> "next_rest"
        PlanDecision.REPEAT_PRESCRIPTION -> "repeat_prescription"
        PlanDecision.REBALANCE_WEEK -> "rebalance_week"
    }

internal fun String.toStoredPlanDecision(): PlanDecision? =
    PlanDecision.entries.firstOrNull { it.toStorageValue() == this }
