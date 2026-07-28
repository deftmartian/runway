import { describe, expect, test } from 'vitest';
import { isMobileActionName } from './mobile-mutations';

describe('mobile Health Connect resolution actions', () => {
	test.each(['resolve_health_connect_record', 'resolve_health_connect_duplicate'])(
		'keeps %s inside the typed native action allowlist',
		(action) => {
			expect(isMobileActionName(action)).toBe(true);
		}
	);

	test('does not accept a browser-style Health Connect action name', () => {
		expect(isMobileActionName('resolveHealthConnectRecord')).toBe(false);
	});
});
