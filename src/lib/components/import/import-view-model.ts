import type { SubmitFunction } from '@sveltejs/kit';
import type { HeartRateActivitySummary } from '$lib/training/types';

export type ImportSection = 'activities' | 'sources' | 'gpx' | 'empty-gpx';

export type ScopedImportResult = {
	section: ImportSection;
	message: string;
	failed: boolean;
};

export type ScopedEnhanceFactory = (key: string, section: ImportSection) => SubmitFunction;

export type ImportWorkoutCandidate = {
	id: string;
	scheduledDate: Date | string;
	purpose: string;
	targetDistanceMeters: number;
};

export type ImportedActivitySummary = {
	id: string;
	workoutId: string | null;
	source: string;
	reviewState: 'review' | 'accepted';
	activityDate: Date | string;
	distanceMeters: number;
	durationSeconds: number | null;
	averageHeartRate: number | null;
	maxHeartRate: number | null;
	heartRateSummary: HeartRateActivitySummary | null;
	feltHard: boolean;
	pain: boolean;
	extraPlanImpactConfirmed: boolean;
	routeSummary: {
		pointCount: number;
	};
	matchedWorkoutPurpose: string | null;
	matchedWorkoutDate: Date | string | null;
};

export type ImportedActivityPage = {
	items: ImportedActivitySummary[];
	nextOffset: number | null;
};

export type ImportSourceSummary = {
	id: string;
	label: string;
	enabled: boolean;
	lastCheckedAt: Date | string | null;
	lastImportedAt: Date | string | null;
	lastError: string | null;
};

export type AndroidDeviceSummary = {
	id: string;
	label: string;
	expiresAt: Date | string;
	lastSeenAt: Date | string | null;
	lastImportedAt: Date | string | null;
};

export type AndroidPairingSummary = {
	code: string;
	expiresAt: Date | string;
};

export type ImportShareNotice = {
	message: string;
	failed: boolean;
};
