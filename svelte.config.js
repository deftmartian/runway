import adapter from '@sveltejs/adapter-node';

const configuredBuildId = process.env.RUNWAY_BUILD_ID?.trim();

/** @type {NonNullable<NonNullable<import('@sveltejs/kit').Config['kit']>['csp']>['directives']} */
const cspDirectives = {
	'default-src': ['self'],
	'base-uri': ['self'],
	'connect-src': ['self'],
	'font-src': ['self'],
	'form-action': ['self'],
	'frame-ancestors': ['none'],
	'img-src': ['self', 'data:'],
	'manifest-src': ['self'],
	'object-src': ['none'],
	'script-src': ['self', 'strict-dynamic'],
	'style-src': ['self'],
	'style-src-attr': ['unsafe-inline'],
	'worker-src': ['self'],
	'require-trusted-types-for': ['script'],
	'trusted-types': ['svelte-trusted-html', 'sveltekit-trusted-url', 'runway-service-worker']
};

/** @type {import('@sveltejs/kit').Config} */
const config = {
	compilerOptions: {
		// Force runes mode for the project, except for libraries. Can be removed in svelte 6.
		runes: ({ filename }) => (filename.split(/[/\\]/).includes('node_modules') ? undefined : true)
	},
	kit: {
		adapter: adapter({ out: process.env.RUNWAY_BUILD_DIR ?? 'build', precompress: true }),
		outDir: process.env.RUNWAY_KIT_OUT_DIR ?? '.svelte-kit',
		...(configuredBuildId ? { version: { name: configuredBuildId } } : {}),
		csp: {
			mode: 'nonce',
			directives: cspDirectives
		},
		csrf: {
			// Let the app hook reject state-changing cross-site requests so blocked
			// responses still carry runway's cache and security headers.
			trustedOrigins: ['*']
		},
		typescript: {
			config: (config) => ({
				...config,
				include: [...config.include, '../drizzle.config.ts']
			})
		}
	}
};

export default config;
