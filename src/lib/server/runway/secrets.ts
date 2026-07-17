import { createHmac } from 'node:crypto';
import { defaults, seal, unseal } from '@hapi/iron';
import { building } from '$app/environment';
import { env } from '$env/dynamic/private';

type SealedSecret = {
	value: string;
};

const developmentSecret = 'runway-development-secret-for-local-preview-only';

export async function sealSecret(value: string): Promise<string> {
	return seal({ value } satisfies SealedSecret, readSecretPassword(), defaults);
}

export async function openSecret(sealed: string): Promise<string> {
	const unsealed = (await unseal(sealed, readSecretPassword(), defaults)) as Partial<SealedSecret>;
	if (typeof unsealed.value !== 'string') {
		throw new Error('Secret value is not in a supported format.');
	}
	return unsealed.value;
}

export function secretBlindIndex(namespace: string, value: string): string {
	if (!namespace) throw new Error('Secret blind-index namespace is required.');
	return createHmac('sha256', readSecretPassword())
		.update(namespace, 'utf8')
		.update('\0', 'utf8')
		.update(value, 'utf8')
		.digest('hex');
}

function readSecretPassword(): string {
	const secret =
		process.env['IMPORT_SECRET_KEY'] ||
		env['IMPORT_SECRET_KEY'] ||
		process.env['BETTER_AUTH_SECRET'] ||
		env['BETTER_AUTH_SECRET'];
	const nodeEnv = process.env['NODE_ENV'] ?? env['NODE_ENV'];
	if (!secret && nodeEnv === 'production' && !building) {
		throw new Error('IMPORT_SECRET_KEY or BETTER_AUTH_SECRET is required in production.');
	}

	return secret || developmentSecret;
}
