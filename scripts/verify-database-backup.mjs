import { randomBytes } from 'node:crypto';
import { resolve } from 'node:path';
import {
	assertPrivateBackupFile,
	configuredSourceConnection,
	inspectBackupArchive,
	parsePostgresUrl,
	runPostgresTool,
	verifyRestoredDatabase,
	withDatabase
} from './database-backup-lib.mjs';

const arguments_ = process.argv.slice(2);
if (arguments_[0] === '--') arguments_.shift();
const [inputArgument, unexpected] = arguments_;
if (!inputArgument || unexpected) {
	throw new Error('Usage: corepack pnpm db:backup:verify -- <backup-file>');
}

const inputPath = resolve(inputArgument);
await assertPrivateBackupFile(inputPath);
const source = await configuredSourceConnection();
await inspectBackupArchive(source, inputPath);

const configuredAdminUrl = process.env['RUNWAY_BACKUP_ADMIN_DATABASE_URL'];
const admin = configuredAdminUrl
	? parsePostgresUrl(configuredAdminUrl, 'RUNWAY_BACKUP_ADMIN_DATABASE_URL')
	: source;
const temporaryDatabase = `runway_verify_${Date.now()}_${randomBytes(4).toString('hex')}`;
const postgres = withDatabase(admin, 'postgres');
const restored = withDatabase(admin, temporaryDatabase);
const quotedDatabase = `"${temporaryDatabase}"`;
let created = false;

try {
	await runPostgresTool(
		postgres,
		[
			'psql',
			'--no-psqlrc',
			'--set',
			'ON_ERROR_STOP=1',
			'--command',
			`create database ${quotedDatabase};`
		],
		{ captureOutput: true }
	);
	created = true;
	await runPostgresTool(
		restored,
		[
			'pg_restore',
			'--dbname',
			restored.database,
			'--exit-on-error',
			'--no-owner',
			'--no-privileges'
		],
		{ inputPath }
	);
	await verifyRestoredDatabase(restored);
} finally {
	if (created) {
		await runPostgresTool(
			postgres,
			[
				'psql',
				'--no-psqlrc',
				'--set',
				'ON_ERROR_STOP=1',
				'--command',
				`drop database if exists ${quotedDatabase} with (force);`
			],
			{ captureOutput: true }
		);
	}
}

console.log('Backup restored, checked against the current migration journal, and removed.');
