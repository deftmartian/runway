import { parseIsoDate } from './date';
import type { RiskRating, TimedIntervalStructure, WorkoutStatus, WorkoutType } from './types';

export type EditableWorkoutState = {
	id: string;
	weekId: string;
	scheduledDate: string;
	type: WorkoutType;
	status: WorkoutStatus;
	prescriptionKind: 'distance' | 'timed' | 'rest';
	targetDistanceMeters: number;
	targetDurationSeconds: number | null;
	intervalStructure: TimedIntervalStructure | null;
	intensity: string;
	purpose: string;
	reason: string;
	sourceRefs: string[];
	isRemoved: boolean;
};

export type WorkoutEditProposal = Omit<EditableWorkoutState, 'id' | 'status'>;

export type WorkoutEditWeek = {
	id: string;
	weekNumber: number;
	startDate: string;
};

export type WorkoutEditPreview = {
	operation: 'edit' | 'add' | 'remove';
	recommended: WorkoutEditProposal | null;
	current: WorkoutEditProposal;
	proposed: WorkoutEditProposal;
	workoutChanges: WorkoutEditWorkoutChange[];
	weekChanges: {
		weekId: string;
		weekNumber: number;
		distanceBeforeMeters: number;
		distanceAfterMeters: number;
		durationBeforeSeconds: number;
		durationAfterSeconds: number;
	}[];
	spacingConflicts: { workoutId: string; scheduledDate: string; purpose: string }[];
	affectedFutureWorkoutIds: string[];
	/** Largest share of an affected week's existing load changed by this edit. */
	weeklyLoadChangePercent: number;
	projectedRampPercent: number;
	projectedRampRisk: RiskRating;
	guardrails: WorkoutEditGuardrail[];
	risk: RiskRating;
	requiresConfirmation: boolean;
};

export type WorkoutEditGuardrail = {
	kind: 'prescription_basis_change';
	label: string;
	description: string;
};

export type WorkoutEditWorkoutChange = {
	workoutId: string;
	isSelected: boolean;
	before: WorkoutEditProposal;
	after: WorkoutEditProposal;
	relativeChangePercent: number | null;
	changeShareOfWeekPercent: number | null;
	risk: RiskRating;
};

export function previewWorkoutEdit(input: {
	current: EditableWorkoutState;
	recommended: WorkoutEditProposal | null;
	proposed: WorkoutEditProposal;
	workouts: EditableWorkoutState[];
	weeks: WorkoutEditWeek[];
	today: string;
	rebalance: boolean;
	hasInjuryRisk?: boolean;
	operation?: 'edit' | 'add' | 'remove';
}): WorkoutEditPreview {
	assertWorkoutProposal(input.proposed);
	const currentProposal = proposalFromWorkout(input.current);
	const rebalanced = input.rebalance
		? rebalanceWorkoutStates({
				selectedId: input.current.id,
				current: input.current,
				proposed: input.proposed,
				workouts: input.workouts,
				today: input.today
			})
		: [];
	const rebalancedById = new Map(rebalanced.map((change) => [change.workoutId, change.proposed]));
	const before = input.workouts;
	const after = input.workouts.map((candidate) => {
		if (candidate.id === input.current.id) return { ...candidate, ...input.proposed };
		const changed = rebalancedById.get(candidate.id);
		return changed ? { ...candidate, ...changed } : candidate;
	});
	const changedIds = new Set([input.current.id, ...rebalanced.map((change) => change.workoutId)]);
	const workoutChanges = before
		.filter((candidate) => changedIds.has(candidate.id))
		.map((candidate) => {
			const updated = after.find((afterCandidate) => afterCandidate.id === candidate.id);
			if (!updated) throw new Error('An affected workout is missing from the edit preview.');
			return workoutChangePreview(candidate, updated, before, candidate.id === input.current.id);
		});
	const affectedWeekIds = new Set([input.current.weekId, input.proposed.weekId]);
	for (const change of rebalanced) affectedWeekIds.add(change.proposed.weekId);
	const weekChanges = input.weeks
		.filter((week) => affectedWeekIds.has(week.id))
		.map((week) => {
			const beforeLoad = weekLoad(before, week.id);
			const afterLoad = weekLoad(after, week.id);
			return {
				weekId: week.id,
				weekNumber: week.weekNumber,
				distanceBeforeMeters: beforeLoad.distanceMeters,
				distanceAfterMeters: afterLoad.distanceMeters,
				durationBeforeSeconds: beforeLoad.durationSeconds,
				durationAfterSeconds: afterLoad.durationSeconds
			};
		});
	const spacingConflicts =
		input.proposed.isRemoved || input.proposed.type === 'rest'
			? []
			: after
					.filter(
						(candidate) =>
							candidate.id !== input.current.id &&
							candidate.status === 'planned' &&
							candidate.scheduledDate >= input.today &&
							!candidate.isRemoved &&
							candidate.type !== 'rest' &&
							candidate.type !== 'race' &&
							Math.abs(daysBetween(candidate.scheduledDate, input.proposed.scheduledDate)) <= 1
					)
					.map((candidate) => ({
						workoutId: candidate.id,
						scheduledDate: candidate.scheduledDate,
						purpose: candidate.purpose
					}));
	const destinationWeek = input.weeks.find((week) => week.id === input.proposed.weekId);
	const destinationIndex = destinationWeek ? input.weeks.indexOf(destinationWeek) : -1;
	const previousWeek = destinationIndex > 0 ? input.weeks[destinationIndex - 1] : undefined;
	const destinationAfter = weekLoad(after, input.proposed.weekId);
	const destinationBefore = weekLoad(before, input.proposed.weekId);
	const previousLoad = previousWeek ? weekLoad(after, previousWeek.id) : destinationBefore;
	const proposedHasNoLoad =
		input.proposed.isRemoved ||
		input.proposed.prescriptionKind === 'rest' ||
		input.proposed.type === 'rest';
	const usesDuration =
		input.proposed.prescriptionKind === 'timed' ||
		(proposedHasNoLoad && input.current.prescriptionKind === 'timed');
	const projected = usesDuration
		? destinationAfter.durationSeconds
		: destinationAfter.distanceMeters;
	const prior = usesDuration ? previousLoad.durationSeconds : previousLoad.distanceMeters;
	const fallback = usesDuration
		? Math.max(destinationBefore.durationSeconds, 1)
		: Math.max(destinationBefore.distanceMeters, 1);
	const projectedRampPercent = finitePercent(projected - prior, prior > 0 ? prior : fallback);
	const largestWeekChangeShare = weekChanges.reduce((largest, change) => {
		const beforeValue = usesDuration ? change.durationBeforeSeconds : change.distanceBeforeMeters;
		const afterValue = usesDuration ? change.durationAfterSeconds : change.distanceAfterMeters;
		const weekBase = beforeValue > 0 ? beforeValue : Math.max(afterValue, 1);
		return Math.max(largest, Math.abs(afterValue - beforeValue) / weekBase);
	}, 0);
	const workoutChangeRisk = workoutChanges.reduce<RiskRating>(
		(highest, change) => highestRisk(highest, change.risk),
		'conservative'
	);
	const largestWorkoutChangeShare = workoutChanges.reduce(
		(largest, change) => Math.max(largest, Math.abs(change.changeShareOfWeekPercent ?? 0) / 100),
		0
	);
	const assessedWeeklyLoadShare = Math.max(largestWeekChangeShare, largestWorkoutChangeShare);
	const loadRisk = riskFromShare(assessedWeeklyLoadShare);
	const risk = highestRisk(loadRisk, workoutChangeRisk);
	const guardrails = prescriptionBasisGuardrails(workoutChanges);

	return {
		operation: input.operation ?? 'edit',
		recommended: input.recommended,
		current: currentProposal,
		proposed: input.proposed,
		workoutChanges,
		weekChanges,
		spacingConflicts,
		affectedFutureWorkoutIds: rebalanced.map((change) => change.workoutId),
		weeklyLoadChangePercent: roundPercent(assessedWeeklyLoadShare * 100),
		projectedRampPercent,
		projectedRampRisk: rampRisk(projectedRampPercent, input.hasInjuryRisk ?? false),
		guardrails,
		risk,
		requiresConfirmation:
			risk !== 'conservative' || spacingConflicts.length > 0 || guardrails.length > 0
	};
}

function workoutChangePreview(
	before: EditableWorkoutState,
	after: EditableWorkoutState,
	workoutsBefore: EditableWorkoutState[],
	isSelected: boolean
): WorkoutEditWorkoutChange {
	const beforeProposal = proposalFromWorkout(before);
	const afterProposal = proposalFromWorkout(after);
	const beforeMetric = loadMetric(beforeProposal);
	const afterMetric = loadMetric(afterProposal);
	if (beforeMetric.kind === 'none' || afterMetric.kind === 'none') {
		if (beforeMetric.kind === 'none' && afterMetric.kind === 'none') {
			return {
				workoutId: before.id,
				isSelected,
				before: beforeProposal,
				after: afterProposal,
				relativeChangePercent: 0,
				changeShareOfWeekPercent: 0,
				risk: 'conservative'
			};
		}
		const activeMetric = beforeMetric.kind === 'none' ? afterMetric : beforeMetric;
		if (activeMetric.kind === 'none') {
			throw new Error('An added or removed workout must have a load metric.');
		}
		const weekBefore = weekLoad(workoutsBefore, before.weekId);
		const weekValue =
			activeMetric.kind === 'timed' ? weekBefore.durationSeconds : weekBefore.distanceMeters;
		const weekBase = weekValue > 0 ? weekValue : activeMetric.value;
		const changeShareOfWeekPercent = finitePercent(activeMetric.value, weekBase);
		return {
			workoutId: before.id,
			isSelected,
			before: beforeProposal,
			after: afterProposal,
			relativeChangePercent: null,
			changeShareOfWeekPercent,
			risk: riskFromShare(changeShareOfWeekPercent / 100)
		};
	}
	if (beforeMetric.kind !== afterMetric.kind) {
		const weekBefore = weekLoad(workoutsBefore, before.weekId);
		const weekValue =
			afterMetric.kind === 'timed' ? weekBefore.durationSeconds : weekBefore.distanceMeters;
		const weekBase = weekValue > 0 ? weekValue : afterMetric.value;
		const changeShareOfWeekPercent = finitePercent(afterMetric.value, weekBase);
		return {
			workoutId: before.id,
			isSelected,
			before: beforeProposal,
			after: afterProposal,
			relativeChangePercent: null,
			changeShareOfWeekPercent,
			risk: riskFromShare(changeShareOfWeekPercent / 100)
		};
	}

	const beforeValue = beforeMetric.value;
	const afterValue = afterMetric.value;
	const absoluteChange = Math.abs(afterValue - beforeValue);
	const relativeBase = beforeValue > 0 ? beforeValue : Math.max(afterValue, 1);
	const weekBefore = weekLoad(workoutsBefore, before.weekId);
	const weekValue =
		beforeMetric.kind === 'timed' ? weekBefore.durationSeconds : weekBefore.distanceMeters;
	const weekBase = weekValue > 0 ? weekValue : relativeBase;
	const relativeChangePercent = finitePercent(absoluteChange, relativeBase);
	const changeShareOfWeekPercent = finitePercent(absoluteChange, weekBase);

	return {
		workoutId: before.id,
		isSelected,
		before: beforeProposal,
		after: afterProposal,
		relativeChangePercent,
		changeShareOfWeekPercent,
		risk: riskFromShare(changeShareOfWeekPercent / 100)
	};
}

function loadMetric(
	proposal: WorkoutEditProposal
): { kind: 'distance' | 'timed'; value: number } | { kind: 'none' } {
	if (proposal.isRemoved || proposal.prescriptionKind === 'rest' || proposal.type === 'rest') {
		return { kind: 'none' };
	}
	return proposal.prescriptionKind === 'timed'
		? { kind: 'timed', value: proposal.targetDurationSeconds ?? 0 }
		: { kind: 'distance', value: proposal.targetDistanceMeters };
}

export function rebalanceWorkoutStates(input: {
	selectedId: string;
	current: EditableWorkoutState;
	proposed: WorkoutEditProposal;
	workouts: EditableWorkoutState[];
	today: string;
}): { workoutId: string; proposed: WorkoutEditProposal }[] {
	if (input.proposed.isRemoved || input.proposed.type === 'rest') return [];
	const metric = input.proposed.prescriptionKind;
	if (metric === 'rest') return [];
	const before = weekLoad(input.workouts, input.proposed.weekId);
	const selectedAfter = input.workouts.map((candidate) =>
		candidate.id === input.selectedId ? { ...candidate, ...input.proposed } : candidate
	);
	const after = weekLoad(selectedAfter, input.proposed.weekId);
	const delta =
		metric === 'timed'
			? after.durationSeconds - before.durationSeconds
			: after.distanceMeters - before.distanceMeters;
	if (delta === 0) return [];
	const candidates = selectedAfter.filter(
		(candidate) =>
			candidate.id !== input.selectedId &&
			candidate.weekId === input.proposed.weekId &&
			candidate.scheduledDate >= input.today &&
			candidate.status === 'planned' &&
			!candidate.isRemoved &&
			candidate.type !== 'race' &&
			candidate.prescriptionKind === metric
	);
	if (candidates.length === 0) return [];
	const share = Math.round(delta / candidates.length);
	return candidates.map((candidate) => {
		if (metric === 'timed') {
			const targetDurationSeconds = Math.max(600, (candidate.targetDurationSeconds ?? 600) - share);
			return {
				workoutId: candidate.id,
				proposed: {
					...proposalFromWorkout(candidate),
					targetDurationSeconds,
					intervalStructure: resizeTimedIntervalStructure(
						candidate.intervalStructure,
						targetDurationSeconds
					),
					reason: 'Rebalanced after an explicit workout edit.'
				}
			};
		}
		return {
			workoutId: candidate.id,
			proposed: {
				...proposalFromWorkout(candidate),
				targetDistanceMeters: Math.max(500, candidate.targetDistanceMeters - share),
				reason: 'Rebalanced after an explicit workout edit.'
			}
		};
	});
}

export function proposalFromWorkout(
	workout: Omit<EditableWorkoutState, 'id' | 'status'>
): WorkoutEditProposal {
	return {
		weekId: workout.weekId,
		scheduledDate: workout.scheduledDate,
		type: workout.type,
		prescriptionKind: workout.prescriptionKind,
		targetDistanceMeters: workout.targetDistanceMeters,
		targetDurationSeconds: workout.targetDurationSeconds,
		intervalStructure: structuredClone(workout.intervalStructure),
		intensity: workout.intensity,
		purpose: workout.purpose,
		reason: workout.reason,
		sourceRefs: [...workout.sourceRefs],
		isRemoved: workout.isRemoved
	};
}

export function assertWorkoutProposal(proposed: WorkoutEditProposal): void {
	if (!/^\d{4}-\d{2}-\d{2}$/.test(proposed.scheduledDate)) {
		throw new Error('Workout date is invalid.');
	}
	if (proposed.purpose.trim().length < 2 || proposed.purpose.length > 120) {
		throw new Error('Workout purpose must be between 2 and 120 characters.');
	}
	if (proposed.type === 'race') throw new Error('Race events are changed through the goal editor.');
	if (proposed.prescriptionKind === 'rest') {
		if (
			proposed.type !== 'rest' ||
			proposed.targetDistanceMeters !== 0 ||
			proposed.targetDurationSeconds !== null ||
			proposed.intervalStructure !== null
		) {
			throw new Error('Rest prescriptions cannot include distance, duration, or intervals.');
		}
		return;
	}
	if (proposed.type === 'rest') throw new Error('Run prescriptions need a run type.');
	if (proposed.prescriptionKind === 'distance') {
		if (
			!Number.isInteger(proposed.targetDistanceMeters) ||
			proposed.targetDistanceMeters < 100 ||
			proposed.targetDistanceMeters > 100_000 ||
			proposed.targetDurationSeconds !== null ||
			proposed.intervalStructure !== null
		) {
			throw new Error('Distance prescriptions need a valid distance and no timed intervals.');
		}
		return;
	}
	if (
		proposed.targetDistanceMeters !== 0 ||
		proposed.targetDurationSeconds === null ||
		!Number.isInteger(proposed.targetDurationSeconds) ||
		proposed.targetDurationSeconds < 600 ||
		proposed.targetDurationSeconds > 21_600 ||
		!validIntervals(proposed.intervalStructure, proposed.targetDurationSeconds)
	) {
		throw new Error('Timed prescriptions need a valid duration and run/walk intervals.');
	}
}

function validIntervals(
	structure: TimedIntervalStructure | null,
	totalDurationSeconds?: number | null
): structure is TimedIntervalStructure {
	const blocks = structure?.blocks;
	return Boolean(
		structure &&
		Number.isInteger(structure.warmupSeconds) &&
		structure.warmupSeconds >= 0 &&
		Number.isInteger(structure.cooldownSeconds) &&
		structure.cooldownSeconds >= 0 &&
		blocks?.length &&
		blocks.every(
			(block) =>
				Number.isInteger(block.repetitions) &&
				block.repetitions > 0 &&
				block.repetitions <= 100 &&
				block.segments.length > 0 &&
				block.segments.every(
					(segment) =>
						(segment.kind === 'run' || segment.kind === 'walk') &&
						Number.isInteger(segment.durationSeconds) &&
						segment.durationSeconds > 0
				)
		) &&
		(totalDurationSeconds === undefined ||
			totalDurationSeconds === null ||
			structure.warmupSeconds + structure.cooldownSeconds + blocksDuration(blocks) ===
				totalDurationSeconds)
	);
}

function blocksDuration(blocks: TimedIntervalStructure['blocks']): number {
	return blocks.reduce(
		(total, block) =>
			total +
			block.repetitions * block.segments.reduce((sum, segment) => sum + segment.durationSeconds, 0),
		0
	);
}

export function resizeTimedIntervalStructure(
	structure: TimedIntervalStructure | null,
	totalDurationSeconds: number
): TimedIntervalStructure | null {
	if (!structure) return null;
	const currentTotal =
		structure.warmupSeconds + structure.cooldownSeconds + blocksDuration(structure.blocks);
	if (currentTotal <= 0 || totalDurationSeconds <= 0) return structuredClone(structure);
	const factor = totalDurationSeconds / currentTotal;
	const resized: TimedIntervalStructure = {
		warmupSeconds: Math.max(0, Math.round(structure.warmupSeconds * factor)),
		cooldownSeconds: Math.max(0, Math.round(structure.cooldownSeconds * factor)),
		blocks: structure.blocks.map((block) => ({
			repetitions: block.repetitions,
			segments: block.segments.map((segment) => ({
				...segment,
				durationSeconds: Math.max(1, Math.round(segment.durationSeconds * factor))
			}))
		}))
	};
	const resizedTotal =
		resized.warmupSeconds + resized.cooldownSeconds + blocksDuration(resized.blocks);
	resized.cooldownSeconds = Math.max(
		0,
		resized.cooldownSeconds + totalDurationSeconds - resizedTotal
	);
	return resized;
}

function weekLoad(workouts: EditableWorkoutState[], weekId: string) {
	return workouts.reduce(
		(load, candidate) => {
			if (
				candidate.weekId !== weekId ||
				candidate.isRemoved ||
				candidate.type === 'rest' ||
				candidate.type === 'race'
			) {
				return load;
			}
			load.distanceMeters += candidate.targetDistanceMeters;
			load.durationSeconds += candidate.targetDurationSeconds ?? 0;
			return load;
		},
		{ distanceMeters: 0, durationSeconds: 0 }
	);
}

function daysBetween(left: string, right: string) {
	return (parseIsoDate(left).getTime() - parseIsoDate(right).getTime()) / (24 * 60 * 60 * 1_000);
}

function finitePercent(delta: number, base: number) {
	const value = (delta / Math.max(base, 1)) * 100;
	return Number.isFinite(value) ? roundPercent(value) : 0;
}

function roundPercent(value: number) {
	return Math.round(value * 10) / 10;
}

export function rampRisk(percent: number, hasInjuryRisk = false): RiskRating {
	const offset = hasInjuryRisk ? 2 : 0;
	if (percent > 18 - offset) return 'unsafe';
	if (percent > 12 - offset) return 'aggressive';
	if (percent > 8 - offset) return 'moderate';
	return 'conservative';
}

function prescriptionBasisGuardrails(changes: WorkoutEditWorkoutChange[]): WorkoutEditGuardrail[] {
	const changesBasis = changes.some((change) => {
		const before = loadMetric(change.before);
		const after = loadMetric(change.after);
		return before.kind !== 'none' && after.kind !== 'none' && before.kind !== after.kind;
	});
	return changesBasis
		? [
				{
					kind: 'prescription_basis_change',
					label: 'Prescription basis changed',
					description:
						'Distance and duration are not directly comparable. Review both prescriptions before applying this change.'
				}
			]
		: [];
}

function riskFromShare(share: number): RiskRating {
	if (share > 0.25) return 'unsafe';
	if (share > 0.15) return 'aggressive';
	if (share > 0.1) return 'moderate';
	return 'conservative';
}

function highestRisk(left: RiskRating, right: RiskRating): RiskRating {
	const order: RiskRating[] = ['conservative', 'moderate', 'aggressive', 'unsafe'];
	return order.indexOf(left) >= order.indexOf(right) ? left : right;
}
