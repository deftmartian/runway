import { createAuthClient } from 'better-auth/svelte';
import { genericOAuthClient, twoFactorClient } from 'better-auth/client/plugins';
import { passkeyClient } from '@better-auth/passkey/client';

export const authClient = createAuthClient({
	plugins: [
		genericOAuthClient(),
		twoFactorClient({ twoFactorPage: '/login/two-factor' }),
		passkeyClient()
	]
});
