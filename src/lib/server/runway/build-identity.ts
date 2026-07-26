import { version as build } from '$app/environment';
import { normalizeBuildCommit } from '$lib/build-metadata';
import packageMetadata from '../../../../package.json';

const normalizedBuild = build.trim();

export const buildIdentity = Object.freeze({
	release: packageMetadata.version,
	build: normalizedBuild,
	commit: normalizeBuildCommit(normalizedBuild),
	// Keep the existing health-contract field while making its meaning explicit.
	version: normalizedBuild
});
