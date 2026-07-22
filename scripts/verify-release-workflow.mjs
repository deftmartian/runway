import { readFile } from 'node:fs/promises';

const workflow = await readFile('.github/workflows/container.yml', 'utf8');
const checkWorkflow = await readFile('.github/workflows/check.yml', 'utf8');
const browserWorkflow = await readFile('.github/workflows/browser.yml', 'utf8');
const dockerfile = await readFile('Dockerfile', 'utf8');
const dockerignore = await readFile('.dockerignore', 'utf8');
const errors = [];

for (const [name, contents] of [
	['check', checkWorkflow],
	['browser', browserWorkflow]
]) {
	if (!contents.includes('workflow_call:')) {
		errors.push(`${name} workflow is not reusable by the release gate`);
	}
}

const imageJob = section(workflow, '  image:', '  android-build:');
for (const required of [
	'needs: [checks, browser]',
	'platforms: linux/amd64,linux/arm64',
	'push: true',
	'tags: ${{ env.RUNWAY_CANDIDATE_IMAGE }}',
	'docker pull --platform linux/amd64 "$RUNWAY_CANDIDATE_IMAGE"',
	'RUNWAY_MIGRATION_IMAGE: ${{ env.RUNWAY_IMAGE }}',
	'docker run --rm --platform linux/arm64',
	'docker buildx imagetools create "${tags[@]}" "$RUNWAY_CANDIDATE_IMAGE"'
]) {
	if (!imageJob.includes(required)) errors.push(`release image job is missing: ${required}`);
}

const orderedSteps = [
	'Build immutable multi-architecture candidate',
	'Load exact published AMD64 candidate',
	'Scan local runtime candidate',
	'Start image-backed production stack',
	'Verify exact-image upgrades from both released migration histories',
	'Verify exact ARM64 candidate runtime and migration contract',
	'Promote exact verified candidate manifest'
];
let prior = -1;
for (const step of orderedSteps) {
	const position = imageJob.indexOf(`- name: ${step}`);
	if (position < 0) errors.push(`release image job is missing ordered step: ${step}`);
	else if (position <= prior) errors.push(`release image step is out of order: ${step}`);
	prior = Math.max(prior, position);
}

const candidateBuild = section(
	imageJob,
	'      - name: Build immutable multi-architecture candidate',
	'      - name: Load exact published AMD64 candidate'
);
if (candidateBuild.includes('steps.meta.outputs.tags')) {
	errors.push('unverified final image aliases are applied during the candidate build');
}

const promotion = section(
	imageJob,
	'      - name: Promote exact verified candidate manifest',
	'  android-build:'
);
if (!promotion.includes("if: github.event_name != 'pull_request'")) {
	errors.push('pull requests are not excluded from final image promotion');
}
if (
	!workflow.includes(
		'type=raw,value=latest,enable=${{ github.ref_name == github.event.repository.default_branch }}'
	)
) {
	errors.push('latest is not restricted to the default branch');
}
if (!workflow.includes("make_latest: 'legacy'")) {
	errors.push(
		'GitHub Releases can move latest backward instead of using semantic-version ordering'
	);
}
if (!dockerfile.includes('/app/scripts/migration-state.mjs ./scripts/migration-state.mjs')) {
	errors.push('runtime image does not contain the shared migration-state validator');
}
if (!dockerignore.split(/\r?\n/u).includes('android/')) {
	errors.push('Android sources and signing artifacts are not excluded from the web image context');
}

if (errors.length > 0) {
	console.error(`Release-workflow verification failed:\n- ${errors.join('\n- ')}`);
	process.exit(1);
}

console.log(
	'Release workflow builds once, tests exact candidates, and promotes only gated artifacts.'
);

function section(contents, startMarker, endMarker) {
	const start = contents.indexOf(startMarker);
	const end = contents.indexOf(endMarker, Math.max(0, start + startMarker.length));
	if (start < 0) return '';
	return contents.slice(start, end < 0 ? contents.length : end);
}
