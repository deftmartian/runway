import { describe, expect, test } from 'vitest';
import { readDatabaseRuntimeOptions } from './config';

describe('database runtime configuration', () => {
	test('uses bounded defaults and identifies the process role', () => {
		expect(readDatabaseRuntimeOptions({}, 'web')).toEqual({
			max: 10,
			connect_timeout: 10,
			idle_timeout: 30,
			max_lifetime: 1_800,
			connection: {
				application_name: 'runway-web',
				statement_timeout: 30_000,
				idle_in_transaction_session_timeout: 30_000
			}
		});
		expect(readDatabaseRuntimeOptions({}, 'worker').connection.application_name).toBe(
			'runway-worker'
		);
	});

	test('accepts explicit values within the documented bounds', () => {
		expect(
			readDatabaseRuntimeOptions({
				DATABASE_POOL_MAX: '6',
				DATABASE_CONNECT_TIMEOUT_SECONDS: '7',
				DATABASE_IDLE_TIMEOUT_SECONDS: '45',
				DATABASE_MAX_LIFETIME_SECONDS: '900',
				DATABASE_STATEMENT_TIMEOUT_MS: '12000',
				DATABASE_IDLE_TRANSACTION_TIMEOUT_MS: '13000'
			})
		).toMatchObject({
			max: 6,
			connect_timeout: 7,
			idle_timeout: 45,
			max_lifetime: 900,
			connection: {
				statement_timeout: 12_000,
				idle_in_transaction_session_timeout: 13_000
			}
		});
	});

	test.each([
		['DATABASE_POOL_MAX', '0'],
		['DATABASE_POOL_MAX', '51'],
		['DATABASE_CONNECT_TIMEOUT_SECONDS', '1.5'],
		['DATABASE_IDLE_TIMEOUT_SECONDS', '-1'],
		['DATABASE_MAX_LIFETIME_SECONDS', '59'],
		['DATABASE_STATEMENT_TIMEOUT_MS', '999'],
		['DATABASE_IDLE_TRANSACTION_TIMEOUT_MS', '300001']
	])('rejects unsafe %s=%s', (name, value) => {
		expect(() => readDatabaseRuntimeOptions({ [name]: value })).toThrow(name);
	});
});
