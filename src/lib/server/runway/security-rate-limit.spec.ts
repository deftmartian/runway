import { describe, expect, test } from 'vitest';
import { gpxImportRateLimitBuckets, nextcloudImportRateLimitBuckets } from './security-rate-limit';

describe('import security rate-limit contracts', () => {
	test('bounds GPX parsing by both user and client address', () => {
		expect(gpxImportRateLimitBuckets('user-1', '192.0.2.4')).toEqual([
			{
				name: 'gpx-import:ip',
				subject: '192.0.2.4',
				max: 60,
				windowMs: 600_000
			},
			{ name: 'gpx-import:user', subject: 'user-1', max: 30, windowMs: 600_000 }
		]);
	});

	test('keeps Nextcloud aggregate and action-specific budgets', () => {
		expect(nextcloudImportRateLimitBuckets('connect', 'user-1', '192.0.2.4')).toEqual([
			{
				name: 'nextcloud-import:ip',
				subject: '192.0.2.4',
				max: 40,
				windowMs: 600_000
			},
			{ name: 'nextcloud-import:user', subject: 'user-1', max: 20, windowMs: 600_000 },
			{
				name: 'nextcloud-import:connect:user',
				subject: 'user-1',
				max: 5,
				windowMs: 600_000
			}
		]);
	});
});
