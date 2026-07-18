import { expect, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

export async function expectNoCriticalAxeViolations(page: Page) {
	await page.waitForLoadState('networkidle');
	const results = await new AxeBuilder({ page }).analyze();
	expect(results.violations, JSON.stringify(results.violations, null, 2)).toEqual([]);
}

export async function expectNoHorizontalOverflow(page: Page) {
	const result = await page.evaluate(() => {
		const root = document.documentElement;
		const clientWidth = root.clientWidth;
		return {
			scrollWidth: root.scrollWidth,
			clientWidth,
			offenders: Array.from(document.querySelectorAll<HTMLElement>('body *'))
				.filter((element) => {
					const bounds = element.getBoundingClientRect();
					return bounds.right > clientWidth + 2 || bounds.left < -2;
				})
				.slice(0, 12)
				.map((element) => {
					const bounds = element.getBoundingClientRect();
					return {
						tag: element.tagName.toLowerCase(),
						className: element.className,
						text: element.textContent?.trim().slice(0, 80) ?? '',
						bounds: {
							left: bounds.left,
							right: bounds.right,
							width: bounds.width
						}
					};
				})
		};
	});

	expect(result.scrollWidth, JSON.stringify(result.offenders, null, 2)).toBeLessThanOrEqual(
		result.clientWidth + 2
	);
}

export async function openImportSourceSetup(
	page: Page,
	source: 'Android folder' | 'Browser folder' | 'Nextcloud' | 'Upload GPX' = 'Upload GPX'
) {
	const setup = page.locator('details.source-setup');
	if ((await setup.getAttribute('open')) === null) {
		await setup.getByText('Add import source', { exact: true }).click();
	}
	await setup.getByRole('button', { name: new RegExp(`^${source}`) }).click();
}
