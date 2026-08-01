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
    else -> NativeAssessmentPresentation("Recorded", "This plan is saved without a weekly increase rating.")
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
            "More information needed",
            "Add the run duration before runway compares it with this timed plan.",
        )
    } else
    when (kind) {
        "pain_reported" -> NativeAssessmentPresentation(
            "Pain reported",
            "Pain was reported for this run. The schedule is not medical clearance to keep running.",
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
            "Felt harder than planned",
            "You completed the planned amount but marked the effort as hard. Review the next run before changing it.",
        )
        "shortfall" -> NativeAssessmentPresentation(
            "Shorter than planned",
            "This run was recorded below its planned amount; review the next planned run.",
        )
        "repeated_shortfall" -> NativeAssessmentPresentation(
            "Several runs changed",
            "More than one recent run was shortened or skipped; review the next planned run.",
        )
        "skip_continue", "skip_reduce" -> NativeAssessmentPresentation(
            "Skipped run",
            "This planned run was skipped; review the next planned run.",
        )
        "repeated_skip", "repeated_miss" -> NativeAssessmentPresentation(
            "Several skipped runs",
            "More than one recent planned run was skipped; review the next planned run.",
        )
        "load_spike" -> NativeAssessmentPresentation(
            "More than planned",
            "The recorded amount was higher than the planned amount.",
        )
        else -> nativeLoadAssessment(risk).copy(
            label = "Run outside the schedule",
            description = "This run was not on the schedule, so its training load is reviewed separately.",
        )
    }
