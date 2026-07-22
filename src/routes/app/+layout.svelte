<script lang="ts">
	import { afterNavigate } from '$app/navigation';
	import { resolve } from '$app/paths';
	import { page } from '$app/state';
	import AccountActions from '$lib/components/AccountActions.svelte';
	import DeviceFolderScanner from '$lib/components/DeviceFolderScanner.svelte';
	import InstallAppControl from '$lib/components/InstallAppControl.svelte';
	import NavIcon from '$lib/components/visual/NavIcon.svelte';
	import RunwayMark from '$lib/components/visual/RunwayMark.svelte';
	import type { LayoutData } from './$types';

	let { data, children }: { data: LayoutData; children: import('svelte').Snippet } = $props();
	type NavItem = {
		href:
			| '/app'
			| '/app/import'
			| '/app/stats'
			| '/app/history'
			| '/app/settings'
			| '/app/onboarding';
		label: string;
		path: string;
		icon: 'calendar' | 'inbox' | 'stats' | 'history' | 'settings';
	};
	const appNavItems: NavItem[] = [
		{ href: '/app', label: 'Calendar', path: resolve('/app'), icon: 'calendar' as const },
		{
			href: '/app/import',
			label: 'Inbox',
			path: resolve('/app/import'),
			icon: 'inbox' as const
		},
		{
			href: '/app/stats',
			label: 'Stats',
			path: resolve('/app/stats'),
			icon: 'stats' as const
		},
		{
			href: '/app/history',
			label: 'History',
			path: resolve('/app/history'),
			icon: 'history' as const
		},
		{
			href: '/app/settings',
			label: 'Settings',
			path: resolve('/app/settings'),
			icon: 'settings' as const
		}
	];
	const setupNavItems: NavItem[] = [
		{
			href: '/app/onboarding',
			label: 'Setup',
			path: resolve('/app/onboarding'),
			icon: 'calendar'
		},
		...appNavItems.slice(1)
	];
	const usesSetupNavigation = $derived(!data.setupComplete);
	const navItems = $derived(usesSetupNavigation ? setupNavItems : appNavItems);
	const brandHref = $derived(usesSetupNavigation ? '/app/onboarding' : '/app');
	const isActive = (item: NavItem) => {
		return item.path === resolve('/app')
			? page.url.pathname === item.path
			: page.url.pathname === item.path || page.url.pathname.startsWith(`${item.path}/`);
	};

	afterNavigate(({ from, to }) => {
		if (from && to?.url.pathname !== from.url.pathname) {
			requestAnimationFrame(resetRouteScroll);
			setTimeout(resetRouteScroll, 0);
			setTimeout(resetRouteScroll, 80);
			requestAnimationFrame(() => document.querySelector<HTMLElement>('#app-content')?.focus());
		}
	});

	function resetRouteScroll() {
		window.scrollTo({ top: 0, left: 0 });
	}
</script>

<a class="skip-link" href="#app-content">Skip to main content</a>
<header class="topbar">
	<a class="brand" href={resolve(brandHref)}>
		<RunwayMark />
		<span>runway</span>
	</a>
	<div class="topbar-install"><InstallAppControl compact /></div>
	<nav class="nav desktop-nav" aria-label="App navigation">
		{#each navItems as item (item.href)}
			<a
				href={resolve(item.href)}
				aria-current={isActive(item) ? 'page' : undefined}
				class:active={isActive(item)}>{item.label}</a
			>
		{/each}
		<AccountActions email={data.user.email} showTheme={false} />
	</nav>
</header>

<div id="app-content" class="app-content" tabindex="-1">
	{@render children()}
</div>

<nav class="mobile-nav" aria-label="App navigation">
	{#each navItems as item (item.href)}
		<a
			href={resolve(item.href)}
			aria-current={isActive(item) ? 'page' : undefined}
			class:active={isActive(item)}><NavIcon name={item.icon} /><span>{item.label}</span></a
		>
	{/each}
</nav>

<DeviceFolderScanner userId={data.user.id} />

<style>
	.mobile-nav {
		display: none;
	}

	.topbar-install {
		margin-left: auto;
	}

	@media (max-width: 720px) {
		.topbar {
			position: sticky;
			top: 0;
			z-index: 40;
			display: flex;
			min-height: 54px;
			margin: 0;
			padding: max(8px, env(safe-area-inset-top)) max(14px, env(safe-area-inset-right)) 8px
				max(14px, env(safe-area-inset-left));
			border: 0;
			border-bottom: 1px solid var(--line);
			border-radius: 0;
			background: color-mix(in oklab, var(--canvas), transparent 6%);
			backdrop-filter: blur(14px);
		}

		.desktop-nav {
			display: none;
		}

		.app-content {
			padding-bottom: calc(78px + env(safe-area-inset-bottom));
		}

		.mobile-nav {
			position: fixed;
			z-index: 50;
			right: 0;
			bottom: 0;
			left: 0;
			display: grid;
			grid-template-columns: repeat(5, minmax(0, 1fr));
			padding: 7px max(6px, env(safe-area-inset-right)) max(7px, env(safe-area-inset-bottom))
				max(6px, env(safe-area-inset-left));
			border-top: 1px solid var(--line);
			background: color-mix(in oklab, var(--canvas), transparent 4%);
			backdrop-filter: blur(16px);
		}

		.mobile-nav a {
			position: relative;
			display: flex;
			flex-direction: column;
			gap: 3px;
			align-items: center;
			justify-content: center;
			min-width: 0;
			min-height: 48px;
			padding: 5px 3px 4px;
			border: 0;
			border-radius: 0;
			color: var(--muted);
			background: transparent;
			font-size: 0.72rem;
			font-weight: 680;
			text-decoration: none;
		}

		.mobile-nav a span {
			max-width: 100%;
			line-height: 1;
			overflow-wrap: anywhere;
			text-align: center;
		}

		.mobile-nav a::before {
			position: absolute;
			top: 5px;
			width: 18px;
			height: 2px;
			border-radius: 0;
			background: transparent;
			content: '';
		}

		.mobile-nav a.active,
		.mobile-nav a[aria-current='page'] {
			color: var(--text);
		}

		.mobile-nav a.active::before,
		.mobile-nav a[aria-current='page']::before {
			background: var(--accent);
		}

		:global(.pwa-notices) {
			bottom: calc(78px + env(safe-area-inset-bottom));
		}
	}

	@media (min-width: 721px) {
		.desktop-nav {
			display: flex;
		}
	}

	:global(.account-action-error) {
		position: fixed;
		z-index: 55;
		top: max(82px, calc(66px + env(safe-area-inset-top)));
		right: max(16px, env(safe-area-inset-right));
		left: max(16px, env(safe-area-inset-left));
		width: auto;
		max-width: 520px;
		margin: 0 0 0 auto;
		padding: 12px 14px;
		border: 1px solid color-mix(in oklab, var(--danger), var(--line) 35%);
		border-radius: var(--radius-small);
		background: var(--surface-strong);
		box-shadow: 0 12px 34px color-mix(in oklab, #000, transparent 82%);
		color: var(--danger);
	}
</style>
