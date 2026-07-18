const secretPrefix = 'runway-secret-v1_';
const encodedSecretBytes = 32;
const encodedSecretCharacters = 43;

const dedicatedSecretNames = [
	'IMPORT_SECRET_KEY',
	'AUTH_RATE_LIMIT_SECRET',
	'PASSWORD_RESET_RATE_LIMIT_SECRET',
	'ANDROID_CREDENTIAL_SECRET'
] as const;

export type SecretEnvironment = Readonly<Record<string, string | undefined>>;

/**
 * Reject recoverable production key material that would only produce a warning in Better Auth.
 * Error messages deliberately identify the setting, never its value.
 */
export function validateProductionSecretConfiguration(environment: SecretEnvironment): void {
	assertStrongSecret('BETTER_AUTH_SECRET', environment['BETTER_AUTH_SECRET'], true);
	validateVersionedAuthSecrets(environment['BETTER_AUTH_SECRETS']);

	for (const name of dedicatedSecretNames) {
		const value = environment[name];
		if (value !== undefined && value !== '') assertStrongSecret(name, value, false);
	}
}

function validateVersionedAuthSecrets(rawValue: string | undefined): void {
	if (rawValue === undefined || rawValue === '') return;

	const versions = new Set<number>();
	const entries = rawValue.split(',');
	if (entries.length === 0) throw invalidKeyring();

	for (const [index, entry] of entries.entries()) {
		const separatorIndex = entry.indexOf(':');
		if (separatorIndex < 1) throw invalidKeyring();

		const versionText = entry.slice(0, separatorIndex);
		if (!/^(?:0|[1-9]\d*)$/.test(versionText)) throw invalidKeyring();
		const version = Number(versionText);
		if (!Number.isSafeInteger(version) || versions.has(version)) throw invalidKeyring();
		versions.add(version);

		assertStrongSecret(
			`BETTER_AUTH_SECRETS entry ${index + 1}`,
			entry.slice(separatorIndex + 1),
			false
		);
	}
}

function assertStrongSecret(name: string, value: string | undefined, required: boolean): void {
	if (!value) {
		throw new Error(
			required
				? `${name} is required in production.`
				: `${name} must contain a non-empty generated runway secret when configured.`
		);
	}
	if (value !== value.trim() || /\s/u.test(value)) {
		throw new Error(`${name} must not contain whitespace.`);
	}
	if (!isCanonicalGeneratedSecret(value)) {
		throw new Error(
			`${name} must be generated with \`pnpm secret:generate\` (runway-secret-v1 plus 32 random bytes).`
		);
	}
}

function isCanonicalGeneratedSecret(value: string): boolean {
	if (!value.startsWith(secretPrefix)) return false;
	const encoded = value.slice(secretPrefix.length);
	if (encoded.length !== encodedSecretCharacters || !/^[A-Za-z0-9_-]+$/.test(encoded)) {
		return false;
	}
	const decoded = Buffer.from(encoded, 'base64url');
	return decoded.length === encodedSecretBytes && decoded.toString('base64url') === encoded;
}

function invalidKeyring(): Error {
	return new Error(
		'BETTER_AUTH_SECRETS must be a comma-separated keyring of unique non-negative versions and generated runway secrets.'
	);
}
