import { describe, expect, test } from 'vitest';
import {
	androidApiCompatibility,
	buildAndroidInstanceDescriptor,
	resolveAndroidApplicationId
} from './android-instance';

describe('Android instance discovery', () => {
	test('publishes the narrow compatibility contract without account data', () => {
		const descriptor = buildAndroidInstanceDescriptor();
		expect(typeof descriptor.release).toBe('string');
		expect({ ...descriptor, release: 'checked-separately' }).toEqual({
			result: 'runway-instance',
			product: 'runway',
			minimumAndroidApi: 1,
			maximumAndroidApi: 1,
			release: 'checked-separately'
		});
		expect(androidApiCompatibility.minimum).toBeLessThanOrEqual(androidApiCompatibility.maximum);
	});

	test('uses the canonical package by default and validates configured package ids', () => {
		expect(resolveAndroidApplicationId(undefined)).toBe('com.deftmartian.runway');
		expect(resolveAndroidApplicationId(' com.example.runway ')).toBe('com.example.runway');
		expect(resolveAndroidApplicationId('runway')).toBeNull();
		expect(resolveAndroidApplicationId('com.example/runway')).toBeNull();
	});
});
