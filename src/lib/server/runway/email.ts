import { env } from '$env/dynamic/private';
import nodemailer from 'nodemailer';

type PasswordResetEmail = {
	to: string;
	resetUrl: string;
};

export class EmailConfigurationError extends Error {
	constructor(message = 'Email is not configured for this deployment.') {
		super(message);
		this.name = 'EmailConfigurationError';
	}
}

export function readSmtpConfig() {
	if (readPrivateEnv('MAIL_ENABLED') !== 'true') {
		throw new EmailConfigurationError();
	}

	const host = readPrivateEnv('SMTP_HOST')?.trim();
	const from = readPrivateEnv('SMTP_FROM')?.trim();
	const user = readPrivateEnv('SMTP_USER')?.trim();
	const password = readPrivateEnv('SMTP_PASSWORD');

	if (!host || !from) {
		throw new EmailConfigurationError();
	}

	const port = Number(readPrivateEnv('SMTP_PORT') ?? '587');
	if (!Number.isInteger(port) || port <= 0 || port > 65_535) {
		throw new EmailConfigurationError('SMTP_PORT must be a valid TCP port.');
	}

	const tls = readTlsMode(port);

	return {
		host,
		port,
		secure: tls.secure,
		requireTLS: tls.requireTLS,
		ignoreTLS: tls.ignoreTLS,
		from,
		auth: user && password ? { user, pass: password } : undefined
	};
}

export function isEmailConfigured(): boolean {
	try {
		readSmtpConfig();
		return true;
	} catch (error) {
		if (error instanceof EmailConfigurationError) return false;
		throw error;
	}
}

export async function sendPasswordResetEmail(input: PasswordResetEmail): Promise<void> {
	const config = readSmtpConfig();
	const transport = nodemailer.createTransport({
		host: config.host,
		port: config.port,
		secure: config.secure,
		requireTLS: config.requireTLS,
		ignoreTLS: config.ignoreTLS,
		...(config.auth ? { auth: config.auth } : {})
	});

	await transport.sendMail({
		from: config.from,
		to: input.to,
		subject: 'Reset your runway password',
		text: [
			'Use this link to reset your runway password:',
			'',
			input.resetUrl,
			'',
			'The link expires soon and can only be used once. If you did not request it, ignore this email.'
		].join('\n')
	});
}

function readTlsMode(port: number): { secure: boolean; requireTLS: boolean; ignoreTLS: boolean } {
	const explicitMode = readPrivateEnv('SMTP_TLS_MODE')?.trim().toLowerCase();

	if (explicitMode) {
		if (explicitMode === 'tls') return { secure: true, requireTLS: false, ignoreTLS: false };
		if (explicitMode === 'starttls') return { secure: false, requireTLS: true, ignoreTLS: false };
		if (explicitMode === 'none') {
			if (
				readPrivateEnv('NODE_ENV') === 'production' &&
				readPrivateEnv('SMTP_ALLOW_PLAINTEXT') !== 'true'
			) {
				throw new EmailConfigurationError(
					'SMTP_TLS_MODE=none requires SMTP_ALLOW_PLAINTEXT=true in production.'
				);
			}
			return { secure: false, requireTLS: false, ignoreTLS: true };
		}
		throw new EmailConfigurationError('SMTP_TLS_MODE must be tls, starttls, or none.');
	}

	return {
		secure: readPrivateEnv('SMTP_SECURE') === 'true' || port === 465,
		requireTLS: readPrivateEnv('SMTP_REQUIRE_TLS') !== 'false',
		ignoreTLS: false
	};
}

function readPrivateEnv(name: string): string | undefined {
	return process.env[name] ?? env[name];
}
