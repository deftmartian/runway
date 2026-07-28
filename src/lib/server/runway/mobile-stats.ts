/**
 * Keeps the mobile stats view as a transparent transport of the server's established planning
 * and history projections.  In particular, the generated recommendation must never be replaced
 * with the edited plan, and review-only activity must already have been excluded by getHistory.
 */
export function mobileStatsPayload<Active, Weeks, History, Trace, PlanHistory, PhaseReview>(input: {
	active: Active;
	weeks: Weeks | null;
	history: History;
	planTrace: Trace;
	planHistory: PlanHistory;
	phaseReview: PhaseReview;
}) {
	return {
		onboardingRequired: false,
		active: input.active,
		detail: input.weeks ? { weeks: input.weeks } : null,
		history: input.history,
		planTrace: input.planTrace,
		planHistory: input.planHistory,
		phaseReview: input.phaseReview
	};
}
