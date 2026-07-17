import { describe, expect, it } from 'vitest';
import { runwayManifest } from './manifest';

describe('runway web app manifest', () => {
	it('uses a stable application identity and bounded launch routes', () => {
		expect(runwayManifest).toMatchObject({
			id: '/',
			lang: 'en',
			start_url: '/app',
			scope: '/',
			display: 'standalone'
		});
		expect(runwayManifest.shortcuts.map(({ url }) => url)).toEqual([
			'/app',
			'/app/import',
			'/app/stats'
		]);
		expect(runwayManifest.description).toContain('decision ledger');
	});

	it('registers the authenticated review-only GPX share endpoint', () => {
		expect(runwayManifest.share_target).toEqual({
			action: '/app/import/share',
			method: 'POST',
			enctype: 'multipart/form-data',
			params: {
				files: [
					{
						name: 'gpx',
						accept: ['.gpx', 'application/gpx+xml', 'application/xml', 'text/xml']
					}
				]
			}
		});
	});

	it('provides portable any and maskable PNG icons', () => {
		expect(runwayManifest.icons).toEqual(
			expect.arrayContaining([
				expect.objectContaining({ sizes: '192x192', type: 'image/png', purpose: 'any' }),
				expect.objectContaining({ sizes: '512x512', type: 'image/png', purpose: 'any' }),
				expect.objectContaining({ sizes: '192x192', type: 'image/png', purpose: 'maskable' }),
				expect.objectContaining({ sizes: '512x512', type: 'image/png', purpose: 'maskable' })
			])
		);
		for (const icon of runwayManifest.icons) expect(icon.src).toMatch(/^\/pwa\/.+\.png$/);
	});

	it('includes narrow and wide install screenshots without private-route query data', () => {
		expect(runwayManifest.screenshots.map(({ form_factor }) => form_factor)).toEqual([
			'narrow',
			'wide'
		]);
		for (const screenshot of runwayManifest.screenshots) {
			expect(screenshot.src).toMatch(/^\/pwa\/screenshots\/.+\.png$/);
			expect(screenshot.src).not.toContain('?');
		}
	});
});
