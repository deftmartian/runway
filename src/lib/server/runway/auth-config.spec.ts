import { symmetricDecrypt, symmetricEncrypt } from 'better-auth/crypto';
import { describe, expect, test } from 'vitest';
import {
	canonicalAppOrigin,
	omitStoredOidcIdToken,
	oauthTokenStorageOptions,
	oidcDiscoveryUrl,
	passkeyRpIdProblem
} from './auth-config';

describe('authentication configuration', () => {
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
