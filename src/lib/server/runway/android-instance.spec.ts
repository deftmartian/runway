import { describe, expect, test } from 'vitest';
import {
	androidApiCompatibility,
	buildAndroidInstanceDescriptor,
	isSupportedAndroidClient,
	resolveAndroidApplicationId
} from './android-instance';

describe('Android instance discovery', () => {
	test('publishes the narrow compatibility contract without account data', () => {
		const descriptor = buildAndroidInstanceDescriptor();
		expect(typeof descriptor.release).toBe('string');
		expect(new URL(descriptor.serverOrigin).origin).toBe(descriptor.serverOrigin);
		expect(typeof descriptor.auth.local).toBe('boolean');
		expect(typeof descriptor.auth.localSignups).toBe('boolean');
		expect(typeof descriptor.auth.oidc).toBe('boolean');
		expect(descriptor.auth.passkeys).toBe(true);
		expect({ ...descriptor, auth: 'checked-separately', release: 'checked-separately' }).toEqual({
			result: 'runway-instance',
			product: 'runway',
			serverOrigin: descriptor.serverOrigin,
			minimumAndroidApi: 1,
			maximumAndroidApi: 2,
			nativeUi: true,
			nativeAuthorization: 'local_and_device_authorization',
			auth: 'checked-separately',
			release: 'checked-separately'
		});
		expect(androidApiCompatibility.minimum).toBeLessThanOrEqual(androidApiCompatibility.maximum);
	});

	test('uses the canonical package by default and validates configured package ids', () => {
		expect(resolveAndroidApplicationId(undefined)).toBe('dev.deftmartian.runway');
		expect(resolveAndroidApplicationId(' com.example.runway ')).toBe('com.example.runway');
		expect(resolveAndroidApplicationId('runway')).toBeNull();
		expect(resolveAndroidApplicationId('com.example/runway')).toBeNull();
	});

	test('accepts the import-only predecessor and native-client headers', () => {
		expect(isSupportedAndroidClient('runway-android/1')).toBe(true);
		expect(isSupportedAndroidClient('runway-android/2')).toBe(true);
		expect(isSupportedAndroidClient('runway-android/3')).toBe(false);
		expect(isSupportedAndroidClient(null)).toBe(false);
	});
});
