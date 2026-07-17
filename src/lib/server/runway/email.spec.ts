import { afterEach, describe, expect, test, vi } from 'vitest';
import { EmailConfigurationError, isEmailConfigured, readSmtpConfig } from './email';

afterEach(() => {
	vi.unstubAllEnvs();
});

describe('SMTP configuration', () => {
	test('stays disabled until mail is explicitly enabled', () => {
		expect.assertions(1);
		vi.stubEnv('MAIL_ENABLED', 'false');

		expect(isEmailConfigured()).toBe(false);
	});

	test('requires host and from address when enabled', () => {
		expect.assertions(1);
		vi.stubEnv('MAIL_ENABLED', 'true');
		vi.stubEnv('SMTP_HOST', '');
		vi.stubEnv('SMTP_FROM', 'runway@example.test');

		expect(() => readSmtpConfig()).toThrow(EmailConfigurationError);
	});

	test('defaults to STARTTLS on submission ports', () => {
		expect.assertions(3);
		vi.stubEnv('MAIL_ENABLED', 'true');
		vi.stubEnv('SMTP_HOST', 'smtp.example.test');
		vi.stubEnv('SMTP_PORT', '587');
		vi.stubEnv('SMTP_FROM', 'runway@example.test');

		const config = readSmtpConfig();

		expect(config.secure).toBe(false);
		expect(config.requireTLS).toBe(true);
		expect(config.ignoreTLS).toBe(false);
	});

	test('uses implicit TLS on port 465', () => {
		expect.assertions(2);
		vi.stubEnv('MAIL_ENABLED', 'true');
		vi.stubEnv('SMTP_HOST', 'smtp.example.test');
		vi.stubEnv('SMTP_PORT', '465');
		vi.stubEnv('SMTP_FROM', 'runway@example.test');

		const config = readSmtpConfig();

		expect(config.secure).toBe(true);
		expect(config.requireTLS).toBe(true);
	});

	test('blocks plaintext SMTP in production unless explicitly allowed', () => {
		expect.assertions(2);
		vi.stubEnv('NODE_ENV', 'production');
		vi.stubEnv('MAIL_ENABLED', 'true');
		vi.stubEnv('SMTP_HOST', '127.0.0.1');
		vi.stubEnv('SMTP_PORT', '1025');
		vi.stubEnv('SMTP_FROM', 'runway@example.test');
		vi.stubEnv('SMTP_TLS_MODE', 'none');

		expect(() => readSmtpConfig()).toThrow(/SMTP_ALLOW_PLAINTEXT/);

		vi.stubEnv('SMTP_ALLOW_PLAINTEXT', 'true');
		expect(readSmtpConfig().ignoreTLS).toBe(true);
	});
});
