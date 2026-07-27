import { readFile } from 'node:fs/promises';
import { parse } from 'yaml';
import { classifyPaths } from './classify-ci-changes.mjs';
import { validateCiResults } from './verify-ci-results.mjs';

const ci = await readFile('.github/workflows/container.yml', 'utf8');
const check = await readFile('.github/workflows/check.yml', 'utf8');
const browser = await readFile('.github/workflows/browser.yml', 'utf8');
const maintenance = await readFile('.github/workflows/maintenance.yml', 'utf8');
const dockerfile = await readFile('Dockerfile', 'utf8');
const actionlintDockerfile = await readFile('Dockerfile.actionlint', 'utf8');
const dockerignore = await readFile('.dockerignore', 'utf8');
const imageVerifier = await readFile('scripts/verify-image.mjs', 'utf8');
const armImageVerifier = await readFile('scripts/verify-arm64-image.mjs', 'utf8');
const browserRunner = await readFile('scripts/run-browser-tests.mjs', 'utf8');
const playwrightConfig = await readFile('playwright.config.ts', 'utf8');
const errors = [];
const ciYaml = parse(ci);
const checkYaml = parse(check);
const browserYaml = parse(browser);
const maintenanceYaml = parse(maintenance);

assertExact('top-level CI triggers', Object.keys(ciYaml.on), [
	'pull_request',
	'push',
	'workflow_dispatch'
]);
assertExact('callable check triggers', Object.keys(checkYaml.on), ['workflow_call']);
assertExact('callable browser triggers', Object.keys(browserYaml.on), ['workflow_call']);
assertExact('top-level CI permissions', ciYaml.permissions, { contents: 'read' });
assertExact('check workflow permissions', checkYaml.permissions, { contents: 'read' });
assertExact('browser workflow permissions', browserYaml.permissions, { contents: 'read' });
assertExact('maintenance workflow permissions', maintenanceYaml.permissions, {
	contents: 'read',
	packages: 'read'
});
if (checkYaml.on.workflow_call.inputs.full.default !== true) {
	errors.push('callable check full input does not default to true');
}
const functionalJob = browserYaml.jobs.functional;
const visualJob = browserYaml.jobs.visual;
assertExact('browser jobs', Object.keys(browserYaml.jobs), ['functional', 'visual']);
assertExact('functional shard matrix', functionalJob.strategy.matrix.include, [
	{ id: 1, shard: '1/2' },
	{ id: 2, shard: '2/2' }
]);
if (functionalJob.strategy['fail-fast'] !== false) {
	errors.push('functional browser shards do not all run after one shard fails');
}
assertRequiredJob('functional browser', functionalJob);
assertRequiredJob('visual browser', visualJob);
const functionalStep = stepByName(functionalJob, 'Functional browser shard');
if (functionalStep?.run !== 'corepack pnpm test:e2e --shard=${{ matrix.shard }}') {
	errors.push('functional browser job does not run the declared Playwright shard');
}
const visualStep = stepByName(visualJob, 'Production-layout visual suite');
if (visualStep?.run !== 'corepack pnpm test:visual') {
	errors.push('visual browser job does not run the complete visual suite');
}

const imageVerifyYaml = ciYaml.jobs['image-verify'];
const imagePublishYaml = ciYaml.jobs['image-publish'];
assertExact('unprivileged image step inventory', imageVerifyYaml.steps.map(stepIdentity), [
	'actions/checkout',
	'actions/setup-node',
	'Enable pnpm',
	'Install migration verification dependencies',
	'Configure ephemeral auth secret',
	'Set up Buildx',
	'Build local unprivileged candidate',
	'Scan local runtime candidate',
	'Verify whole-project Compose lifecycle',
	'Start image-backed production stack',
	'Verify production runtime and PWA revision',
	'Container diagnostics',
	'Stop containers'
]);
assertExact('trusted image step inventory', imagePublishYaml.steps.map(stepIdentity), [
	'actions/checkout',
	'actions/setup-node',
	'Enable pnpm',
	'Install migration verification dependencies',
	'Configure ephemeral auth secret',
	'Set up QEMU for published candidates',
	'Set up Buildx',
	'Log in to GitHub Container Registry',
	'Generate verified image metadata',
	'Build immutable multi-architecture candidate',
	'Load exact published AMD64 candidate',
	'Scan local runtime candidate',
	'Verify whole-project Compose lifecycle',
	'Start image-backed production stack',
	'Verify production runtime and PWA revision',
	'Verify exact ARM64 candidate runtime and migration contract',
	'Container diagnostics',
	'Stop containers',
	'Promote exact verified candidate manifest'
]);
assertExact('unprivileged image dependencies', imageVerifyYaml.needs, [
	'scope',
	'checks',
	'browser'
]);
assertExact('trusted image dependencies', imagePublishYaml.needs, [
	'scope',
	'checks',
	'browser',
	'release-source',
	'android-release'
]);
assertExact('unprivileged image permissions', imageVerifyYaml.permissions, { contents: 'read' });
assertExact('trusted image permissions', imagePublishYaml.permissions, {
	contents: 'read',
	packages: 'write'
});
for (const required of [
	'always()',
	"github.event_name != 'push'",
	"github.ref != format('refs/heads/{0}', github.event.repository.default_branch)",
	"startsWith(github.ref, 'refs/tags/v') == false",
	"needs.scope.result == 'success'",
	"needs.scope.outputs.image == 'true'",
	"needs.checks.result == 'success'",
	"needs.browser.result == 'success'"
]) {
	requireText(
		String(imageVerifyYaml.if),
		required,
		`unprivileged image condition includes ${required}`
	);
}
for (const required of [
	'always()',
	"github.event_name == 'push'",
	"github.ref == format('refs/heads/{0}', github.event.repository.default_branch)",
	"startsWith(github.ref, 'refs/tags/v')",
	"needs.scope.result == 'success'",
	"needs.scope.outputs.image == 'true'",
	"needs.checks.result == 'success'",
	"needs.browser.result == 'success'",
	"needs.release-source.result == 'success'",
	"needs.android-release.result == 'success'"
]) {
	requireText(
		String(imagePublishYaml.if),
		required,
		`trusted image condition includes ${required}`
	);
}
assertExact(
	'unprivileged image condition',
	normalizeExpression(imageVerifyYaml.if),
	"${{always()&&(github.event_name!='push'||(github.ref!=format('refs/heads/{0}',github.event.repository.default_branch)&&startsWith(github.ref,'refs/tags/v')==false))&&needs.scope.result=='success'&&needs.scope.outputs.image=='true'&&needs.checks.result=='success'&&needs.browser.result=='success'}}"
);
assertExact(
	'trusted image condition',
	normalizeExpression(imagePublishYaml.if),
	"${{always()&&github.event_name=='push'&&(github.ref==format('refs/heads/{0}',github.event.repository.default_branch)||startsWith(github.ref,'refs/tags/v'))&&needs.scope.result=='success'&&needs.scope.outputs.image=='true'&&needs.checks.result=='success'&&needs.browser.result=='success'&&(startsWith(github.ref,'refs/tags/v')==false||(needs.release-source.result=='success'&&needs.android-release.result=='success'))}}"
);
assertNoContinueOnError('unprivileged image verification', imageVerifyYaml);
assertNoContinueOnError('trusted image publication', imagePublishYaml);

const candidateStepYaml = stepByName(
	imagePublishYaml,
	'Build immutable multi-architecture candidate'
);
const localCandidateStepYaml = stepByName(imageVerifyYaml, 'Build local unprivileged candidate');
const loadStepYaml = stepByName(imagePublishYaml, 'Load exact published AMD64 candidate');
const promotionStepYaml = stepByName(imagePublishYaml, 'Promote exact verified candidate manifest');
assertExact(
	'unprivileged candidate build action',
	localCandidateStepYaml?.uses,
	'docker/build-push-action@53b7df96c91f9c12dcc8a07bcb9ccacbed38856a'
);
assertExact('unprivileged candidate build inputs', localCandidateStepYaml?.with, {
	context: '.',
	platforms: 'linux/amd64',
	load: true,
	push: false,
	tags: '${{ env.RUNWAY_IMAGE }}',
	'build-args': 'RUNWAY_BUILD_ID=${{ github.sha }}',
	provenance: false,
	'cache-from': 'type=gha,scope=runway-container-untrusted',
	'cache-to': 'type=gha,mode=max,scope=runway-container-untrusted'
});
assertExact(
	'trusted candidate build action',
	candidateStepYaml?.uses,
	'docker/build-push-action@53b7df96c91f9c12dcc8a07bcb9ccacbed38856a'
);
assertExact('trusted candidate build inputs', candidateStepYaml?.with, {
	context: '.',
	platforms: 'linux/amd64,linux/arm64',
	push: true,
	tags: '${{ env.RUNWAY_CANDIDATE_IMAGE }}',
	'build-args': 'RUNWAY_BUILD_ID=${{ github.sha }}',
	provenance: 'mode=max',
	sbom: true,
	'cache-from': 'type=gha,scope=runway-container-release',
	'cache-to': 'type=gha,mode=max,scope=runway-container-release'
});
if (
	candidateStepYaml?.id !== 'candidate' ||
	candidateStepYaml?.with?.push !== true ||
	candidateStepYaml?.with?.platforms !== 'linux/amd64,linux/arm64'
) {
	errors.push('trusted candidate build is not one captured, pushed multi-architecture artifact');
}
if (
	loadStepYaml?.env?.RUNWAY_CANDIDATE_DIGEST !== '${{ steps.candidate.outputs.digest }}' ||
	!String(loadStepYaml?.run).includes('RUNWAY_VERIFIED_CANDIDATE=$candidate_ref')
) {
	errors.push('published candidate digest is not bound to the verified local image');
}
assertExact('candidate load environment', loadStepYaml?.env, {
	RUNWAY_CANDIDATE_DIGEST: '${{ steps.candidate.outputs.digest }}'
});
if (
	promotionStepYaml?.['continue-on-error'] !== undefined ||
	!String(promotionStepYaml?.run).includes(
		'docker buildx imagetools create "${tags[@]}" "$RUNWAY_VERIFIED_CANDIDATE"'
	)
) {
	errors.push('final promotion is optional or does not consume the verified immutable candidate');
}
assertExact('promotion environment', promotionStepYaml?.env, {
	FINAL_IMAGE_TAGS: '${{ steps.meta.outputs.tags }}'
});
assertRun(
	imagePublishYaml,
	'Load exact published AMD64 candidate',
	[
		'[[ "$RUNWAY_CANDIDATE_DIGEST" =~ ^sha256:[0-9a-f]{64}$ ]]',
		'candidate_ref="${REGISTRY}/${IMAGE_NAME}@${RUNWAY_CANDIDATE_DIGEST}"',
		'docker pull --platform linux/amd64 "$candidate_ref"',
		'docker tag "$candidate_ref" "$RUNWAY_IMAGE"',
		'echo "RUNWAY_VERIFIED_CANDIDATE=$candidate_ref" >> "$GITHUB_ENV"'
	].join('\n')
);
for (const job of [imageVerifyYaml, imagePublishYaml]) {
	for (const [name, run] of [
		['Scan local runtime candidate', 'node scripts/verify-image.mjs "${RUNWAY_IMAGE}"'],
		['Verify whole-project Compose lifecycle', 'node scripts/verify-compose-lifecycle.mjs'],
		[
			'Start image-backed production stack',
			'docker compose -f compose.yaml -f deploy/compose.production.yaml up -d --wait app worker'
		],
		[
			'Verify production runtime and PWA revision',
			'SITE_URL=http://127.0.0.1:4100 node scripts/verify-preview.mjs'
		],
		[
			'Stop containers',
			'docker compose -f compose.yaml -f deploy/compose.production.yaml down --volumes'
		]
	]) {
		assertRun(job, name, run, name === 'Stop containers' ? 'always()' : undefined);
	}
	for (const name of [
		'Scan local runtime candidate',
		'Start image-backed production stack',
		'Verify production runtime and PWA revision',
		'Stop containers'
	]) {
		assertExact(`${name} environment`, stepByName(job, name)?.env, undefined);
	}
	assertExact(
		'Compose lifecycle environment',
		stepByName(job, 'Verify whole-project Compose lifecycle')?.env,
		{ RUNWAY_COMPOSE_TEST_IMAGE: '${{ env.RUNWAY_IMAGE }}' }
	);
}
assertRun(
	imagePublishYaml,
	'Verify exact ARM64 candidate runtime and migration contract',
	[
		'db_container="$(docker compose -f compose.yaml -f deploy/compose.production.yaml ps -q db)"',
		'test -n "$db_container"',
		`db_address="$(docker inspect --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$db_container")"`,
		`[[ "$db_address" =~ ^[0-9]+(\\.[0-9]+){3}$ ]]`,
		'DATABASE_URL="postgres://runway:${POSTGRES_PASSWORD}@${db_address}:5432/runway" \\',
		'  node scripts/verify-arm64-image.mjs "$RUNWAY_VERIFIED_CANDIDATE"'
	].join('\n')
);
assertExact(
	'ARM64 verification environment',
	stepByName(imagePublishYaml, 'Verify exact ARM64 candidate runtime and migration contract')?.env,
	{
		RUNWAY_EXPECTED_BUILD_ID: '${{ github.sha }}',
		RUNWAY_ARM64_SITE_URL: 'http://127.0.0.1:4110'
	}
);
assertRun(
	imagePublishYaml,
	'Promote exact verified candidate manifest',
	[
		'tags=()',
		'while IFS= read -r tag; do',
		'  if [ -n "$tag" ]; then tags+=(--tag "$tag"); fi',
		'done <<< "$FINAL_IMAGE_TAGS"',
		'test "${#tags[@]}" -gt 0',
		'docker buildx imagetools create "${tags[@]}" "$RUNWAY_VERIFIED_CANDIDATE"'
	].join('\n')
);
if (
	occurrences(section(ci, '  image-publish:', '  android-build:'), 'RUNWAY_VERIFIED_CANDIDATE=') !==
	1
) {
	errors.push('verified candidate reference can be reassigned after digest capture');
}

const releaseSourceYaml = ciYaml.jobs['release-source'];
assertExact('release-source permissions', releaseSourceYaml.permissions, { contents: 'read' });
if (
	releaseSourceYaml.if !== "github.event_name == 'push' && startsWith(github.ref, 'refs/tags/v')"
) {
	errors.push('release-source ancestry runs outside the exact version-tag push boundary');
}
if (
	!String(
		stepByName(releaseSourceYaml, 'Require the tagged commit in default-branch history')?.run
	).includes('git merge-base --is-ancestor')
) {
	errors.push('version tags are not required to point into default-branch history');
}
if (ciYaml.jobs['android-build'].needs !== 'release-source') {
	errors.push('unsigned Android release build is not gated on tag ancestry');
}
assertExact('GitHub release dependencies', ciYaml.jobs.release.needs, [
	'image-publish',
	'android-release'
]);
assertExact('aggregate CI dependencies', ciYaml.jobs['ci-result'].needs, [
	'scope',
	'checks',
	'browser',
	'release-source',
	'image-verify',
	'image-publish',
	'android-build',
	'android-release',
	'release'
]);

for (const [jobId, job] of Object.entries(ciYaml.jobs)) {
	const permissions = job.permissions ?? ciYaml.permissions;
	const writes = Object.entries(permissions)
		.filter(([, access]) => access === 'write')
		.map(([scope]) => scope);
	const allowed =
		jobId === 'image-publish' ? ['packages'] : jobId === 'release' ? ['contents'] : [];
	assertExact(`${jobId} write permissions`, writes, allowed);
}

for (const [name, contents] of [
	['check', check],
	['browser', browser]
]) {
	requireText(contents, 'workflow_call:', `${name} workflow is reusable`);
	for (const trigger of ['pull_request', 'push', 'workflow_dispatch']) {
		if (new RegExp(`^ {2}${trigger}:`, 'mu').test(contents)) {
			errors.push(`${name} workflow has a direct ${trigger} trigger and would duplicate CI`);
		}
	}
}

for (const trigger of ['pull_request:', 'push:', 'workflow_dispatch:']) {
	requireText(ci, trigger, `top-level CI includes ${trigger}`);
}
if (/^ {4}paths(?:-ignore)?:/mu.test(section(ci, 'on:', 'concurrency:'))) {
	errors.push('top-level CI can be skipped by a path filter instead of returning a stable result');
}

const scopeJob = section(ci, '  scope:', '  checks:');
for (const required of [
	'fetch-depth: 0',
	'persist-credentials: false',
	'id: scope',
	'node scripts/classify-ci-changes.mjs'
]) {
	requireText(scopeJob, required, `change classifier includes ${required}`);
}

const scopeCases = [
	{
		name: 'documentation-only',
		paths: ['README.md', 'docs/DEPLOYMENT.md'],
		expected: { full: false, browser: false, image: false }
	},
	{
		name: 'Android-only',
		paths: ['android/app/src/main/AndroidManifest.xml'],
		expected: { full: true, browser: false, image: false }
	},
	{
		name: 'web source',
		paths: ['src/routes/+page.svelte'],
		expected: { full: true, browser: true, image: true }
	},
	{
		name: 'release workflow',
		paths: ['.github/workflows/container.yml'],
		expected: { full: true, browser: true, image: true }
	},
	{
		name: 'unknown build input',
		paths: ['future-build-tool.config.js'],
		expected: { full: true, browser: true, image: true }
	}
];
for (const testCase of scopeCases) {
	assertScope(testCase.name, classifyPaths(testCase.paths), testCase.expected);
}
assertScope('forced tag or manual run', classifyPaths(['README.md'], { forceFull: true }), {
	full: true,
	browser: true,
	image: true
});

const skippedReleaseResults = {
	scope: 'success',
	checks: 'success',
	browser: 'skipped',
	imageVerify: 'skipped',
	imagePublish: 'skipped',
	releaseSource: 'skipped',
	androidBuild: 'skipped',
	androidRelease: 'skipped',
	release: 'skipped'
};
assertCiResult('documentation pull request', {
	eventName: 'pull_request',
	ref: 'refs/pull/1/merge',
	defaultBranch: 'main',
	browserRequired: 'false',
	imageRequired: 'false',
	results: skippedReleaseResults
});
assertCiResult('source pull request', {
	eventName: 'pull_request',
	ref: 'refs/pull/2/merge',
	defaultBranch: 'main',
	browserRequired: 'true',
	imageRequired: 'true',
	results: {
		...skippedReleaseResults,
		browser: 'success',
		imageVerify: 'success'
	}
});
assertCiResult('default-branch publication', {
	eventName: 'push',
	ref: 'refs/heads/main',
	defaultBranch: 'main',
	browserRequired: 'true',
	imageRequired: 'true',
	results: {
		...skippedReleaseResults,
		browser: 'success',
		imagePublish: 'success'
	}
});
assertCiResult('non-default branch verification', {
	eventName: 'push',
	ref: 'refs/heads/master',
	defaultBranch: 'main',
	browserRequired: 'true',
	imageRequired: 'true',
	results: {
		...skippedReleaseResults,
		browser: 'success',
		imageVerify: 'success'
	}
});
assertCiResult('version release', {
	eventName: 'push',
	ref: 'refs/tags/v1.2.3',
	defaultBranch: 'main',
	browserRequired: 'true',
	imageRequired: 'true',
	results: {
		...skippedReleaseResults,
		browser: 'success',
		imagePublish: 'success',
		releaseSource: 'success',
		androidBuild: 'success',
		androidRelease: 'success',
		release: 'success'
	}
});
const missingImageGate = validateCiResults({
	eventName: 'pull_request',
	ref: 'refs/pull/3/merge',
	defaultBranch: 'main',
	browserRequired: 'true',
	imageRequired: 'true',
	results: { ...skippedReleaseResults, browser: 'success' }
});
if (!missingImageGate.some((error) => error.includes('unprivileged image verification'))) {
	errors.push('aggregate CI result does not reject a skipped required image gate');
}

for (const required of [
	'name: documentation',
	'node scripts/verify-docs.mjs',
	'if: inputs.full',
	'name: web quality',
	'name: deployment contracts',
	'name: Android'
]) {
	requireText(check, required, `check workflow includes ${required}`);
}

const functionalBrowser = section(browser, '  functional:', '  visual:');
for (const required of [
	"shard: '1/2'",
	"shard: '2/2'",
	'fail-fast: false',
	'fullyParallel: true',
	'workers: 1',
	'RUNWAY_TEST_RUN_ID: e2e_shard_',
	'corepack pnpm test:e2e --shard=${{ matrix.shard }}',
	'browser-functional-diagnostics-${{ matrix.id }}-${{ github.run_attempt }}',
	'retention-days: 7'
]) {
	const source = ['fullyParallel: true', 'workers: 1'].includes(required)
		? playwrightConfig
		: functionalBrowser;
	requireText(source, required, `functional browser contract includes ${required}`);
}
const visualBrowser = section(browser, '  visual:', undefined);
for (const required of [
	'name: visual browser',
	'RUNWAY_TEST_RUN_ID: visual_',
	'corepack pnpm test:visual',
	'name: browser-visual-diagnostics-${{ github.run_attempt }}',
	'retention-days: 7'
]) {
	requireText(visualBrowser, required, `visual browser contract includes ${required}`);
}
for (const required of [
	'createIsolatedDatabase',
	'dropIsolatedDatabase',
	'RUNWAY_BUILD_DIR',
	'RUNWAY_KIT_OUT_DIR',
	'RUNWAY_PREVIEW_DIR'
]) {
	requireText(browserRunner, required, `browser shard isolation includes ${required}`);
}

const imageVerify = section(ci, '  image-verify:', '  image-publish:');
for (const required of [
	"github.event_name != 'push'",
	'permissions:',
	'contents: read',
	'push: false',
	'scope=runway-container-untrusted',
	'Build local unprivileged candidate'
]) {
	requireText(imageVerify, required, `unprivileged image job includes ${required}`);
}
for (const forbidden of ['packages: write', 'docker/login-action@', 'push: true']) {
	if (imageVerify.includes(forbidden)) {
		errors.push(`unprivileged image job must not include ${forbidden}`);
	}
}

const imagePublish = section(ci, '  image-publish:', '  android-build:');
for (const required of [
	"github.event_name == 'push'",
	'packages: write',
	'docker/login-action@',
	'platforms: linux/amd64,linux/arm64',
	'push: true',
	'scope=runway-container-release',
	'RUNWAY_CANDIDATE_DIGEST: ${{ steps.candidate.outputs.digest }}',
	'docker pull --platform linux/amd64 "$candidate_ref"',
	'echo "RUNWAY_VERIFIED_CANDIDATE=$candidate_ref" >> "$GITHUB_ENV"',
	'node scripts/verify-arm64-image.mjs "$RUNWAY_VERIFIED_CANDIDATE"',
	'docker buildx imagetools create "${tags[@]}" "$RUNWAY_VERIFIED_CANDIDATE"'
]) {
	requireText(imagePublish, required, `trusted image job includes ${required}`);
}

for (const [name, job, steps] of [
	[
		'unprivileged image verification',
		imageVerify,
		[
			'Build local unprivileged candidate',
			'Scan local runtime candidate',
			'Verify whole-project Compose lifecycle',
			'Start image-backed production stack',
			'Verify production runtime and PWA revision',
			'Stop containers'
		]
	],
	[
		'trusted image publication',
		imagePublish,
		[
			'Build immutable multi-architecture candidate',
			'Load exact published AMD64 candidate',
			'Scan local runtime candidate',
			'Verify whole-project Compose lifecycle',
			'Start image-backed production stack',
			'Verify production runtime and PWA revision',
			'Verify exact ARM64 candidate runtime and migration contract',
			'Stop containers',
			'Promote exact verified candidate manifest'
		]
	]
]) {
	assertOrderedSteps(name, job, steps);
}

const candidateBuild = section(
	imagePublish,
	'      - name: Build immutable multi-architecture candidate',
	'      - name: Load exact published AMD64 candidate'
);
if (candidateBuild.includes('steps.meta.outputs.tags')) {
	errors.push('unverified final image aliases are applied during the candidate build');
}
requireText(candidateBuild, 'id: candidate', 'published candidate digest is captured once');

const promotion = section(
	imagePublish,
	'      - name: Promote exact verified candidate manifest',
	undefined
);
if (promotion.includes('"$RUNWAY_CANDIDATE_IMAGE"')) {
	errors.push('final promotion follows a mutable candidate tag instead of the verified digest');
}

for (const required of [
	'type=raw,value=latest,enable=${{ github.ref_name == github.event.repository.default_branch }}',
	'group: runway-ci-${{ github.ref }}',
	"if: github.event_name == 'push' && startsWith(github.ref, 'refs/tags/v')",
	'needs: [image-publish, android-release]',
	'name: CI result',
	'name: Require every applicable gate',
	'node scripts/verify-ci-results.mjs'
]) {
	requireText(ci, required, `top-level CI contract includes ${required}`);
}
requireText(ci, "make_latest: 'legacy'", 'release ordering follows semantic versions');

for (const [name, contents] of [
	['CI', ci],
	['check', check],
	['browser', browser],
	['maintenance', maintenance]
]) {
	const checkouts = occurrences(contents, 'uses: actions/checkout@');
	const nonPersisting = occurrences(contents, 'persist-credentials: false');
	if (checkouts !== nonPersisting) {
		errors.push(
			`${name} workflow persists credentials in ${checkouts - nonPersisting} checkout(s)`
		);
	}
	for (const match of contents.matchAll(/uses:\s+([^\s#]+)/gu)) {
		const action = match[1];
		if (action.startsWith('./')) continue;
		if (!/@[0-9a-f]{40}$/u.test(action)) {
			errors.push(`${name} workflow action is not pinned to a full commit: ${action}`);
		}
	}
}

for (const forbidden of ['/var/run/docker.sock', 'docker.sock:']) {
	if (imageVerifier.includes(forbidden)) {
		errors.push(`image scanner exposes the Docker socket through ${forbidden}`);
	}
}
for (const required of [
	"['save', '--output', archive, image]",
	"'--input'",
	"'--cap-drop'",
	"'no-new-privileges'",
	"'--read-only'"
]) {
	requireText(imageVerifier, required, `image scanner isolation includes ${required}`);
}

if (!dockerfile.includes('FROM --platform=$BUILDPLATFORM node:')) {
	errors.push('architecture-neutral build stages are not pinned to the native build platform');
}
if (
	!actionlintDockerfile.includes(
		'FROM rhysd/actionlint:1.7.12@sha256:b1934ee5f1c509618f2508e6eb47ee0d3520686341fec936f3b79331f9315667'
	)
) {
	errors.push('actionlint is not pinned to the reviewed multi-architecture image digest');
}
if (
	!imageVerifier.includes("for (const root of ['/app/build', '/app/node_modules'])") ||
	!imageVerifier.includes('Cross-platform build stages require architecture-neutral app artifacts')
) {
	errors.push('runtime image verification does not reject architecture-specific app artifacts');
}
if (
	!imagePublish.includes('RUNWAY_EXPECTED_BUILD_ID: ${{ github.sha }}') ||
	!imagePublish.includes('RUNWAY_ARM64_SITE_URL: http://127.0.0.1:4110') ||
	!imagePublish.includes(
		`db_address="$(docker inspect --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$db_container")"`
	) ||
	!imagePublish.includes(
		'DATABASE_URL="postgres://runway:${POSTGRES_PASSWORD}@${db_address}:5432/runway"'
	) ||
	imagePublish.includes('@127.0.0.1:5432/runway') ||
	!armImageVerifier.includes(
		'Published ARM64 verification requires an immutable manifest digest'
	) ||
	!armImageVerifier.includes("candidate.platform?.architecture === 'arm64'")
) {
	errors.push('published ARM64 verification lacks immutable identity or full app coverage');
}
if (!dockerignore.split(/\r?\n/u).includes('android/')) {
	errors.push('Android sources and signing artifacts are not excluded from the web image context');
}

if (errors.length > 0) {
	console.error(`Release-workflow verification failed:\n- ${errors.join('\n- ')}`);
	process.exit(1);
}

console.log(
	'CI workflow is non-duplicative, least-privilege, sharded, and fail-closed through exact artifact promotion.'
);

function assertScope(name, actual, expected) {
	for (const key of ['full', 'browser', 'image']) {
		if (actual[key] !== expected[key]) {
			errors.push(`${name} scope ${key} was ${actual[key]}, expected ${expected[key]}`);
		}
	}
}

function assertCiResult(name, input) {
	const resultErrors = validateCiResults(input);
	if (resultErrors.length > 0) {
		errors.push(`${name} aggregate result failed: ${resultErrors.join('; ')}`);
	}
}

function assertExact(name, actual, expected) {
	if (JSON.stringify(actual) !== JSON.stringify(expected)) {
		errors.push(`${name} was ${JSON.stringify(actual)}, expected ${JSON.stringify(expected)}`);
	}
}

function assertRequiredJob(name, job) {
	if (job.if !== undefined || job['continue-on-error'] !== undefined) {
		errors.push(`${name} job can be skipped or allowed to fail`);
	}
	for (const step of job.steps ?? []) {
		if (step['continue-on-error'] !== undefined) {
			errors.push(`${name} step can be allowed to fail: ${step.name ?? step.uses ?? 'unnamed'}`);
		}
	}
}

function assertNoContinueOnError(name, job) {
	if (job['continue-on-error'] !== undefined) {
		errors.push(`${name} job can ignore a failure`);
	}
	for (const step of job.steps ?? []) {
		if (step['continue-on-error'] !== undefined) {
			errors.push(`${name} can ignore failure in ${step.name ?? step.uses ?? 'unnamed step'}`);
		}
	}
}

function assertRun(job, name, expectedRun, expectedIf) {
	const step = stepByName(job, name);
	if (String(step?.run).trim() !== expectedRun || step?.if !== expectedIf) {
		errors.push(`${name} command or condition differs from the reviewed release contract`);
	}
}

function normalizeExpression(value) {
	return String(value).replace(/\s+/gu, '');
}

function stepByName(job, name) {
	return job?.steps?.find((step) => step.name === name);
}

function stepIdentity(step) {
	return step.name ?? step.uses?.split('@', 1)[0] ?? 'unnamed';
}

function assertOrderedSteps(name, contents, steps) {
	let prior = -1;
	for (const step of steps) {
		const position = contents.indexOf(`- name: ${step}`);
		if (position < 0) errors.push(`${name} is missing ordered step: ${step}`);
		else if (position <= prior) errors.push(`${name} step is out of order: ${step}`);
		prior = Math.max(prior, position);
	}
}

function requireText(contents, required, label) {
	if (!contents.includes(required)) errors.push(`${label} is missing`);
}

function occurrences(contents, needle) {
	return contents.split(needle).length - 1;
}

function section(contents, startMarker, endMarker) {
	const start = contents.indexOf(startMarker);
	if (start < 0) return '';
	if (endMarker === undefined) return contents.slice(start);
	const end = contents.indexOf(endMarker, start + startMarker.length);
	return contents.slice(start, end < 0 ? contents.length : end);
}
