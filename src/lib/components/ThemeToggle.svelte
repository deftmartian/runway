<script lang="ts">
	import { onMount } from 'svelte';

	type Theme = 'light' | 'dark';

	const storageKey = 'runway-theme';
	const themeColors: Record<Theme, string> = {
		light: '#edf3f7',
		dark: '#071018'
	};

	let { class: className = '' }: { class?: string } = $props();
	let effectiveTheme = $state<Theme>('light');
	let themeOverride = $state<Theme | null>(null);
	let mediaQuery: MediaQueryList | null = null;

	onMount(() => {
		mediaQuery = window.matchMedia('(prefers-color-scheme: dark)');
		themeOverride = storedTheme();
		applyTheme(themeOverride);

		const handleSystemChange = () => {
			if (!themeOverride) applyTheme(null);
		};
		mediaQuery.addEventListener('change', handleSystemChange);

		return () => {
			mediaQuery?.removeEventListener('change', handleSystemChange);
		};
	});

	function storedTheme(): Theme | null {
		const value = localStorage.getItem(storageKey);
		return value === 'light' || value === 'dark' ? value : null;
	}

	function systemTheme(): Theme {
		return mediaQuery?.matches ? 'dark' : 'light';
	}

	function activeTheme(): Theme {
		const explicitTheme = document.documentElement.dataset['theme'];
		return explicitTheme === 'light' || explicitTheme === 'dark' ? explicitTheme : systemTheme();
	}

	function applyTheme(theme: Theme | null) {
		if (theme) {
			document.documentElement.dataset['theme'] = theme;
			localStorage.setItem(storageKey, theme);
		} else {
			delete document.documentElement.dataset['theme'];
			localStorage.removeItem(storageKey);
		}

		effectiveTheme = activeTheme();
		updateThemeColor(theme);
	}

	function updateThemeColor(theme: Theme | null) {
		const selector = 'meta[name="theme-color"][data-runway-theme-color]';
		const existing = document.querySelector<HTMLMetaElement>(selector);

		if (!theme) {
			existing?.remove();
			return;
		}

		const meta = existing ?? document.createElement('meta');
		meta.name = 'theme-color';
		meta.dataset['runwayThemeColor'] = 'override';
		meta.content = themeColors[theme];
		if (!existing) document.head.append(meta);
	}

	function toggleTheme() {
		themeOverride = effectiveTheme === 'dark' ? 'light' : 'dark';
		applyTheme(themeOverride);
	}

	function nextThemeLabel() {
		return effectiveTheme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme';
	}
</script>

<button
	type="button"
	class={`theme-toggle ${className}`.trim()}
	aria-label={nextThemeLabel()}
	title={nextThemeLabel()}
	aria-pressed={effectiveTheme === 'dark'}
	data-theme-state={effectiveTheme}
	onclick={toggleTheme}
>
	<span class="theme-toggle-track" aria-hidden="true">
		<span class="theme-toggle-dot"></span>
	</span>
	<span class="theme-toggle-label">{effectiveTheme === 'dark' ? 'Dark' : 'Light'}</span>
</button>
