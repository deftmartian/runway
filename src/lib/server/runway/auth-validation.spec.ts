import { describe, expect, test } from 'vitest';
import { authEmailSchema, backupCodeSchema, newPasswordSchema, totpCodeSchema } from './validation';

describe('authentication input validation', () => {
	test('normalizes a valid email and rejects malformed addresses', () => {
		expect.assertions(2);
		expect(authEmailSchema.parse('  runner@example.test  ')).toBe('runner@example.test');
		expect(authEmailSchema.safeParse('not-an-email').success).toBe(false);
	});

	test('uses the same password bounds as Better Auth', () => {
		expect.assertions(3);
		expect(newPasswordSchema.safeParse('short').success).toBe(false);
		expect(newPasswordSchema.safeParse('correct horse battery staple').success).toBe(true);
		expect(newPasswordSchema.safeParse('x'.repeat(129)).success).toBe(false);
	});

	test('accepts only the configured authenticator and backup-code formats', () => {
		expect.assertions(4);
		expect(totpCodeSchema.safeParse('123456').success).toBe(true);
		expect(totpCodeSchema.safeParse('12345a').success).toBe(false);
		expect(backupCodeSchema.safeParse('A1b2C-3d4E5').success).toBe(true);
		expect(backupCodeSchema.safeParse('123456').success).toBe(false);
	});
});
