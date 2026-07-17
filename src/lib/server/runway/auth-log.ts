type AuthLogLevel = 'debug' | 'info' | 'warn' | 'error';

const rejectedAttemptMessages = [
	'user not found',
	'credential account not found',
	'password not found',
	'invalid password'
];

export function redactAuthLogMessage(message: string): string {
	const normalized = message.toLowerCase();
	if (rejectedAttemptMessages.some((candidate) => normalized.includes(candidate))) {
		return 'Authentication attempt rejected.';
	}

	return message
		.replace(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi, '[redacted-email]')
		.replace(/([?&](?:token|code|state|password|client_secret)=)[^&\s]+/gi, '$1[redacted]')
		.replace(
			/(["']?(?:token|code|state|password|client_secret)["']?\s*[:=]\s*["']?)[^"',\s&}]+/gi,
			'$1[redacted]'
		)
		.replace(/\b[A-Za-z0-9]{5}-[A-Za-z0-9]{5}\b/g, '[redacted-backup-code]')
		.replace(/\b\d{6}\b/g, '[redacted-one-time-code]')
		.replace(/\bBearer\s+[A-Za-z0-9._~+/-]+=*/gi, 'Bearer [redacted]');
}

export const authLogger = {
	level: 'warn' as const,
	disableColors: true,
	log(level: AuthLogLevel, message: string): void {
		const safeMessage = redactAuthLogMessage(message);
		if (level === 'error') {
			console.error(`[Better Auth] ${safeMessage}`);
			return;
		}
		console.warn(`[Better Auth] ${safeMessage}`);
	}
};
