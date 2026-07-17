import { spawn } from 'node:child_process';
import { resolve } from 'node:path';

const scannerImage = 'runway-dependency-scanner:local';
const root = resolve('.');

await run('docker', ['build', '--pull', '--file', 'Dockerfile.audit', '--tag', scannerImage, '.']);

await run('docker', [
	'run',
	'--rm',
	'--network',
	'bridge',
	'--volume',
	`${resolve(root, 'package.json')}:/workspace/package.json:ro`,
	'--volume',
	`${resolve(root, 'pnpm-lock.yaml')}:/workspace/pnpm-lock.yaml:ro`,
	scannerImage,
	'fs',
	'--scanners',
	'vuln',
	'--pkg-types',
	'library',
	'--severity',
	'HIGH,CRITICAL',
	'--exit-code',
	'1',
	'--no-progress',
	'--skip-version-check',
	'/workspace'
]);

console.log('Production pnpm dependency graph has no known high or critical advisories.');

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
