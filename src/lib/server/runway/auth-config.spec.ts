import { symmetricDecrypt, symmetricEncrypt } from 'better-auth/crypto';
import { describe, expect, test } from 'vitest';
import {
	authFreshSessionSeconds,
	canonicalAppOrigin,
	isFreshAuthSession,
	omitStoredOidcIdToken,
	oauthTokenStorageOptions,
	oidcDiscoveryUrl,
	passkeyRpIdProblem,
	publicOriginMismatchProblem
} from './auth-config';

describe('authentication configuration', () => {
	test('keeps sensitive account changes behind a short fresh-session window', () => {
		expect(authFreshSessionSeconds).toBe(600);
	});

	test('accepts only recent, valid session timestamps', () => {
		const now = Date.parse('2026-07-18T12:00:00.000Z');
		expect(isFreshAuthSession('2026-07-18T11:55:00.000Z', now)).toBe(true);
		expect(isFreshAuthSession('2026-07-18T11:49:59.000Z', now)).toBe(false);
		expect(isFreshAuthSession('not-a-date', now)).toBe(false);
		expect(isFreshAuthSession('2026-07-18T12:02:00.000Z', now)).toBe(false);
	});

	test('accepts a canonical app origin', () => {
		expect.assertions(1);
		expect(canonicalAppOrigin('https://runway.example.test', 'ORIGIN')).toBe(
			'https://runway.example.test'
		);
	});

	test('rejects app origins with request-specific URL parts', () => {
		expect.assertions(3);
		expect(() => canonicalAppOrigin('https://runway.example.test/app', 'ORIGIN')).toThrow();
		expect(() => canonicalAppOrigin('https://runway.example.test?next=/app', 'ORIGIN')).toThrow();
		expect(() => canonicalAppOrigin('https://user:secret@runway.example.test', 'ORIGIN')).toThrow();
	});

	test('builds discovery URLs without a double slash', () => {
		expect.assertions(2);
		expect(oidcDiscoveryUrl('https://id.example.test/application/o/runway/')).toBe(
			'https://id.example.test/application/o/runway/.well-known/openid-configuration'
		);
		expect(oidcDiscoveryUrl('https://id.example.test/application/o/runway')).toBe(
			'https://id.example.test/application/o/runway/.well-known/openid-configuration'
		);
	});

	test('requires the passkey RP ID to match the public hostname', () => {
		expect.assertions(2);
		expect(passkeyRpIdProblem('https://runway.example.test', 'runway.example.test')).toBeNull();
		expect(passkeyRpIdProblem('https://runway.example.test', 'example.test')).toMatch(
			/exactly match/
		);
	});

	test('requires one canonical public origin', () => {
		expect(
			publicOriginMismatchProblem('https://runway.example.test', 'https://runway.example.test')
		).toBeNull();
		expect(
			publicOriginMismatchProblem('https://runway.example.test', 'https://passkeys.example.test')
		).toMatch(/must match/);
	});

	test('keeps OAuth token storage encrypted with a versioned ciphertext envelope', async () => {
		expect.assertions(5);
		expect(oauthTokenStorageOptions.encryptOAuthTokens).toBe(true);
		const sentinels = [
			'synthetic-access-token-for-encryption-regression',
			'synthetic-refresh-token-for-encryption-regression'
		];
		const key = {
			keys: new Map([[7, 'synthetic-auth-secret-with-at-least-32-characters']]),
			currentVersion: 7
		};
		const ciphertexts = await Promise.all(sentinels.map((data) => symmetricEncrypt({ key, data })));
		expect(ciphertexts.every((ciphertext) => /^\$ba\$7\$/.test(ciphertext))).toBe(true);
		expect(
			ciphertexts.every((ciphertext, index) => !ciphertext.includes(sentinels[index] ?? ''))
		).toBe(true);
		await expect(
			Promise.all(ciphertexts.map((data) => symmetricDecrypt({ key, data })))
		).resolves.toEqual(sentinels);
		expect(omitStoredOidcIdToken({ providerId: 'authentik', idToken: sentinels[0] })).toEqual({
			providerId: 'authentik',
			idToken: null
		});
	});
});
