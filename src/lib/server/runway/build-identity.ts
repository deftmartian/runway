import { version as build } from '$app/environment';
import packageMetadata from '../../../../package.json';

export const buildIdentity = Object.freeze({
	release: packageMetadata.version,
	build,
	// Keep the existing health-contract field while making its meaning explicit.
	version: build
});
