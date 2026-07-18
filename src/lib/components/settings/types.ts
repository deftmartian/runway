import type { SubmitFunction } from '@sveltejs/kit';

export type SettingsFormState = {
	message?: string;
	scope?: string;
	totpQrCode?: string;
	totpManualKey?: string;
	setupPending?: boolean;
	backupCodes?: string[];
};

export type SettingsProfile = {
	timeZone: string | null;
	routeDataMode: 'discard' | 'private';
	sexForEstimates: 'not_specified' | 'female' | 'male';
	ageYears: number | null;
	heartRateSettingsSource: string;
	maxHeartRateBpm: number | null;
	zone2FloorBpm: number | null;
	zone3FloorBpm: number | null;
	zone4FloorBpm: number | null;
	zone5FloorBpm: number | null;
};

export type SettingsUser = {
	id: string;
	email: string;
	twoFactorEnabled: boolean;
};

export type SettingsPasskey = {
	id: string;
	name: string | null | undefined;
	deviceType: string;
	backedUp: boolean;
};

export type SettingsAuthCapabilities = {
	localPassword: boolean;
	oidc: boolean;
};

export type SettingsActionEnhancer = (
	key: string,
	confirmation?: string
) => SubmitFunction<never, never>;
