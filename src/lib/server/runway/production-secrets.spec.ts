import { randomBytes } from 'node:crypto';
import { describe, expect, test } from 'vitest';
import { validateProductionSecretConfiguration } from './production-secrets';

const generatedSecret = () => `runway-secret-v1_${randomBytes(32).toString('base64url')}`;
const primarySecret = generatedSecret();
const rotatedSecret = generatedSecret();
const dedicatedSecrets = Array.from({ length: 4 }, generatedSecret);

describe('production secret configuration', () => {
	test('accepts generated primary, versioned, and dedicated key material', () => {
		expect(() => {
			validateProductionSecretConfiguration({
				BETTER_AUTH_SECRET: primarySecret,
				BETTER_AUTH_SECRETS: `2:${rotatedSecret},1:${primarySecret}`,
				IMPORT_SECRET_KEY: dedicatedSecrets[0],
				AUTH_RATE_LIMIT_SECRET: dedicatedSecrets[1],
				PASSWORD_RESET_RATE_LIMIT_SECRET: dedicatedSecrets[2],
				ANDROID_CREDENTIAL_SECRET: dedicatedSecrets[3]
			});
		}).not.toThrow();
	});

	test('requires the generated 32-byte runway secret encoding', () => {
		for (const value of [
			undefined,
			'short',
			'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
			'abcdefghijklmnopqrstuvwxyzabcdef',
			'0123456789abcdef0123456789abcdef',
			'correcthorsebatterystaplecorrecthorse',
			' runway-auth-key-with-whitespace-Q8m2P7v4Z1x6 ',
			'runway-build-time-placeholder-secret-Q8m2P7v4Z1x6',
			'runway-secret-v1_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh',
			'runway-secret-v1_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=',
			'runway-secret-v1_***************************************'
		]) {
			expect(() => {
				validateProductionSecretConfiguration({ BETTER_AUTH_SECRET: value });
			}).toThrow(/BETTER_AUTH_SECRET/);
		}
	});

	test('validates every versioned key and rejects malformed or duplicate versions', () => {
		for (const keyring of [
			rotatedSecret,
			`one:${rotatedSecret}`,
			`01:${rotatedSecret}`,
			`2:${rotatedSecret},2:${primarySecret}`,
			`2:${rotatedSecret},1:short`,
			`2:${rotatedSecret},1: runway-auth-key-with-whitespace-Q8m2P7v4Z1x6`
		]) {
			expect(() => {
				validateProductionSecretConfiguration({
					BETTER_AUTH_SECRET: primarySecret,
					BETTER_AUTH_SECRETS: keyring
				});
			}).toThrow(/BETTER_AUTH_SECRETS/);
		}
	});

	test('rejects weak dedicated keys without requiring them', () => {
		expect(() => {
			validateProductionSecretConfiguration({ BETTER_AUTH_SECRET: primarySecret });
		}).not.toThrow();

		for (const name of [
			'IMPORT_SECRET_KEY',
			'AUTH_RATE_LIMIT_SECRET',
			'PASSWORD_RESET_RATE_LIMIT_SECRET',
			'ANDROID_CREDENTIAL_SECRET'
		]) {
			expect(() => {
				validateProductionSecretConfiguration({
					BETTER_AUTH_SECRET: primarySecret,
					[name]: 'replace-me-with-a-real-secret-value'
				});
			}).toThrow(name);
		}
	});

	test('never includes rejected values in configuration errors', () => {
		const rejectedValue = 'runway-example-secret-never-print-Q8m2P7v4Z1x6';
		let message = '';
		try {
			validateProductionSecretConfiguration({ BETTER_AUTH_SECRET: rejectedValue });
		} catch (error) {
			message = error instanceof Error ? error.message : String(error);
		}
		expect(message).not.toContain(rejectedValue);
	});
});
