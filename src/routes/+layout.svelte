<script lang="ts">
	import '../app.css';
	import { page } from '$app/state';
	import favicon from '$lib/assets/favicon.svg';
	import ServiceWorkerRetirement from '$lib/components/ServiceWorkerRetirement.svelte';
	import type { Snippet } from 'svelte';

	let { children }: { children: Snippet } = $props();
	const documentTitle = $derived(titleForPath(page.url.pathname));

	function titleForPath(pathname: string): string {
		if (pathname === '/app') return 'Training calendar · runway';
		if (pathname.startsWith('/app/import')) return 'Activity inbox · runway';
		if (pathname.startsWith('/app/stats')) return 'Stats · runway';
		if (pathname.startsWith('/app/history')) return 'History · runway';
		if (pathname.startsWith('/app/settings')) return 'Settings · runway';
		if (pathname.startsWith('/app/onboarding')) return 'Build plan · runway';
		if (pathname.startsWith('/login/two-factor')) return 'Two-factor verification · runway';
		if (pathname.startsWith('/login/forgot-password')) return 'Reset password · runway';
		if (pathname.startsWith('/login/reset-password')) return 'Choose a new password · runway';
		if (pathname.startsWith('/login')) return 'Sign in · runway';
		if (pathname.startsWith('/device')) return 'Connect Android · runway';
		return 'runway · running plans and activity review';
	}
</script>

<svelte:head>
	<title>{documentTitle}</title>
	<meta
		name="description"
		content="A self-hosted running planner and activity ledger for comparing recommendations, edits, and recorded work."
	/>
	<link rel="icon" href={favicon} />
	<meta name="theme-color" media="(prefers-color-scheme: light)" content="#F4F2EC" />
	<meta name="theme-color" media="(prefers-color-scheme: dark)" content="#151A18" />
</svelte:head>

{@render children()}
<ServiceWorkerRetirement />
