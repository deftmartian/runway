import { describe, expect, test, vi } from 'vitest';
import { authLogger, redactAuthLogMessage } from './auth-log';

describe('authentication logging', () => {
	test('does not reveal whether an account or password exists', () => {
		expect.assertions(2);
		expect(redactAuthLogMessage('User not found: runner@example.test')).toBe(
			'Authentication attempt rejected.'
		);
		expect(redactAuthLogMessage('Invalid password for runner@example.test')).toBe(
			'Authentication attempt rejected.'
		);
	});

	test('redacts common credentials from other warning messages', () => {
		expect.assertions(7);
		const message = redactAuthLogMessage(
			'Callback for runner@example.test?token=secret&code=code-value with Bearer abc.def.ghi; password: hunter2; TOTP 123456; backup A1b2C-3d4E5'
		);
		expect(message).not.toContain('runner@example.test');
		expect(message).not.toContain('token=secret');
		expect(message).not.toContain('code=code-value');
		expect(message).not.toContain('abc.def.ghi');
		expect(message).not.toContain('hunter2');
		expect(message).not.toContain('123456');
		expect(message).not.toContain('A1b2C-3d4E5');
	});

	test('never forwards Better Auth positional error data to the process logger', () => {
		expect.assertions(3);
		const syntheticToken = 'synthetic-token-that-must-not-be-logged';
		const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);

		try {
			authLogger.log('error', 'Failed to create user', {
				query: 'insert into session values (...)',
				params: [syntheticToken]
			});

			expect(errorSpy).toHaveBeenCalledTimes(1);
			expect(errorSpy).toHaveBeenCalledWith('[Better Auth] Failed to create user');
			expect(errorSpy.mock.calls.flat().join(' ')).not.toContain(syntheticToken);
		} finally {
			errorSpy.mockRestore();
		}
	});
});
