export type DatabaseRuntimeOptions = {
	max: number;
	connect_timeout: number;
	idle_timeout: number;
	max_lifetime: number;
	connection: {
		application_name: string;
		statement_timeout: number;
		idle_in_transaction_session_timeout: number;
	};
};

type IntegerSetting = {
	name: string;
	defaultValue: number;
	minimum: number;
	maximum: number;
};

const settings = {
	poolMax: {
		name: 'DATABASE_POOL_MAX',
		defaultValue: 10,
		minimum: 1,
		maximum: 50
	},
	connectTimeoutSeconds: {
		name: 'DATABASE_CONNECT_TIMEOUT_SECONDS',
		defaultValue: 10,
		minimum: 1,
		maximum: 60
	},
	idleTimeoutSeconds: {
		name: 'DATABASE_IDLE_TIMEOUT_SECONDS',
		defaultValue: 30,
		minimum: 1,
		maximum: 600
	},
	maxLifetimeSeconds: {
		name: 'DATABASE_MAX_LIFETIME_SECONDS',
		defaultValue: 1_800,
		minimum: 60,
		maximum: 86_400
	},
	statementTimeoutMs: {
		name: 'DATABASE_STATEMENT_TIMEOUT_MS',
		defaultValue: 30_000,
		minimum: 1_000,
		maximum: 300_000
	},
	idleTransactionTimeoutMs: {
		name: 'DATABASE_IDLE_TRANSACTION_TIMEOUT_MS',
		defaultValue: 30_000,
		minimum: 1_000,
		maximum: 300_000
	}
} satisfies Record<string, IntegerSetting>;

export function readDatabaseRuntimeOptions(
	environment: Record<string, string | undefined>,
	role: 'web' | 'worker' = 'web'
): DatabaseRuntimeOptions {
	return {
		max: readInteger(environment, settings.poolMax),
		connect_timeout: readInteger(environment, settings.connectTimeoutSeconds),
		idle_timeout: readInteger(environment, settings.idleTimeoutSeconds),
		max_lifetime: readInteger(environment, settings.maxLifetimeSeconds),
		connection: {
			application_name: role === 'worker' ? 'runway-worker' : 'runway-web',
			statement_timeout: readInteger(environment, settings.statementTimeoutMs),
			idle_in_transaction_session_timeout: readInteger(
				environment,
				settings.idleTransactionTimeoutMs
			)
		}
	};
}

function readInteger(
	environment: Record<string, string | undefined>,
	setting: IntegerSetting
): number {
	const configured = environment[setting.name]?.trim();
	if (!configured) return setting.defaultValue;
	if (!/^\d+$/.test(configured)) throw invalidSetting(setting);

	const value = Number(configured);
	if (!Number.isSafeInteger(value) || value < setting.minimum || value > setting.maximum) {
		throw invalidSetting(setting);
	}
	return value;
}

function invalidSetting(setting: IntegerSetting): Error {
	return new Error(
		`${setting.name} must be an integer from ${setting.minimum} to ${setting.maximum}.`
	);
}
