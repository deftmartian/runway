<script lang="ts">
	import { onMount } from 'svelte';
	import LedgerRow from '$lib/components/visual/LedgerRow.svelte';
	import { serviceWorkerSetupState, type ServiceWorkerSetupState } from '$lib/pwa/lifecycle';
	import { sourceCodeUrl } from '$lib/project';

	type ServerState = 'checking' | 'connected' | 'problem' | 'unreachable' | 'offline';
	type DataState = 'checking' | 'ready' | 'not-ready' | 'problem' | 'unknown' | 'offline';

	let {
		release,
		commit,
		serverOrigin
	}: { release: string; commit: string | null; serverOrigin: string } = $props();

	let browserOrigin = $state('');
	let checking = $state(true);
	let serverState = $state<ServerState>('checking');
	let dataState = $state<DataState>('checking');
	let activeCheck: AbortController | null = null;
	let checkSequence = 0;

	const displayedOrigin = $derived(browserOrigin || serverOrigin);
	const transport = $derived(
		displayedOrigin.startsWith('https://') ? 'HTTPS requests' : 'HTTP requests'
	);
	const serverStatus = $derived(serverStateLabel(serverState));
	const dataStatus = $derived(dataStateLabel(dataState));
	const connectionAnnouncement = $derived(
		`Server ${serverStatus.toLowerCase()}. Data service ${dataStatus.toLowerCase()}.`
	);

	onMount(() => {
		browserOrigin = globalThis.location.origin;

		const handleOnline = () => {
			void checkConnection();
		};
		const handleOffline = () => {
			markOffline();
		};
		const handleVisibilityChange = () => {
			if (document.visibilityState === 'visible') {
				if (navigator.onLine) void checkConnection();
				else markOffline();
			}
		};

		globalThis.addEventListener('online', handleOnline);
		globalThis.addEventListener('offline', handleOffline);
		document.addEventListener('visibilitychange', handleVisibilityChange);

		if (navigator.onLine) void checkConnection();
		else markOffline();

		return () => {
			checkSequence += 1;
			activeCheck?.abort();
			globalThis.removeEventListener('online', handleOnline);
			globalThis.removeEventListener('offline', handleOffline);
			document.removeEventListener('visibilitychange', handleVisibilityChange);
		};
	});

	async function checkConnection() {
		const sequence = ++checkSequence;
		activeCheck?.abort();

		if (!navigator.onLine) {
			markOffline();
			return;
		}

		const controller = new AbortController();
		const timeout = setTimeout(() => {
			controller.abort();
		}, 5_000);
		activeCheck = controller;
		checking = true;
		serverState = 'checking';
		dataState = 'checking';

		try {
			const [live, ready] = await Promise.all([
				requestHealth('/health/live', controller.signal),
				requestHealth('/health/ready', controller.signal)
			]);
			if (sequence !== checkSequence) return;
			if (!navigator.onLine) {
				markOffline();
				return;
			}

			serverState = live === null ? 'unreachable' : live.ok ? 'connected' : 'problem';
			dataState =
				ready === null
					? 'unknown'
					: ready.ok
						? 'ready'
						: ready.status === 503
							? 'not-ready'
							: 'problem';
		} finally {
			clearTimeout(timeout);
			if (sequence === checkSequence) {
				activeCheck = null;
				checking = false;
			}
		}
	}

	function markOffline() {
		checkSequence += 1;
		activeCheck?.abort();
		activeCheck = null;
		checking = false;
		serverState = 'offline';
		dataState = 'offline';
	}

	async function requestHealth(
		path: string,
		signal: AbortSignal
	): Promise<{ ok: boolean; status: number } | null> {
		try {
			const response = await fetch(path, {
				cache: 'no-store',
				credentials: 'same-origin',
				headers: { accept: 'application/json' },
				signal
			});
			await response.text();
			return { ok: response.ok, status: response.status };
		} catch {
			return null;
		}
	}

	function serverStateLabel(state: ServerState): string {
		if (state === 'connected') return 'Connected';
		if (state === 'problem') return 'Server problem';
		if (state === 'unreachable') return 'No response';
		if (state === 'offline') return 'Offline';
		return 'Checking…';
	}

	function dataStateLabel(state: DataState): string {
		if (state === 'ready') return 'Ready';
		if (state === 'not-ready') return 'Not ready';
		if (state === 'problem') return 'Service problem';
		if (state === 'unknown') return 'No response';
		if (state === 'offline') return 'Offline';
		return 'Checking…';
	}

	function browserAppStateLabel(state: ServiceWorkerSetupState): string {
		if (state === 'ready') return 'Offline support ready';
		if (state === 'failed') return 'Setup failed';
		if (state === 'unsupported') return 'Web only';
		if (state === 'development') return 'Development mode';
		return 'Setting up…';
	}
</script>

<section class="settings-section" aria-labelledby="about-settings-heading">
	<header class="section-heading">
		<h2 id="about-settings-heading">About</h2>
	</header>

	<div class="settings-group">
		<LedgerRow label="Release" value={`v${release}`} />
		<div class="technical-value" data-build-commit>
			<LedgerRow label="Build commit" value={commit ?? 'Not embedded'} />
		</div>
		<LedgerRow label="Source code" value="GNU AGPL v3.0 only">
			{#snippet action()}
				<!-- eslint-disable-next-line svelte/no-navigation-without-resolve -- external corresponding-source repository -->
				<a class="button" href={sourceCodeUrl} target="_blank" rel="noreferrer">View source</a>
			{/snippet}
		</LedgerRow>
	</div>

	<div class="settings-group connection-group">
		<div class="group-heading">
			<h3>Connection</h3>
			<p>Current status between this browser and the server.</p>
		</div>
		<p class="sr-only" role="status" aria-live="polite">{connectionAnnouncement}</p>
		<div class="technical-value" data-server-origin>
			<LedgerRow label="Server" detail={serverStatus} value={displayedOrigin}>
				{#snippet action()}
					<button type="button" onclick={checkConnection} disabled={checking} aria-busy={checking}>
						{checking ? 'Checking…' : 'Check again'}
					</button>
				{/snippet}
			</LedgerRow>
		</div>
		<LedgerRow label="Data service" value={dataStatus} />
		<LedgerRow label="Web connection" detail="WebSocket is not used" value={transport} />
		<LedgerRow label="Browser app" value={browserAppStateLabel($serviceWorkerSetupState)} />
	</div>
</section>

<style>
	.group-heading h3 {
		margin: 0;
		font-size: 0.95rem;
		letter-spacing: 0;
	}

	.technical-value :global(.ledger-row > strong) {
		font-family: var(--measure-font);
		font-size: 0.86rem;
		font-weight: 650;
	}

	.connection-group :global(button) {
		white-space: nowrap;
	}
</style>
