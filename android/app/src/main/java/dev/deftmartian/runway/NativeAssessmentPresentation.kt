package dev.deftmartian.runway

internal data class NativeAssessmentPresentation(
    val label: String,
    val description: String,
)

internal fun nativeRampAssessment(risk: String?): NativeAssessmentPresentation = when (risk) {
    "conservative" -> NativeAssessmentPresentation(
        "Within runway’s conservative default",
        "The calculated increase stays within runway’s default ramp.",
    )
    "moderate" -> NativeAssessmentPresentation(
        "Above default",
        "The calculated increase is above runway’s default ramp.",
    )
    "aggressive" -> NativeAssessmentPresentation(
        "High increase",
        "The calculated increase is well above runway’s default ramp.",
    )
    "unsafe" -> NativeAssessmentPresentation(
        "Unsupported",
        "The calculated increase is outside runway’s plan-generation limits.",
    )
    else -> NativeAssessmentPresentation("Recorded", "The plan assessment is recorded.")
}

internal fun nativeLoadAssessment(risk: String?): NativeAssessmentPresentation = when (risk) {
    "conservative" -> NativeAssessmentPresentation(
        "Within runway’s conservative default",
        "This change stays within runway’s default load-change range.",
    )
    "moderate" -> NativeAssessmentPresentation(
        "Above default",
        "This change is above runway’s default load-change range.",
    )
    "aggressive" -> NativeAssessmentPresentation(
        "High change",
        "This change adds a high share of the week’s planned load.",
    )
    "unsafe" -> NativeAssessmentPresentation(
        "Outside default",
        "This change is outside runway’s default range and needs explicit confirmation.",
    )
    else -> NativeAssessmentPresentation("Needs review", "Review this change before applying it.")
}

internal fun nativeConsequenceAssessment(
    kind: String?,
    risk: String?,
    comparisonStatus: String? = null,
): NativeAssessmentPresentation =
    if (comparisonStatus == "not_comparable") {
        NativeAssessmentPresentation(
            "Needs review",
            "This timed plan needs recorded duration before its load can be compared.",
        )
    } else
    when (kind) {
        "pain_reported" -> NativeAssessmentPresentation(
            "Pain review",
            "Pain was reported, so health guidance stays separate from load arithmetic.",
        )
        "completed_as_planned" -> NativeAssessmentPresentation(
            "Recorded as planned",
            "The recorded amount is within the material threshold for this workout.",
        )
        "historical_link" -> NativeAssessmentPresentation(
            "Historical record",
            "This activity is linked for history and does not change the current plan.",
        )
        "hard_effort" -> NativeAssessmentPresentation(
            "Hard-effort review",
            "The planned amount was recorded, but the reported effort changes the next-workout advice.",
        )
        "shortfall" -> NativeAssessmentPresentation(
            "Shortfall review",
            "This workout was recorded below its planned amount; review the next prescription.",
        )
        "repeated_shortfall" -> NativeAssessmentPresentation(
            "Repeated-deviation review",
            "More than one recent workout was shortened or skipped; review the next prescription.",
        )
        "skip_continue", "skip_reduce" -> NativeAssessmentPresentation(
            "Skipped-run review",
            "This planned run was skipped; review the next prescription.",
        )
        "repeated_skip", "repeated_miss" -> NativeAssessmentPresentation(
            "Repeated-skip review",
            "More than one recent planned run was skipped; review the next prescription.",
        )
        "load_spike" -> NativeAssessmentPresentation(
            "Extra-load review",
            "The recorded amount exceeded this workout prescription.",
        )
        else -> nativeLoadAssessment(risk).copy(
            label = "Unplanned-run review",
            description = "This run was not prescribed, so its recorded load is reviewed separately.",
        )
    }
