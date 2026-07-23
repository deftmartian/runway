import { expect, test } from '@playwright/test';
import { randomBytes } from 'node:crypto';
import { fixedBrowserClockScript } from '../support/test-clock';
import {
	createAccount,
	localSignInForm,
	holdSettingsAction,
	holdPageAction,
	insertPasswordResetToken,
	clearPasswordResetRateLimits,
	insertTrustedDeviceVerification,
	verificationExists,
	totpForSecret,
	expectNoCriticalAxeViolations
} from './support/runway';

test.beforeEach(async ({ page }) => {
	await page.addInitScript(fixedBrowserClockScript());
});

test('protected app redirects to login', async ({ page }) => {
	await page.goto('/app');
	await expect(page.getByRole('heading', { name: 'Sign in', exact: true })).toBeVisible();
	await expectNoCriticalAxeViolations(page);
});

test('theme follows system until light or dark is selected', async ({ page }) => {
	await page.emulateMedia({ colorScheme: 'dark' });
	await page.goto('/');
	await expect(page.locator('html')).not.toHaveAttribute('data-theme', /.+/);

	await page.getByRole('button', { name: 'Switch to light theme' }).click();
	await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
	await expect(page.getByRole('button', { name: 'Switch to dark theme' })).toBeVisible();
	await expect
		.poll(async () => page.evaluate(() => localStorage.getItem('runway-theme')))
		.toBe('light');

	await page.reload();
	await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');

	await page.getByRole('button', { name: 'Switch to dark theme' }).click();
	await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
	await expect
		.poll(async () => page.evaluate(() => localStorage.getItem('runway-theme')))
		.toBe('dark');
});

test('auth recovery and account creation failures do not enumerate accounts', async ({ page }) => {
	await clearPasswordResetRateLimits();
	const email = await createAccount(page);
	await page
		.getByRole('navigation', { name: 'App navigation' })
		.getByRole('button', { name: 'Sign out' })
		.click();
	await expect(page.getByRole('heading', { name: 'runway' })).toBeVisible();
	await page.goto('/login');
	await expect(page.getByRole('heading', { name: 'Sign in', exact: true })).toBeVisible();

	await page.getByRole('button', { name: 'Create account', exact: true }).click();
	await expect(page).toHaveURL(/\/login#create-account$/);
	const signup = page.locator('#create-account');
	await signup.getByLabel('Email').fill(email);
	await signup.getByLabel('Password').fill('correct horse battery staple 2026');
	await signup.getByLabel('Name').fill('Runway Tester');
	const heldSignup = await holdPageAction(page, '/login', 'signUpEmail');
	await signup.getByRole('button', { name: 'Create account' }).click();
	await heldSignup.observed;
	await expect(signup.getByRole('button', { name: 'Creating account…' })).toBeDisabled();
	await expect(localSignInForm(page).locator('.message')).toHaveCount(0);
	heldSignup.release();
	await expect(signup.getByText('Account could not be created.')).toBeVisible();
	await expect(signup).not.toContainText('already exists');
	await heldSignup.stop();

	await page.getByRole('button', { name: 'Sign in', exact: true }).click();
	await expect(page).toHaveURL(/\/login$/);
	await page.getByRole('link', { name: 'Reset password' }).click();
	await expect(page).toHaveURL(/\/login\/forgot-password$/);
	await page.getByLabel('Email').fill(email);
	const heldResetRequest = await holdPageAction(page, '/login/forgot-password', 'requestReset');
	await page.getByRole('button', { name: 'Send reset link' }).click();
	await heldResetRequest.observed;
	await expect(page.getByRole('button', { name: 'Sending reset link…' })).toBeDisabled();
	heldResetRequest.release();
	await expect(
		page.getByText('Password reset email is not available yet. Ask the workspace owner for help.')
	).toBeVisible();
	await heldResetRequest.stop();

	await page.goto('/login/reset-password?token=bad-token');
	await expect(
		page.getByText('That reset link is invalid, expired, or already used.')
	).toBeVisible();
	await expect(page.getByLabel('New password')).toHaveCount(0);
});

test('local sign-in actions are throttled outside the Better Auth router', async ({ page }) => {
	await clearPasswordResetRateLimits();
	await page.goto('/login');
	const email = `missing-${randomBytes(8).toString('hex')}@example.test`;
	const signIn = localSignInForm(page);
	await signIn.getByLabel('Email').fill(email);
	await signIn.getByLabel('Password').fill('incorrect password value');
	const heldSignIn = await holdPageAction(page, '/login', 'signInEmail');
	await signIn.getByRole('button', { name: 'Sign in' }).click();
	await heldSignIn.observed;
	await expect(signIn).toHaveAttribute('aria-busy', 'true');
	await expect(signIn.getByRole('button', { name: 'Signing in…' })).toBeVisible();
	await expect(page.locator('#create-account .message')).toHaveCount(0);
	heldSignIn.release();
	await expect(signIn.getByText('Email or password is not correct.')).toBeVisible();
	await heldSignIn.stop();
	await clearPasswordResetRateLimits();
	let finalResponseBody = '';
	for (let attempt = 0; attempt < 11; attempt += 1) {
		const response = await page.request.post('/login?/signInEmail', {
			headers: {
				accept: 'application/json',
				origin: new URL(page.url()).origin,
				'x-sveltekit-action': 'true'
			},
			form: { email, password: 'incorrect password value' }
		});
		finalResponseBody = await response.text();
	}
	expect(finalResponseBody).toContain('Too many sign-in attempts. Try again later.');
});

test('local sign-up actions are throttled outside the Better Auth router', async ({ page }) => {
	await clearPasswordResetRateLimits();
	await page.goto('/login');
	const email = `signup-limit-${randomBytes(8).toString('hex')}@example.test`;
	let finalResponseBody = '';
	for (let attempt = 0; attempt < 4; attempt += 1) {
		const response = await page.request.post('/login?/signUpEmail', {
			headers: {
				accept: 'application/json',
				origin: new URL(page.url()).origin,
				'x-sveltekit-action': 'true'
			},
			form: {
				email,
				password: 'correct horse battery staple 2026',
				name: 'Signup Limit Test'
			}
		});
		finalResponseBody = await response.text();
	}
	expect(finalResponseBody).toContain('Too many account-creation attempts. Try again later.');
});

test('TOTP setup reveals recovery codes only after verification and backup sign-in works', async ({
	page
}) => {
	await clearPasswordResetRateLimits();
	const email = await createAccount(page);
	await page.goto('/app/settings');
	await page.getByText('Authenticator app', { exact: true }).click();
	const enableForm = page.locator('form[action="?/enableTwoFactor"]');
	await enableForm.getByLabel('Password').fill('correct horse battery staple 2026');
	const heldEnable = await holdSettingsAction(page, 'enableTwoFactor');
	await enableForm.getByRole('button', { name: 'Set up authenticator' }).click();
	await heldEnable.observed;
	await expect(enableForm.getByRole('button', { name: 'Starting…' })).toBeDisabled();
	heldEnable.release();
	await expect(page.getByText('Cannot scan it? Enter this setup key manually:')).toBeVisible();
	await heldEnable.stop();
	await expect(page.getByText('Recovery codes')).toHaveCount(0);
	const secret = (await page.locator('.setup-qr code').textContent())?.trim();
	if (!secret) throw new Error('TOTP setup did not render the manual secret.');
	await page.getByLabel('Authenticator code').fill(totpForSecret(secret));
	const heldVerify = await holdSettingsAction(page, 'verifySetupTotp');
	await page.getByRole('button', { name: 'Verify code' }).click();
	await heldVerify.observed;
	await expect(page.getByRole('button', { name: 'Verifying…' })).toBeDisabled();
	heldVerify.release();
	await expect(page.getByText('Recovery codes', { exact: true })).toBeVisible();
	await heldVerify.stop();
	const backupCodes = (await page.locator('.setup-codes pre').textContent())?.trim().split('\n');
	expect(backupCodes).toHaveLength(10);
	const backupCode = backupCodes?.[0];
	if (!backupCode) throw new Error('TOTP verification did not return backup codes.');

	await page
		.getByRole('navigation', { name: 'App navigation' })
		.getByRole('button', { name: 'Sign out' })
		.click();
	await expect(page).toHaveURL(/\/$/);
	await page.goto('/login');
	await localSignInForm(page).getByLabel('Email').fill(email);
	await localSignInForm(page).getByLabel('Password').fill('correct horse battery staple 2026');
	await localSignInForm(page).getByRole('button', { name: 'Sign in' }).click();
	await expect(page).toHaveURL(/\/login\/two-factor$/);
	const backupInput = page.getByLabel('Backup code');
	await expect(backupInput).toHaveAttribute('inputmode', 'text');
	await backupInput.fill(backupCode);
	const heldBackupCode = await holdPageAction(page, '/login/two-factor', 'verifyBackupCode');
	await page.getByRole('button', { name: 'Use backup code' }).click();
	await heldBackupCode.observed;
	await expect(page.getByRole('button', { name: 'Checking backup code…' })).toBeDisabled();
	heldBackupCode.release();
	await expect(page).toHaveURL(/\/app\/onboarding$/);
	await heldBackupCode.stop();

	await page.goto('/app/settings');
	await page.getByText('Authenticator app', { exact: true }).click();
	const disableForm = page.locator('form[action="?/disableTwoFactor"]');
	await disableForm.getByLabel('Password').fill('correct horse battery staple 2026');
	const heldDisable = await holdSettingsAction(page, 'disableTwoFactor');
	await disableForm.getByRole('button', { name: 'Disable authenticator' }).click();
	await heldDisable.observed;
	await expect(disableForm.getByRole('button', { name: 'Disabling…' })).toBeDisabled();
	heldDisable.release();
	await expect(page.getByText('Two-factor authentication disabled.')).toBeVisible();
	await heldDisable.stop();
});

test('password reset tokens are single-use and revoke existing sessions', async ({ page }) => {
	await clearPasswordResetRateLimits();
	const email = await createAccount(page);
	const resetToken = randomBytes(32).toString('base64url');
	const trustedVerificationId = await insertTrustedDeviceVerification(email);
	const newPassword = 'correct horse battery staple 2028';
	await insertPasswordResetToken(email, resetToken, new Date(Date.now() + 30 * 60_000));

	await page.goto(`/login/reset-password?token=${encodeURIComponent(resetToken)}`);
	await expect(page).toHaveURL(/\/login\/reset-password$/);
	await page.getByLabel('New password').fill(newPassword);
	await page.getByLabel('Confirm password').fill(newPassword);
	const heldPasswordReset = await holdPageAction(page, '/login/reset-password', 'resetPassword');
	await page.getByRole('button', { name: 'Change password' }).click();
	await heldPasswordReset.observed;
	await expect(page.getByRole('button', { name: 'Changing password…' })).toBeDisabled();
	heldPasswordReset.release();
	await expect(page.getByText('Password changed. Sign in with the new password.')).toBeVisible();
	await heldPasswordReset.stop();
	expect(await verificationExists(trustedVerificationId)).toBe(false);

	await page.goto('/app');
	await expect(page.getByRole('heading', { name: 'Sign in', exact: true })).toBeVisible();

	await page.goto('/login');
	await localSignInForm(page).getByLabel('Email').fill(email);
	await localSignInForm(page).getByLabel('Password').fill('correct horse battery staple 2026');
	await localSignInForm(page).getByRole('button', { name: 'Sign in' }).click();
	await expect(page.getByText('Email or password is not correct.')).toBeVisible();

	await localSignInForm(page).getByLabel('Password').fill(newPassword);
	await localSignInForm(page).getByRole('button', { name: 'Sign in' }).click();
	await expect(page).toHaveURL(/\/app\/onboarding$/);

	await page.goto(`/login/reset-password?token=${encodeURIComponent(resetToken)}`);
	await expect(
		page.getByText('That reset link is invalid, expired, or already used.')
	).toBeVisible();
	await expect(page.getByLabel('New password')).toHaveCount(0);

	const expiredToken = randomBytes(32).toString('base64url');
	await insertPasswordResetToken(email, expiredToken, new Date(Date.now() - 60_000));
	await page.goto(`/login/reset-password?token=${encodeURIComponent(expiredToken)}`);
	await expect(
		page.getByText('That reset link is invalid, expired, or already used.')
	).toBeVisible();
	await expect(page.getByLabel('New password')).toHaveCount(0);
});
