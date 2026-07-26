import { readFile } from 'node:fs/promises';

const workflow = await readFile('.github/workflows/container.yml', 'utf8');
const checkWorkflow = await readFile('.github/workflows/check.yml', 'utf8');
const browserWorkflow = await readFile('.github/workflows/browser.yml', 'utf8');
const dockerfile = await readFile('Dockerfile', 'utf8');
const dockerignore = await readFile('.dockerignore', 'utf8');
const imageVerifier = await readFile('scripts/verify-image.mjs', 'utf8');
const armImageVerifier = await readFile('scripts/verify-arm64-image.mjs', 'utf8');
const errors = [];

for (const [name, contents] of [
	['check', checkWorkflow],
	['browser', browserWorkflow]
]) {
	if (!contents.includes('workflow_call:')) {
		errors.push(`${name} workflow is not reusable by the release gate`);
	}
}

for (const releaseGatePath of [
	'tests/e2e/**',
	'tests/visual/**',
	'tests/support/**',
	'playwright.config.ts',
	'playwright.visual.config.ts'
]) {
	const occurrences = workflow.split(`- ${releaseGatePath}`).length - 1;
	if (occurrences !== 2) {
		errors.push(
			`container publication must react to ${releaseGatePath} changes on pull requests and pushes`
		);
	}
}

const imageJob = section(workflow, '  image:', '  android-build:');
for (const required of [
	'needs: [checks, browser, android-release]',
	'always()',
	"needs.checks.result == 'success'",
	"needs.browser.result == 'success'",
	"(startsWith(github.ref, 'refs/tags/v') == false || needs.android-release.result == 'success')",
	'platforms: linux/amd64,linux/arm64',
	'push: true',
	'tags: ${{ env.RUNWAY_CANDIDATE_IMAGE }}',
	'RUNWAY_CANDIDATE_DIGEST: ${{ steps.candidate.outputs.digest }}',
	'docker pull --platform linux/amd64 "$candidate_ref"',
	'echo "RUNWAY_VERIFIED_CANDIDATE=$candidate_ref" >> "$GITHUB_ENV"',
	'RUNWAY_MIGRATION_IMAGE: ${{ env.RUNWAY_IMAGE }}',
	'node scripts/verify-arm64-image.mjs "$RUNWAY_VERIFIED_CANDIDATE"',
	'docker buildx imagetools create "${tags[@]}" "$RUNWAY_VERIFIED_CANDIDATE"'
]) {
	if (!imageJob.includes(required)) errors.push(`release image job is missing: ${required}`);
}

const orderedSteps = [
	'Build immutable multi-architecture candidate',
	'Load exact published AMD64 candidate',
	'Scan local runtime candidate',
	'Verify whole-project Compose lifecycle',
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
if (!candidateBuild.includes('id: candidate')) {
	errors.push('published candidate digest is not captured from the one multi-architecture build');
}

const promotion = section(
	imageJob,
	'      - name: Promote exact verified candidate manifest',
	'  android-build:'
);
if (!promotion.includes("if: github.event_name != 'pull_request'")) {
	errors.push('pull requests are not excluded from final image promotion');
}
if (promotion.includes('"$RUNWAY_CANDIDATE_IMAGE"')) {
	errors.push('final promotion follows a mutable candidate tag instead of the verified digest');
}
if (
	!workflow.includes(
		'type=raw,value=latest,enable=${{ github.ref_name == github.event.repository.default_branch }}'
	)
) {
	errors.push('latest is not restricted to the default branch');
}
if (!workflow.includes('group: runway-container-${{ github.ref }}')) {
	errors.push('container publication does not cancel older runs for the same branch or tag');
}
if (!workflow.includes("make_latest: 'legacy'")) {
	errors.push(
		'GitHub Releases can move latest backward instead of using semantic-version ordering'
	);
}
if (!dockerfile.includes('/app/scripts/migration-state.mjs ./scripts/migration-state.mjs')) {
	errors.push('runtime image does not contain the shared migration-state validator');
}
if (!dockerfile.includes('FROM --platform=$BUILDPLATFORM node:')) {
	errors.push('architecture-neutral build stages are not pinned to the native build platform');
}
if (
	!imageVerifier.includes("for (const root of ['/app/build', '/app/node_modules'])") ||
	!imageVerifier.includes('Cross-platform build stages require architecture-neutral app artifacts')
) {
	errors.push('runtime image verification does not reject architecture-specific app artifacts');
}
if (
	!workflow.includes('RUNWAY_EXPECTED_BUILD_ID: ${{ github.sha }}') ||
	!workflow.includes('RUNWAY_ARM64_SITE_URL: http://127.0.0.1:4110')
) {
	errors.push(
		'published ARM64 verification is missing exact build identity or app startup coverage'
	);
}
if (
	!imageJob.includes('RUNWAY_CANDIDATE_DIGEST: ${{ steps.candidate.outputs.digest }}') ||
	!imageJob.includes('RUNWAY_VERIFIED_CANDIDATE=$candidate_ref') ||
	!armImageVerifier.includes(
		'Published ARM64 verification requires an immutable manifest digest'
	) ||
	!armImageVerifier.includes("candidate.platform?.architecture === 'arm64'")
) {
	errors.push('published image verification does not follow immutable platform manifest digests');
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
