import { expect, test, type Page } from '@playwright/test';

test('a service-worker setup failure stays out of the form and can be dismissed', async ({
	page
}) => {
	await page.addInitScript(() => {
		Object.defineProperty(navigator.serviceWorker, 'register', {
			configurable: true,
			value: () => Promise.reject(new Error('Synthetic registration failure.'))
		});
	});
	await page.goto('/login');
	const notice = page.getByRole('alert', { name: /App setup incomplete/ });
	await expect(notice).toBeVisible();
	const signIn = page.getByRole('button', { name: 'Sign in', exact: true }).last();
	const [noticeBox, signInBox] = await Promise.all([notice.boundingBox(), signIn.boundingBox()]);
	expect(noticeBox).not.toBeNull();
	expect(signInBox).not.toBeNull();
	if (noticeBox && signInBox) {
		expect(noticeBox.y).toBeGreaterThanOrEqual(signInBox.y + signInBox.height);
	}
	await notice.getByRole('button', { name: 'Dismiss' }).click();
	await expect(notice).toHaveCount(0);
});

test('a controller replacement reloads a clean client', async ({ page }) => {
	await openControlledLogin(page);

	await Promise.all([
		page.waitForEvent('framenavigated'),
		page.evaluate(() => navigator.serviceWorker.dispatchEvent(new Event('controllerchange')))
	]);

	await expect(page).toHaveURL(/\/login$/);
});

test('a controller replacement protects edits and offers a working reload action', async ({
	page
}) => {
	await openControlledLogin(page);
	const signInForm = page.locator('form[action*="signInEmail"]');
	await signInForm.getByLabel('Email').fill('unsaved@example.test');

	await page.evaluate(() => navigator.serviceWorker.dispatchEvent(new Event('controllerchange')));
	await expect(page.getByText('New version active', { exact: true })).toBeVisible();
	await expect(
		page.getByText('This tab is still on the previous version. Reload after finishing any edits.')
	).toBeVisible();

	await page.getByRole('button', { name: 'Reload runway' }).click();
	await expect(page.getByText('Save or discard the current form before reloading.')).toBeVisible();
	await expect(signInForm.getByLabel('Email')).toHaveValue('unsaved@example.test');

	await signInForm.evaluate((form) => {
		(form as HTMLFormElement).reset();
	});
	await Promise.all([
		page.waitForEvent('framenavigated'),
		page.getByRole('button', { name: 'Reload runway' }).click()
	]);

	await expect(page).toHaveURL(/\/login$/);
});

async function openControlledLogin(page: Page) {
	await page.goto('/login');
	await page.evaluate(async () => {
		if (!('serviceWorker' in navigator)) throw new Error('Service workers are unavailable.');
		await navigator.serviceWorker.ready;
		if (navigator.serviceWorker.controller) return;
		await new Promise<void>((resolve) => {
			navigator.serviceWorker.addEventListener(
				'controllerchange',
				() => {
					resolve();
				},
				{ once: true }
			);
		});
	});
}
