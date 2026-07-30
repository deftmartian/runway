package dev.deftmartian.runway.domain

enum class TrainingAssessment { WITHIN_DEFAULT, ABOVE_DEFAULT, HIGH_INCREASE, UNSUPPORTED, NEEDS_REVIEW, PAIN_REVIEW }
enum class TrainingAssessmentAttention { NONE, REVIEW, HIGH, BLOCKED }
data class TrainingAssessmentPresentation(val assessment: TrainingAssessment, val label: String, val description: String, val attention: TrainingAssessmentAttention)

object TrainingAssessments {
    fun fromRisk(risk: RiskRating) = when (risk) { RiskRating.CONSERVATIVE -> TrainingAssessment.WITHIN_DEFAULT; RiskRating.MODERATE -> TrainingAssessment.ABOVE_DEFAULT; RiskRating.AGGRESSIVE -> TrainingAssessment.HIGH_INCREASE; RiskRating.UNSAFE -> TrainingAssessment.UNSUPPORTED }
    fun presentRamp(risk: RiskRating): TrainingAssessmentPresentation = present(fromRisk(risk), true)
    fun presentLoadChange(risk: RiskRating): TrainingAssessmentPresentation = present(fromRisk(risk), false)
    fun presentMixedPrescription() = TrainingAssessmentPresentation(TrainingAssessment.NEEDS_REVIEW, "Mixed prescriptions", "This plan contains distance and timed work. Review each prescription separately; runway does not convert between them.", TrainingAssessmentAttention.REVIEW)
    fun formatRampEvidence(required: Double, default: Double? = null): String { val value = percent(required); return if (default == null) "$value needed each week" else "$value needed each week · ${percent(default)} runway default" }
    fun formatLoadChangeEvidence(change: Double, risk: RiskRating): String = when (risk) { RiskRating.UNSAFE -> "${percent(change)} of weekly load; outside-default boundary 25%."; RiskRating.AGGRESSIVE -> "${percent(change)} of weekly load; high-change boundary 15%."; else -> "${percent(change)} of weekly load; default up to 10%." }
    private fun present(assessment: TrainingAssessment, ramp: Boolean): TrainingAssessmentPresentation = when (assessment) {
        TrainingAssessment.WITHIN_DEFAULT -> TrainingAssessmentPresentation(assessment, "Within default", if (ramp) "The calculated increase stays within runway's default ramp." else "This change stays within runway's default load-change range.", TrainingAssessmentAttention.NONE)
        TrainingAssessment.ABOVE_DEFAULT -> TrainingAssessmentPresentation(assessment, "Above default", if (ramp) "The calculated increase is above runway's default ramp." else "This change is above runway's default load-change range.", TrainingAssessmentAttention.REVIEW)
        TrainingAssessment.HIGH_INCREASE -> TrainingAssessmentPresentation(assessment, if (ramp) "High increase" else "High change", if (ramp) "The calculated increase is well above runway's default ramp." else "This change adds a high share of the week's planned load.", TrainingAssessmentAttention.HIGH)
        TrainingAssessment.UNSUPPORTED -> TrainingAssessmentPresentation(assessment, if (ramp) "Unsupported" else "Outside default", if (ramp) "The calculated increase is outside runway's plan-generation limits." else "This change is outside runway's default range and needs explicit confirmation.", if (ramp) TrainingAssessmentAttention.BLOCKED else TrainingAssessmentAttention.HIGH)
        else -> error("Numeric risk required")
    }
    private fun percent(value: Double): String {
        require(value.isFinite()) { "Ramp evidence must be a finite percentage." }
        return "${roundOneDecimalLikeJavaScript(value)}%"
    }
}
