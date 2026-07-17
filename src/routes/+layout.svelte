<script lang="ts">
	import '../app.css';
	import { page } from '$app/state';
	import favicon from '$lib/assets/favicon.svg';
	import PwaLifecycle from '$lib/components/PwaLifecycle.svelte';
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
	<link rel="apple-touch-icon" sizes="180x180" href="/pwa/apple-touch-icon.png" />
	<link rel="manifest" href="/manifest.webmanifest" />
	<meta name="application-name" content="runway" />
	<meta name="mobile-web-app-capable" content="yes" />
	<meta name="apple-mobile-web-app-capable" content="yes" />
	<meta name="apple-mobile-web-app-title" content="runway" />
	<meta name="apple-mobile-web-app-status-bar-style" content="default" />
	<meta name="theme-color" media="(prefers-color-scheme: light)" content="#edf3f7" />
	<meta name="theme-color" media="(prefers-color-scheme: dark)" content="#071018" />
</svelte:head>

{@render children()}
<PwaLifecycle />
