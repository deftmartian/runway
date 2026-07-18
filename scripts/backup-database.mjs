import { open, rm } from 'node:fs/promises';
import { resolve } from 'node:path';
import {
	assertPrivateBackupFile,
	configuredSourceConnection,
	inspectBackupArchive,
	runPostgresTool
} from './database-backup-lib.mjs';

const arguments_ = process.argv.slice(2);
if (arguments_[0] === '--') arguments_.shift();
const [outputArgument, unexpected] = arguments_;
if (!outputArgument || unexpected) {
	throw new Error('Usage: corepack pnpm db:backup -- <new-backup-file>');
}

const outputPath = resolve(outputArgument);
const connection = await configuredSourceConnection();
let file;
try {
	file = await open(outputPath, 'wx', 0o600);
	await runPostgresTool(
		connection,
		['pg_dump', '--format=custom', '--no-owner', '--no-privileges'],
		{ outputStream: file.createWriteStream() }
	);
	await file.close();
	file = undefined;
	await assertPrivateBackupFile(outputPath);
	await inspectBackupArchive(connection, outputPath);
} catch (error) {
	await file?.close().catch(() => undefined);
	await rm(outputPath, { force: true }).catch(() => undefined);
	throw error;
}

console.log('Private runway database backup created and archive inventory verified.');
