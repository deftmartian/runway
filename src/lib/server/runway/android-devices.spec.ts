import { describe, expect, test } from 'vitest';
import {
	normalizeAndroidDeviceLabel,
	normalizePairingCode,
	parseAndroidBearerToken
} from './android-devices';

describe('Android device credential boundaries', () => {
	test('normalizes a grouped pairing code without accepting shorter guesses', () => {
		expect(normalizePairingCode('abcd-1234 ef56-7890')).toBe('ABCD1234EF567890');
		expect(normalizePairingCode('ABCD-1234')).toBeNull();
		expect(normalizePairingCode('ABCD-1234-EF56-789Z')).toBeNull();
	});

	test('keeps device labels short and free of control characters', () => {
		expect(normalizeAndroidDeviceLabel('  Trail   phone  ')).toBe('Trail phone');
		expect(normalizeAndroidDeviceLabel('phone\nname')).toBeNull();
		expect(normalizeAndroidDeviceLabel('x'.repeat(61))).toBeNull();
	});

	test('accepts only the versioned bearer token shape', () => {
		const deviceId = '4f48bcf8-65d5-4f42-a8e2-3252fd55c034';
		const token = `rwy1_${deviceId}_${'A'.repeat(43)}`;
		expect(parseAndroidBearerToken(`Bearer ${token}`)).toEqual({ deviceId, token });
		expect(parseAndroidBearerToken(token)).toBeNull();
		expect(parseAndroidBearerToken(`Bearer rwy2_${deviceId}_${'A'.repeat(43)}`)).toBeNull();
		expect(parseAndroidBearerToken(`Bearer ${token}extra`)).toBeNull();
	});
});
