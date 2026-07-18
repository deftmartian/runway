import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

const scannerImage = 'runway-dependency-scanner:local';
const root = resolve('.');
const scanDirectory = mkdtempSync(join(tmpdir(), 'runway-dependency-scan-'));
const androidRuntimeLock = join(scanDirectory, 'gradle.lockfile');

try {
	writeFileSync(androidRuntimeLock, releaseRuntimeGradleLock(), { encoding: 'utf8', mode: 0o600 });
	await run('docker', [
		'build',
		'--pull',
		'--file',
		'Dockerfile.audit',
		'--tag',
		scannerImage,
		'.'
	]);

	await run('docker', [
		'run',
		'--rm',
		'--network',
		'bridge',
		'--volume',
		`${resolve(root, 'package.json')}:/workspace/package.json:ro`,
		'--volume',
		`${resolve(root, 'pnpm-lock.yaml')}:/workspace/pnpm-lock.yaml:ro`,
		'--volume',
		`${scanDirectory}:/workspace/android-release:ro`,
		scannerImage,
		'fs',
		'--scanners',
		'vuln',
		'--pkg-types',
		'library',
		'--severity',
		'MEDIUM,HIGH,CRITICAL',
		'--exit-code',
		'1',
		'--no-progress',
		'--skip-version-check',
		'/workspace'
	]);
} finally {
	rmSync(scanDirectory, { recursive: true, force: true });
}

console.log(
	'Production pnpm and Android release dependency graphs have no known moderate, high, or critical advisories.'
);

function releaseRuntimeGradleLock() {
	const source = readFileSync(resolve(root, 'android/app/gradle.lockfile'), 'utf8');
	const lockedDependencies = source.split(/\r?\n/).filter((line) => {
		if (!line || line.startsWith('#')) return false;
		const separator = line.lastIndexOf('=');
		if (separator < 1) return false;
		return line
			.slice(separator + 1)
			.split(',')
			.includes('releaseRuntimeClasspath');
	});
	if (lockedDependencies.length === 0) {
		throw new Error('Android dependency lock has no releaseRuntimeClasspath entries.');
	}
	return [
		'# Generated in memory from android/app/gradle.lockfile for release advisory scanning.',
		...lockedDependencies,
		''
	].join('\n');
}

function run(command, args) {
	return new Promise((resolveRun, reject) => {
		const child = spawn(command, args, { stdio: 'inherit' });
		child.once('error', reject);
		child.once('exit', (code, signal) => {
			if (code === 0) resolveRun();
			else reject(new Error(`${command} exited with ${signal ?? code ?? 'unknown status'}.`));
		});
	});
}
