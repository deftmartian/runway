import { afterEach, describe, expect, test, vi } from 'vitest';
import { secretBlindIndex } from './secrets';

afterEach(() => {
	vi.unstubAllEnvs();
});

describe('secretBlindIndex', () => {
	test('is deterministic within a namespace without exposing the source value', () => {
		expect.assertions(4);
		vi.stubEnv('IMPORT_SECRET_KEY', 'first-test-secret-with-at-least-thirty-two-characters');
		const first = secretBlindIndex('nextcloud-item:user:source', '/private/run.gpx');
		const repeated = secretBlindIndex('nextcloud-item:user:source', '/private/run.gpx');

		expect(first).toBe(repeated);
		expect(first).toMatch(/^[a-f0-9]{64}$/);
		expect(first).not.toContain('private');
		expect(secretBlindIndex('nextcloud-item:user:other-source', '/private/run.gpx')).not.toBe(
			first
		);
	});

	test('changes when the import secret changes', () => {
		expect.assertions(1);
		vi.stubEnv('IMPORT_SECRET_KEY', 'first-test-secret-with-at-least-thirty-two-characters');
		const first = secretBlindIndex('nextcloud-token:user:host', 'share-token');
		vi.stubEnv('IMPORT_SECRET_KEY', 'second-test-secret-with-at-least-thirty-two-characters');

		expect(secretBlindIndex('nextcloud-token:user:host', 'share-token')).not.toBe(first);
	});
});
