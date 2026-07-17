<script lang="ts">
	import { afterNavigate } from '$app/navigation';
	import Notice from '$lib/components/Notice.svelte';
	import { startInstallPromptCapture } from '$lib/pwa/install-prompt';
	import { updateReloadBlockReason } from '$lib/pwa/lifecycle';
	import { onMount } from 'svelte';
	import { SvelteMap, SvelteSet } from 'svelte/reactivity';

	type TrustedTypesPolicy = {
		createScriptURL: (value: string) => unknown;
	};
	type TrustedTypesFactory = {
		createPolicy: (
			name: string,
			rules: { createScriptURL: (value: string) => string }
		) => TrustedTypesPolicy;
	};

	type PendingForm = {
		submitter: HTMLElement | null;
		observedBusy: boolean;
	};

	let online = $state(true);
	let reconnected = $state(false);
	let updateAvailable = $state(false);
	let updateBlockReason = $state<'unsaved-changes' | 'pending-action' | null>(null);
	let activatingUpdate = $state(false);
	let registration: ServiceWorkerRegistration | null = null;
	let waitingWorker: ServiceWorker | null = null;
	let activationRequested = false;
	let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
	let serviceWorkerPolicy: TrustedTypesPolicy | null = null;
	const dirtyForms = new SvelteSet<HTMLFormElement>();
	const pendingForms = new SvelteMap<HTMLFormElement, PendingForm>();

	afterNavigate(() => {
		dirtyForms.clear();
		pendingForms.clear();
		updateBlockReason = null;
	});

	onMount(() => {
		online = navigator.onLine;
		const stopInstallPromptCapture = startInstallPromptCapture();

		const handleOnline = () => {
			online = true;
			reconnected = true;
			clearTimeout(reconnectTimer);
			reconnectTimer = setTimeout(() => (reconnected = false), 4_000);
		};
		const handleOffline = () => {
			online = false;
			reconnected = false;
			clearTimeout(reconnectTimer);
		};
		const handleControllerChange = () => {
			if (activationRequested) globalThis.location.reload();
		};
		const handleFormEdit = (event: Event) => {
			if (!event.isTrusted) return;
			const target = event.target;
			if (!(target instanceof Element)) return;
			const form = target.closest('form');
			if (!form) return;
			dirtyForms.add(form);
			updateBlockReason = null;
		};
		const handleFormButton = (event: MouseEvent) => {
			if (!event.isTrusted || !(event.target instanceof Element)) return;
			const button = event.target.closest('button[type="button"]');
			const form = button?.closest('form');
			if (!form) return;
			dirtyForms.add(form);
			updateBlockReason = null;
		};
		const handleFormReset = (event: Event) => {
			if (event.target instanceof HTMLFormElement) dirtyForms.delete(event.target);
		};
		const handleFormSubmit = (event: SubmitEvent) => {
			if (!(event.target instanceof HTMLFormElement)) return;
			pendingForms.set(event.target, {
				submitter: event.submitter instanceof HTMLElement ? event.submitter : null,
				observedBusy: false
			});
			queueMicrotask(syncPendingForms);
		};
		const handleVisibilityChange = () => {
			if (document.visibilityState === 'visible')
				void registration?.update().catch(() => undefined);
		};

		globalThis.addEventListener('online', handleOnline);
		globalThis.addEventListener('offline', handleOffline);
		navigator.serviceWorker?.addEventListener('controllerchange', handleControllerChange);
		document.addEventListener('input', handleFormEdit, true);
		document.addEventListener('change', handleFormEdit, true);
		document.addEventListener('click', handleFormButton, true);
		document.addEventListener('reset', handleFormReset, true);
		document.addEventListener('submit', handleFormSubmit, true);
		document.addEventListener('visibilitychange', handleVisibilityChange);

		const pendingObserver = new MutationObserver(syncPendingForms);
		pendingObserver.observe(document.body, {
			attributes: true,
			attributeFilter: ['aria-busy', 'disabled'],
			subtree: true
		});

		if ('serviceWorker' in navigator) {
			void navigator.serviceWorker
				.register(serviceWorkerUrl(), { scope: '/' })
				.then(bindRegistration)
				.catch(() => undefined);
		}

		return () => {
			clearTimeout(reconnectTimer);
			stopInstallPromptCapture();
			globalThis.removeEventListener('online', handleOnline);
			globalThis.removeEventListener('offline', handleOffline);
			navigator.serviceWorker?.removeEventListener('controllerchange', handleControllerChange);
			document.removeEventListener('input', handleFormEdit, true);
			document.removeEventListener('change', handleFormEdit, true);
			document.removeEventListener('click', handleFormButton, true);
			document.removeEventListener('reset', handleFormReset, true);
			document.removeEventListener('submit', handleFormSubmit, true);
			document.removeEventListener('visibilitychange', handleVisibilityChange);
			pendingObserver.disconnect();
		};
	});

	function bindRegistration(nextRegistration: ServiceWorkerRegistration) {
		registration = nextRegistration;
		if (nextRegistration.waiting && navigator.serviceWorker.controller) {
			showWaitingUpdate(nextRegistration.waiting);
		}

		nextRegistration.addEventListener('updatefound', () => {
			const installing = nextRegistration.installing;
			if (!installing) return;
			installing.addEventListener('statechange', () => {
				if (
					installing.state === 'installed' &&
					navigator.serviceWorker.controller &&
					nextRegistration.waiting
				) {
					showWaitingUpdate(nextRegistration.waiting);
				}
			});
		});
	}

	function showWaitingUpdate(worker: ServiceWorker) {
		waitingWorker = worker;
		updateAvailable = true;
		activatingUpdate = false;
	}

	function syncPendingForms() {
		for (const [form, pending] of pendingForms) {
			if (!form.isConnected) {
				pendingForms.delete(form);
				continue;
			}
			const busy =
				form.getAttribute('aria-busy') === 'true' ||
				pending.submitter?.hasAttribute('disabled') === true;
			if (busy) pending.observedBusy = true;
			else if (pending.observedBusy) pendingForms.delete(form);
		}
	}

	function activateUpdate() {
		syncPendingForms();
		updateBlockReason = updateReloadBlockReason({
			hasDirtyForms: dirtyForms.size > 0,
			hasPendingForms: pendingForms.size > 0,
			hasBusyElement: Boolean(document.querySelector('[aria-busy="true"]'))
		});
		if (updateBlockReason) return;
		if (!waitingWorker) return;
		activatingUpdate = true;
		activationRequested = true;
		waitingWorker.postMessage({ type: 'ACTIVATE_UPDATE' });
	}

	function serviceWorkerUrl(): string {
		const trustedTypes = (globalThis as typeof globalThis & { trustedTypes?: TrustedTypesFactory })
			.trustedTypes;
		if (!trustedTypes) return '/service-worker.js';
		serviceWorkerPolicy ??= trustedTypes.createPolicy('runway-service-worker', {
			createScriptURL: (value) => value
		});
		return serviceWorkerPolicy.createScriptURL('/service-worker.js') as string;
	}
</script>

<div class="pwa-notices" aria-live="polite" aria-atomic="true">
	{#if !online}
		<Notice title="Offline" tone="warning">
			<span>Reconnect to view or change private training data.</span>
		</Notice>
	{:else if reconnected}
		<Notice title="Back online" />
	{/if}

	{#if updateAvailable}
		<Notice title="Update ready" tone={updateBlockReason ? 'warning' : 'info'}>
			<span>Apply it after finishing any edits.</span>
			{#if updateBlockReason}
				<span class="notice-error" role="alert">
					{updateBlockReason === 'pending-action'
						? 'Wait for the current action to finish before updating.'
						: 'Save or discard the current form, then reload this page before updating.'}
				</span>
			{/if}
			{#snippet actions()}
				<button type="button" class="primary" onclick={activateUpdate} disabled={activatingUpdate}>
					{activatingUpdate ? 'Updating…' : 'Update runway'}
				</button>
			{/snippet}
		</Notice>
	{/if}
</div>

<style>
	.pwa-notices {
		position: fixed;
		z-index: 50;
		right: max(16px, env(safe-area-inset-right));
		left: max(16px, env(safe-area-inset-left));
		bottom: max(16px, env(safe-area-inset-bottom));
		display: grid;
		gap: 10px;
		width: auto;
		max-width: 480px;
		margin-left: auto;
		pointer-events: none;
	}

	.pwa-notices :global(.notice) {
		pointer-events: auto;
	}

	@media (max-width: 560px) {
		.pwa-notices {
			right: max(10px, env(safe-area-inset-right));
			left: max(10px, env(safe-area-inset-left));
			bottom: max(10px, env(safe-area-inset-bottom));
			width: auto;
		}
	}
</style>
