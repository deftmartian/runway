import { spawn } from 'node:child_process';
import { resolve } from 'node:path';

const image = `runway-actionlint:${process.pid}`;
let built = false;

try {
	await run('docker', ['build', '--pull', '--file', 'Dockerfile.actionlint', '--tag', image, '.']);
	built = true;
	await run('docker', [
		'run',
		'--rm',
		'--network',
		'none',
		'--cap-drop',
		'ALL',
		'--security-opt',
		'no-new-privileges',
		'--read-only',
		'--volume',
		`${resolve('.')}:/repo:ro`,
		'--workdir',
		'/repo',
		image,
		'-color'
	]);
} finally {
	if (built) {
		await run('docker', ['image', 'rm', image]).catch((error) => {
			console.warn(`Could not remove temporary actionlint image: ${error.message}`);
		});
	}
}

console.log('GitHub Actions workflow syntax and expressions passed actionlint.');

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
