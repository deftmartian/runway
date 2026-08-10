package dev.deftmartian.runway.domain

enum class TrainingAssessment {
    WITHIN_DEFAULT,
    ABOVE_DEFAULT,
    HIGH_INCREASE,
    UNSUPPORTED,
    NEEDS_REVIEW,
}

enum class TrainingAssessmentAttention { NONE, REVIEW, HIGH, BLOCKED }

data class TrainingAssessmentPresentation(
    val assessment: TrainingAssessment,
    val label: String,
    val description: String,
    val attention: TrainingAssessmentAttention,
)

object TrainingAssessments {
    fun fromRisk(risk: RiskRating) = when (risk) {
        RiskRating.CONSERVATIVE -> TrainingAssessment.WITHIN_DEFAULT
        RiskRating.MODERATE -> TrainingAssessment.ABOVE_DEFAULT
        RiskRating.AGGRESSIVE -> TrainingAssessment.HIGH_INCREASE
        RiskRating.UNSAFE -> TrainingAssessment.UNSUPPORTED
    }

    fun presentRamp(risk: RiskRating): TrainingAssessmentPresentation = present(fromRisk(risk), true)

    fun presentLoadChange(risk: RiskRating): TrainingAssessmentPresentation = present(fromRisk(risk), false)

    fun presentMixedPrescription() = TrainingAssessmentPresentation(
        TrainingAssessment.NEEDS_REVIEW,
        "Mixed prescriptions",
        "This plan contains distance and timed work. Review each prescription separately; runway does not convert between them.",
        TrainingAssessmentAttention.REVIEW,
    )

    fun formatRampEvidence(required: Double, default: Double? = null): String {
        val value = percent(required)
        return if (default == null) {
            "$value needed each week"
        } else {
            "$value needed each week · ${percent(default)} runway default"
        }
    }

    fun formatLoadChangeEvidence(change: Double, risk: RiskRating): String = when (risk) {
        RiskRating.UNSAFE -> "${percent(change)} of weekly load; outside-default boundary 25%."
        RiskRating.AGGRESSIVE -> "${percent(change)} of weekly load; high-change boundary 15%."
        else -> "${percent(change)} of weekly load; default up to 10%."
    }

    private fun present(assessment: TrainingAssessment, ramp: Boolean): TrainingAssessmentPresentation {
        if (assessment == TrainingAssessment.NEEDS_REVIEW) {
            error("Numeric risk required")
        }
        return TrainingAssessmentPresentation(
            assessment,
            assessmentLabel(assessment, ramp),
            assessmentDescription(assessment, ramp),
            assessmentAttention(assessment, ramp),
        )
    }

    private fun assessmentLabel(assessment: TrainingAssessment, ramp: Boolean): String = when (assessment) {
        TrainingAssessment.WITHIN_DEFAULT -> "Within default"
        TrainingAssessment.ABOVE_DEFAULT -> "Above default"
        TrainingAssessment.HIGH_INCREASE -> if (ramp) "High increase" else "High change"
        TrainingAssessment.UNSUPPORTED -> if (ramp) "Unsupported" else "Outside default"
        TrainingAssessment.NEEDS_REVIEW -> error("Numeric risk required")
    }

    private fun assessmentDescription(assessment: TrainingAssessment, ramp: Boolean): String =
        if (ramp) rampDescription(assessment) else loadChangeDescription(assessment)

    private fun rampDescription(assessment: TrainingAssessment): String = when (assessment) {
        TrainingAssessment.WITHIN_DEFAULT -> "The calculated increase stays within runway's default ramp."
        TrainingAssessment.ABOVE_DEFAULT -> "The calculated increase is above runway's default ramp."
        TrainingAssessment.HIGH_INCREASE -> "The calculated increase is well above runway's default ramp."
        TrainingAssessment.UNSUPPORTED -> "The calculated increase is outside runway's plan-generation limits."
        TrainingAssessment.NEEDS_REVIEW -> error("Numeric risk required")
    }

    private fun loadChangeDescription(assessment: TrainingAssessment): String = when (assessment) {
        TrainingAssessment.WITHIN_DEFAULT -> "This change stays within runway's default load-change range."
        TrainingAssessment.ABOVE_DEFAULT -> "This change is above runway's default load-change range."
        TrainingAssessment.HIGH_INCREASE -> "This change adds a high share of the week's planned load."
        TrainingAssessment.UNSUPPORTED -> "This change is outside runway's default range and needs explicit confirmation."
        TrainingAssessment.NEEDS_REVIEW -> error("Numeric risk required")
    }

    private fun assessmentAttention(
        assessment: TrainingAssessment,
        ramp: Boolean,
    ): TrainingAssessmentAttention = when (assessment) {
        TrainingAssessment.WITHIN_DEFAULT -> TrainingAssessmentAttention.NONE
        TrainingAssessment.ABOVE_DEFAULT -> TrainingAssessmentAttention.REVIEW
        TrainingAssessment.HIGH_INCREASE -> TrainingAssessmentAttention.HIGH
        TrainingAssessment.UNSUPPORTED -> if (ramp) TrainingAssessmentAttention.BLOCKED else TrainingAssessmentAttention.HIGH
        TrainingAssessment.NEEDS_REVIEW -> error("Numeric risk required")
    }

    private fun percent(value: Double): String {
        require(value.isFinite()) { "Ramp evidence must be a finite percentage." }
        return "${roundTrainingValueToOneDecimal(value)}%"
    }
}
