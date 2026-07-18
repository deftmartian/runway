import { describe, expect, test } from 'vitest';
import {
	assertDifferentDatabase,
	parsePostgresUrl,
	postgresComposeInvocation
} from '../../../../scripts/database-backup-lib.mjs';

describe('database backup safety', () => {
	test('parses an explicit PostgreSQL target without retaining the URL', () => {
		expect(
			parsePostgresUrl(
				'postgres://runway%20backup:p%40ss@db.example.test:5433/runway_prod?sslmode=verify-full',
				'TEST_DATABASE_URL'
			)
		).toEqual({
			host: 'db.example.test',
			port: '5433',
			user: 'runway backup',
			password: 'p@ss',
			database: 'runway_prod',
			sslMode: 'verify-full'
		});
	});

	test('rejects ambiguous targets and unsupported URL parameters', () => {
		expect(() => parsePostgresUrl('https://db/runway', 'TEST_DATABASE_URL')).toThrow(
			/postgres or postgresql scheme/
		);
		expect(() =>
			parsePostgresUrl(
				'postgres://runway:secret@db/runway?application_name=unexpected',
				'TEST_DATABASE_URL'
			)
		).toThrow(/unsupported connection parameter/);
	});

	test('passes the password through environment only, never command arguments', () => {
		const connection = parsePostgresUrl(
			'postgres://runway:private-value@db/runway',
			'TEST_DATABASE_URL'
		);
		const invocation = postgresComposeInvocation(connection, ['pg_dump', '--format=custom']);
		expect(invocation.arguments.join(' ')).not.toContain('private-value');
		expect(invocation.arguments).toContain('PGPASSWORD');
		expect(invocation.environment.PGPASSWORD).toBe('private-value');
	});

	test('refuses to restore over the source database even with another user', () => {
		const source = parsePostgresUrl('postgres://source:a@db/runway', 'SOURCE');
		const sameDatabase = parsePostgresUrl('postgres://restore:b@db/runway', 'TARGET');
		expect(() => {
			assertDifferentDatabase(source, sameDatabase);
		}).toThrow(/must not be/);
		expect(() => {
			assertDifferentDatabase(
				source,
				parsePostgresUrl('postgres://restore:b@db/runway_restore', 'TARGET')
			);
		}).not.toThrow();
	});
});
