<script lang="ts">
	import { afterNavigate } from '$app/navigation';
	import { dev } from '$app/environment';
	import Notice from '$lib/components/Notice.svelte';
	import { startInstallPromptCapture } from '$lib/pwa/install-prompt';
	import {
		enhancedFormsShareSaveScope,
		enhancedFormSavedEvent,
		serviceWorkerSetupMessage,
		serviceWorkerSetupState,
		updateReloadBlockReason
	} from '$lib/pwa/lifecycle';
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
	let newVersionActive = $state(false);
	let updateBlockReason = $state<'unsaved-changes' | 'pending-action' | null>(null);
	let activatingUpdate = $state(false);
	let activationProblem = $state(false);
	let serviceWorkerProblemDismissed = $state(false);
	let registration: ServiceWorkerRegistration | null = null;
	let waitingWorker: ServiceWorker | null = null;
	let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
	let activationTimer: ReturnType<typeof setTimeout> | undefined;
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
		let hadServiceWorkerController = Boolean(navigator.serviceWorker?.controller);
		const stopInstallPromptCapture = startInstallPromptCapture();

		const handleOnline = () => {
			online = true;
			reconnected = true;
			clearTimeout(reconnectTimer);
			reconnectTimer = setTimeout(() => (reconnected = false), 4_000);
			if ($serviceWorkerSetupState === 'failed') void registerServiceWorker();
		};
		const handleOffline = () => {
			online = false;
			reconnected = false;
			clearTimeout(reconnectTimer);
		};
		const handleControllerChange = () => {
			const replacedExistingController = hadServiceWorkerController;
			hadServiceWorkerController = true;
			if (!replacedExistingController) return;

			markNewVersionActive();
			if (!updateBlockReason) globalThis.location.reload();
		};
		const handleFormEdit = (event: Event) => {
			if (!event.isTrusted) return;
			const target = event.target;
			if (!(target instanceof Element)) return;
			const form = target.closest('form');
			if (!form) return;
			dirtyForms.add(form);
			updateBlockReason = newVersionActive ? 'unsaved-changes' : null;
		};
		const handleFormButton = (event: MouseEvent) => {
			if (!event.isTrusted || !(event.target instanceof Element)) return;
			const button = event.target.closest('button[type="button"]');
			const form = button?.closest('form');
			if (!form) return;
			dirtyForms.add(form);
			updateBlockReason = newVersionActive ? 'unsaved-changes' : null;
		};
		const handleFormReset = (event: Event) => {
			if (!(event.target instanceof HTMLFormElement)) return;
			dirtyForms.delete(event.target);
			queueMicrotask(refreshActiveUpdateBlockReason);
		};
		const handleEnhancedFormSaved = (event: Event) => {
			if (!(event instanceof CustomEvent)) return;
			const saved = (event.detail as { form?: unknown } | null)?.form;
			if (!(saved instanceof HTMLFormElement)) return;
			for (const dirty of dirtyForms) {
				if (enhancedFormsShareSaveScope(dirty, saved)) dirtyForms.delete(dirty);
			}
			for (const [pending] of pendingForms) {
				if (enhancedFormsShareSaveScope(pending, saved)) pendingForms.delete(pending);
			}
			queueMicrotask(refreshActiveUpdateBlockReason);
		};
		const handleFormSubmit = (event: SubmitEvent) => {
			if (!(event.target instanceof HTMLFormElement)) return;
			pendingForms.set(event.target, {
				submitter: event.submitter instanceof HTMLElement ? event.submitter : null,
				observedBusy: false
			});
			if (newVersionActive) updateBlockReason = 'pending-action';
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
		document.addEventListener(enhancedFormSavedEvent, handleEnhancedFormSaved);
		document.addEventListener('visibilitychange', handleVisibilityChange);

		const pendingObserver = new MutationObserver(syncPendingForms);
		pendingObserver.observe(document.body, {
			attributes: true,
			attributeFilter: ['aria-busy', 'disabled'],
			subtree: true
		});

		if (dev) {
			serviceWorkerSetupState.set('development');
		} else if ('serviceWorker' in navigator) {
			void registerServiceWorker();
		} else {
			serviceWorkerSetupState.set('unsupported');
		}

		return () => {
			clearTimeout(reconnectTimer);
			clearTimeout(activationTimer);
			stopInstallPromptCapture();
			globalThis.removeEventListener('online', handleOnline);
			globalThis.removeEventListener('offline', handleOffline);
			navigator.serviceWorker?.removeEventListener('controllerchange', handleControllerChange);
			document.removeEventListener('input', handleFormEdit, true);
			document.removeEventListener('change', handleFormEdit, true);
			document.removeEventListener('click', handleFormButton, true);
			document.removeEventListener('reset', handleFormReset, true);
			document.removeEventListener('submit', handleFormSubmit, true);
			document.removeEventListener(enhancedFormSavedEvent, handleEnhancedFormSaved);
			document.removeEventListener('visibilitychange', handleVisibilityChange);
			pendingObserver.disconnect();
		};
	});

	async function registerServiceWorker() {
		serviceWorkerSetupState.set('checking');
		try {
			const nextRegistration = await navigator.serviceWorker.register(serviceWorkerUrl(), {
				scope: '/'
			});
			bindRegistration(nextRegistration);
			await waitForInitialInstallation(nextRegistration);
			serviceWorkerSetupState.set('ready');
		} catch {
			serviceWorkerSetupState.set('failed');
		}
	}

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
					installing.state === 'redundant' &&
					!nextRegistration.active &&
					!navigator.serviceWorker.controller
				) {
					serviceWorkerSetupState.set('failed');
				}
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

	function waitForInitialInstallation(nextRegistration: ServiceWorkerRegistration): Promise<void> {
		if (nextRegistration.active || navigator.serviceWorker.controller) return Promise.resolve();
		const installing = nextRegistration.installing ?? nextRegistration.waiting;
		if (!installing)
			return Promise.reject(new Error('No service worker installation is available.'));
		if (installing.state === 'installed' || installing.state === 'activated') {
			return Promise.resolve();
		}
		if (installing.state === 'redundant') {
			return Promise.reject(new Error('The service worker installation failed.'));
		}
		return new Promise((resolve, reject) => {
			const handleStateChange = () => {
				if (installing.state === 'installed' || installing.state === 'activated') {
					installing.removeEventListener('statechange', handleStateChange);
					resolve();
				} else if (installing.state === 'redundant') {
					installing.removeEventListener('statechange', handleStateChange);
					reject(new Error('The service worker installation failed.'));
				}
			};
			installing.addEventListener('statechange', handleStateChange);
		});
	}

	function showWaitingUpdate(worker: ServiceWorker) {
		clearTimeout(activationTimer);
		waitingWorker = worker;
		updateAvailable = true;
		newVersionActive = false;
		activatingUpdate = false;
		activationProblem = false;
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
		refreshActiveUpdateBlockReason();
	}

	function activateUpdate() {
		syncPendingForms();
		updateBlockReason = currentUpdateBlockReason();
		if (updateBlockReason) return;
		if (newVersionActive || waitingWorker?.state !== 'installed') {
			markNewVersionActive();
			globalThis.location.reload();
			return;
		}

		activatingUpdate = true;
		activationProblem = false;
		try {
			waitingWorker.postMessage({ type: 'ACTIVATE_UPDATE' });
		} catch {
			activatingUpdate = false;
			activationProblem = true;
			return;
		}

		clearTimeout(activationTimer);
		activationTimer = setTimeout(() => {
			activatingUpdate = false;
			if (waitingWorker?.state === 'installed') {
				activationProblem = true;
				return;
			}

			markNewVersionActive();
			if (!updateBlockReason) globalThis.location.reload();
		}, 5_000);
	}

	function markNewVersionActive() {
		clearTimeout(activationTimer);
		waitingWorker = null;
		updateAvailable = true;
		newVersionActive = true;
		activatingUpdate = false;
		activationProblem = false;
		updateBlockReason = currentUpdateBlockReason();
	}

	function refreshActiveUpdateBlockReason() {
		if (newVersionActive) updateBlockReason = currentUpdateBlockReason();
	}

	function currentUpdateBlockReason() {
		return updateReloadBlockReason({
			hasDirtyForms: dirtyForms.size > 0,
			hasPendingForms: pendingForms.size > 0,
			hasBusyElement: Boolean(document.querySelector('[aria-busy="true"]'))
		});
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
		<Notice
			title={newVersionActive ? 'New version active' : 'Update ready'}
			tone={updateBlockReason || activationProblem ? 'warning' : 'info'}
		>
			<span>
				{newVersionActive
					? 'This tab is still on the previous version. Reload after finishing any edits.'
					: 'Apply it after finishing any edits.'}
			</span>
			{#if updateBlockReason}
				<span class="notice-error" role="alert">
					{updateBlockReason === 'pending-action'
						? `Wait for the current action to finish before ${newVersionActive ? 'reloading' : 'updating'}.`
						: `Save or discard the current form before ${newVersionActive ? 'reloading' : 'updating'}.`}
				</span>
			{/if}
			{#if activationProblem}
				<span class="notice-error" role="alert">The update did not start. Try again.</span>
			{/if}
			{#snippet actions()}
				<button type="button" class="primary" onclick={activateUpdate} disabled={activatingUpdate}>
					{activatingUpdate ? 'Updating…' : newVersionActive ? 'Reload runway' : 'Update runway'}
				</button>
			{/snippet}
		</Notice>
	{/if}
</div>

{#if $serviceWorkerSetupState === 'failed' && !serviceWorkerProblemDismissed}
	<div class="service-worker-setup-notice">
		<Notice title="App setup incomplete" tone="warning" role="alert" label="App setup incomplete">
			<span>{serviceWorkerSetupMessage($serviceWorkerSetupState)}</span>
			{#snippet actions()}
				<button type="button" onclick={() => (serviceWorkerProblemDismissed = true)}>Dismiss</button
				>
				<button type="button" class="primary" onclick={registerServiceWorker}>Retry setup</button>
			{/snippet}
		</Notice>
	</div>
{/if}

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

	.service-worker-setup-notice {
		position: relative;
		z-index: 1;
		width: min(calc(100% - 32px), 480px);
		margin: 16px auto max(16px, env(safe-area-inset-bottom));
	}

	@media (max-width: 560px) {
		.pwa-notices {
			right: max(10px, env(safe-area-inset-right));
			left: max(10px, env(safe-area-inset-left));
			bottom: max(10px, env(safe-area-inset-bottom));
			width: auto;
		}

		.service-worker-setup-notice {
			width: min(calc(100% - 20px), 480px);
			margin-bottom: calc(78px + env(safe-area-inset-bottom));
		}
	}
</style>
