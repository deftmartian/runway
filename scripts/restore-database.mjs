import { resolve } from 'node:path';
import {
	assertDifferentDatabase,
	assertPrivateBackupFile,
	configuredSourceConnection,
	databaseIsEmpty,
	inspectBackupArchive,
	parsePostgresUrl,
	runPostgresTool,
	verifyRestoredDatabase
} from './database-backup-lib.mjs';

const arguments_ = process.argv.slice(2);
if (arguments_[0] === '--') arguments_.shift();
const [inputArgument, unexpected] = arguments_;
if (!inputArgument || unexpected) {
	throw new Error('Usage: corepack pnpm db:restore -- <backup-file>');
}
const restoreUrl = process.env['RUNWAY_RESTORE_DATABASE_URL'];
if (!restoreUrl) {
	throw new Error('RUNWAY_RESTORE_DATABASE_URL must identify a separate empty target database.');
}

const inputPath = resolve(inputArgument);
await assertPrivateBackupFile(inputPath);
const source = await configuredSourceConnection();
const target = parsePostgresUrl(restoreUrl, 'RUNWAY_RESTORE_DATABASE_URL');
assertDifferentDatabase(source, target);
await inspectBackupArchive(target, inputPath);
if (!(await databaseIsEmpty(target))) {
	throw new Error('The restore target is not empty. Create a new empty database and try again.');
}

await runPostgresTool(
	target,
	['pg_restore', '--dbname', target.database, '--exit-on-error', '--no-owner', '--no-privileges'],
	{ inputPath }
);
await verifyRestoredDatabase(target);

console.log('Backup restored into the explicitly configured empty target database and verified.');
