import { defineConfig } from '@playwright/test';
import { testDate } from './tests/support/test-clock';

const previewUrl = process.env['PLAYWRIGHT_BASE_URL'] ?? 'http://127.0.0.1:4174';
const preview = new URL(previewUrl);
const runId = process.env['RUNWAY_TEST_RUN_ID'] ?? `${process.pid}`;

export default defineConfig({
	testDir: 'tests/visual',
	testMatch: '**/*.visual.ts',
	forbidOnly: Boolean(process.env['CI']),
	workers: 1,
	expect: {
		toHaveScreenshot: {
			maxDiffPixelRatio: 0.005,
			threshold: 0.15
		}
	},
	use: {
		baseURL: previewUrl,
		colorScheme: 'light',
		launchOptions: {
			args: [`--explicitly-allowed-ports=${preview.port || '80,443'}`]
		},
		trace: 'retain-on-failure'
	},
	webServer: {
		command: 'corepack pnpm build && corepack pnpm preview',
		url: previewUrl,
		env: {
			DATABASE_URL:
				process.env['DATABASE_URL'] ??
				'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
			BETTER_AUTH_SECRET:
				process.env['BETTER_AUTH_SECRET'] ?? 'local-dev-secret-for-runway-tests-please-change',
			ORIGIN: process.env['ORIGIN'] ?? previewUrl,
			PUBLIC_APP_ORIGIN: process.env['PUBLIC_APP_ORIGIN'] ?? previewUrl,
			HOST: process.env['HOST'] ?? preview.hostname,
			PORT: process.env['PORT'] ?? (preview.port || '80'),
			RUNWAY_BUILD_DIR:
				process.env['RUNWAY_BUILD_DIR'] ?? `.runway-live/playwright-visual-build-${runId}`,
			RUNWAY_KIT_OUT_DIR:
				process.env['RUNWAY_KIT_OUT_DIR'] ?? `.svelte-kit-playwright-visual-${runId}`,
			RUNWAY_PREVIEW_DIR:
				process.env['RUNWAY_PREVIEW_DIR'] ?? `.runway-live/playwright-visual-${runId}`,
			RUNWAY_FIXED_DATE: testDate,
			BODY_SIZE_LIMIT: process.env['BODY_SIZE_LIMIT'] ?? '12M',
			LOCAL_AUTH_ENABLED: process.env['LOCAL_AUTH_ENABLED'] ?? 'true',
			ALLOW_LOCAL_SIGNUPS: process.env['ALLOW_LOCAL_SIGNUPS'] ?? 'true',
			PASSKEY_RP_ID: process.env['PASSKEY_RP_ID'] ?? new URL(previewUrl).hostname,
			PASSKEY_RP_NAME: process.env['PASSKEY_RP_NAME'] ?? 'runway'
		},
		reuseExistingServer: process.env['PLAYWRIGHT_REUSE_EXISTING_SERVER'] === 'true'
	}
});
