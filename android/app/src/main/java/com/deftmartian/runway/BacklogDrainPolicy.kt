package com.deftmartian.runway

internal fun nextBacklogDrainBudget(currentBudget: Int, remainingCandidates: Int): Int? {
    if (remainingCandidates <= 0) return null
    return (currentBudget - 1).takeIf { it > 0 }
}
