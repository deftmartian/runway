import { describe, expect, test } from 'vitest';
import { buildAndroidAssetLinks } from './android-asset-links';

const fingerprint = Array.from({ length: 32 }, () => 'AB').join(':');

describe('Android Digital Asset Links configuration', () => {
	test('builds one same-origin application statement', () => {
		expect(buildAndroidAssetLinks('com.example.runway', fingerprint)).toEqual([
			{
				relation: ['delegate_permission/common.handle_all_urls'],
				target: {
					namespace: 'android_app',
					package_name: 'com.example.runway',
					sha256_cert_fingerprints: [fingerprint]
				}
			}
		]);
	});

	test('normalizes and deduplicates multiple certificate fingerprints', () => {
		const statements = buildAndroidAssetLinks(
			'com.example.runway',
			`${fingerprint.toLowerCase()}, ${fingerprint}`
		);
		expect(statements?.[0]?.target.sha256_cert_fingerprints).toEqual([fingerprint]);
	});

	test('fails closed for missing or malformed release identity', () => {
		expect(buildAndroidAssetLinks(undefined, fingerprint)).toBeNull();
		expect(buildAndroidAssetLinks('runway', fingerprint)).toBeNull();
		expect(buildAndroidAssetLinks('com.example.runway', 'not-a-certificate')).toBeNull();
	});
});
