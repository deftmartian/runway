<script lang="ts">
	import { goto, invalidateAll } from '$app/navigation';
	import { resolve } from '$app/paths';
	import { page } from '$app/state';
	import Notice from '$lib/components/Notice.svelte';
	import {
		retainDeviceFolderForUser,
		scanDeviceFolder,
		supportsDeviceFolderImport,
		type DeviceFolderScanResult
	} from '$lib/pwa/device-folder';
	import { onMount } from 'svelte';

	let { userId }: { userId: string } = $props();
	let notice = $state<{ message: string; failed: boolean } | null>(null);
	let lastScanStartedAt = Number.NEGATIVE_INFINITY;
	let permissionNoticeShown = false;
	let scanning = $state(false);
	let accountIsolation = Promise.resolve(false);

	onMount(() => {
		accountIsolation = retainDeviceFolderForUser(userId)
			.then(() => true)
			.catch(() => false);
		if (!supportsDeviceFolderImport()) return;

		const scanWhenActive = () => {
			if (document.visibilityState !== 'visible' || !navigator.onLine) return;
			if (performance.now() - lastScanStartedAt < 5_000) return;
			lastScanStartedAt = performance.now();
			void runScan();
		};
		const handleVisibility = () => {
			if (document.visibilityState === 'visible') scanWhenActive();
		};

		globalThis.addEventListener('focus', scanWhenActive);
		globalThis.addEventListener('online', scanWhenActive);
		globalThis.addEventListener('pageshow', scanWhenActive);
		document.addEventListener('visibilitychange', handleVisibility);
		document.addEventListener('resume', scanWhenActive);
		scanWhenActive();

		return () => {
			globalThis.removeEventListener('focus', scanWhenActive);
			globalThis.removeEventListener('online', scanWhenActive);
			globalThis.removeEventListener('pageshow', scanWhenActive);
			document.removeEventListener('visibilitychange', handleVisibility);
			document.removeEventListener('resume', scanWhenActive);
		};
	});

	async function runScan() {
		scanning = true;
		let result: DeviceFolderScanResult;
		try {
			if (!(await accountIsolation)) {
				notice = {
					message: 'Local folder access could not be isolated for this account.',
					failed: true
				};
				return;
			}
			result = await scanDeviceFolder(userId);
		} catch {
			result = { result: 'failed' };
		} finally {
			scanning = false;
		}

		if (result.result === 'imported') {
			notice = {
				message: 'A GPX from the device folder is ready for review.',
				failed: false
			};
			return;
		}
		if (result.result !== 'permission-required') permissionNoticeShown = false;
		if (result.result === 'permission-required' && !permissionNoticeShown) {
			permissionNoticeShown = true;
			notice = {
				message: 'Device folder access needs to be restored from Import.',
				failed: false
			};
			return;
		}
		if (result.result === 'time-zone-required') {
			notice = {
				message: 'Set the training time zone before device-folder imports can run.',
				failed: true
			};
			return;
		}
		if (result.result === 'future') {
			notice = {
				message:
					'A device-folder GPX was dated in the future and skipped. Reconnect the folder after correcting the device clock to retry it.',
				failed: true
			};
			return;
		}
		if (result.result === 'too-many-files') {
			notice = {
				message: 'The approved folder has too many entries. Choose a dedicated GPX export folder.',
				failed: true
			};
			return;
		}
		if (result.result === 'folder-missing') {
			notice = {
				message: 'The saved device folder moved or was removed. Choose it again from Import.',
				failed: true
			};
			return;
		}
		if (result.result === 'folder-unavailable') {
			notice = {
				message: 'The device folder is temporarily unavailable. Unlock the device and try again.',
				failed: true
			};
			return;
		}
		if (result.result === 'invalid' || result.result === 'too-large') {
			notice = {
				message:
					result.result === 'too-large'
						? 'A device-folder GPX exceeded the 10 MB import limit.'
						: 'A device-folder file was not a valid GPX activity.',
				failed: true
			};
			return;
		}
		if (result.result === 'failed') {
			notice = { message: 'The device folder could not be checked.', failed: true };
		}
	}

	async function openInbox() {
		notice = null;
		if (page.url.pathname === '/app/import') {
			await invalidateAll();
			return;
		}
		await goto(resolve('/app/import'));
	}

	function dismissNotice() {
		notice = null;
		requestAnimationFrame(() => document.querySelector<HTMLElement>('#app-content')?.focus());
	}
</script>

<div class="device-folder-scan-state" aria-hidden="true" aria-busy={scanning}></div>

{#if notice}
	<div class="device-folder-notice" aria-live="polite">
		<Notice
			title="Device folder"
			tone={notice.failed ? 'danger' : 'review'}
			role={notice.failed ? 'alert' : 'status'}
		>
			<span>{notice.message}</span>
			{#snippet actions()}
				<button type="button" class="inbox-link" onclick={openInbox}>
					{page.url.pathname === '/app/import' ? 'Refresh inbox' : 'Open inbox'}
				</button>
				<button
					type="button"
					class="ghost"
					aria-label="Dismiss device folder notice"
					onclick={dismissNotice}
				>
					Dismiss
				</button>
			{/snippet}
		</Notice>
	</div>
{/if}

<style>
	.device-folder-notice {
		position: fixed;
		z-index: 45;
		top: max(82px, calc(66px + env(safe-area-inset-top)));
		right: max(16px, env(safe-area-inset-right));
		left: max(16px, env(safe-area-inset-left));
		width: auto;
		max-width: 500px;
		margin-left: auto;
	}

	.inbox-link {
		min-height: 44px;
		padding: 10px 4px;
		border: 0;
		background: transparent;
		color: var(--accent-strong);
		font-weight: 700;
		text-decoration: underline;
	}

	.device-folder-notice :global(.ghost) {
		padding: 7px 9px;
	}

	@media (max-width: 560px) {
		.device-folder-notice {
			top: max(72px, calc(58px + env(safe-area-inset-top)));
			right: max(10px, env(safe-area-inset-right));
			left: max(10px, env(safe-area-inset-left));
			width: auto;
		}
	}

	.device-folder-scan-state {
		position: fixed;
		width: 1px;
		height: 1px;
		overflow: hidden;
		clip-path: inset(50%);
	}
</style>
