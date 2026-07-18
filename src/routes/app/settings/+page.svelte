<script lang="ts">
	import AboutSettings from '$lib/components/settings/AboutSettings.svelte';
	import AppearanceSettings from '$lib/components/settings/AppearanceSettings.svelte';
	import DataPrivacySettings from '$lib/components/settings/DataPrivacySettings.svelte';
	import SecuritySettings from '$lib/components/settings/SecuritySettings.svelte';
	import TrainingSettings from '$lib/components/settings/TrainingSettings.svelte';
	import type { SettingsFormState } from '$lib/components/settings/types';
	import { disconnectDeviceFolder } from '$lib/pwa/device-folder';
	import type { ActionData, PageData, SubmitFunction } from './$types';

	let { data, form }: { data: PageData; form: ActionData } = $props();
	let settingsActionPending = $state<string | null>(null);
	let privacyClientMessage = $state('');
	let privacyAttention = $state<'routeMaps' | 'activityData' | null>(null);
	const scopedForm = $derived((form ?? {}) as SettingsFormState);

	function enhanceSettingsAction(key: string, confirmation?: string): SubmitFunction {
		return async ({ cancel }) => {
			if (settingsActionPending || (confirmation && !globalThis.confirm(confirmation))) {
				cancel();
				return;
			}
			settingsActionPending = key;
			if (key === 'routeDataMode' || key === 'deleteActivityData') privacyAttention = null;
			if (key === 'deleteActivityData') {
				privacyClientMessage = '';
				try {
					// Stop and forget any foreground scan before the server removes
					// activity data, so an in-flight import cannot recreate a record.
					await disconnectDeviceFolder(data.user.id);
				} catch {
					privacyClientMessage =
						'Imported GPX activities were not deleted because runway could not clear this browser’s folder access. Close other runway tabs and try again.';
					settingsActionPending = null;
					cancel();
					return;
				}
			}
			return async ({ result, update }) => {
				try {
					if (key === 'routeDataMode') privacyAttention = 'routeMaps';
					if (key === 'deleteActivityData') privacyAttention = 'activityData';
					if (result.type !== 'success' && key === 'deleteActivityData') {
						privacyClientMessage =
							'The folder was disconnected, but imported GPX activities were not deleted. Try again, or reconnect the folder from Import.';
					}
					await update({
						reset: result.type === 'success',
						invalidateAll: result.type === 'success'
					});
				} finally {
					settingsActionPending = null;
				}
			};
		};
	}
</script>

<main class="page settings-page">
	<header class="page-heading">
		<h1>Settings</h1>
		{#if scopedForm.message && !scopedForm.scope}
			<p class="message" role="status" aria-live="polite">{scopedForm.message}</p>
		{/if}
	</header>

	<TrainingSettings
		profile={data.profile}
		form={scopedForm}
		{settingsActionPending}
		{enhanceSettingsAction}
	/>
	<SecuritySettings
		user={data.user}
		passkeys={data.passkeys}
		authCapabilities={data.authCapabilities}
		form={scopedForm}
		{settingsActionPending}
		{enhanceSettingsAction}
	/>
	<AppearanceSettings />
	<DataPrivacySettings
		profile={data.profile}
		user={data.user}
		form={scopedForm}
		{privacyClientMessage}
		{privacyAttention}
		{settingsActionPending}
		{enhanceSettingsAction}
	/>
	<AboutSettings />
</main>

<style>
	.settings-page {
		display: grid;
		gap: 0;
		max-width: 1040px;
	}

	.settings-page :global(*) {
		min-width: 0;
	}

	.page-heading {
		padding: 8px 0 24px;
	}

	.page-heading h1 {
		margin: 0;
		letter-spacing: 0;
	}

	.page-heading h1 {
		font-size: clamp(2rem, 5vw, 3rem);
	}

	.page-heading .message {
		margin: 14px 0 0;
	}

	:global(.settings-page) {
		:global {
			.settings-section {
				display: grid;
				grid-template-columns: minmax(150px, 210px) minmax(0, 1fr);
				column-gap: clamp(24px, 5vw, 64px);
				padding: 30px 0;
				border-top: 1px solid var(--line);
			}

			.section-heading {
				grid-column: 1;
				grid-row: 1 / span 20;
			}

			.section-heading h2 {
				margin: 0;
				font-size: 1rem;
				letter-spacing: 0;
			}

			.settings-group,
			.settings-ledger,
			.security-control {
				grid-column: 2;
			}

			.settings-group {
				display: grid;
				gap: 16px;
				padding-bottom: 28px;
			}

			.settings-group + .settings-group {
				padding-top: 28px;
				border-top: 1px solid color-mix(in oklab, var(--line), transparent 35%);
			}

			.settings-group:last-child {
				padding-bottom: 0;
			}

			.settings-group.settings-control {
				padding-bottom: 0;
			}

			.settings-group.settings-control + .settings-group.settings-control {
				padding-top: 0;
			}

			.group-heading {
				display: grid;
				gap: 5px;
			}

			.group-heading p,
			.section-note {
				max-width: 68ch;
				margin: 0;
				color: var(--muted);
				font-size: 0.93rem;
				line-height: 1.5;
			}

			.field-note {
				color: var(--muted);
				font-size: 0.86rem;
				line-height: 1.4;
			}

			.estimate-sources {
				width: fit-content;
				max-width: 100%;
			}

			.estimate-sources summary,
			.setup-codes summary {
				font-weight: 680;
				cursor: pointer;
			}

			.estimate-sources[open] {
				width: 100%;
			}

			.estimate-sources p,
			.estimate-sources ul {
				margin-top: 12px;
			}

			.training-profile-form {
				gap: 20px;
			}

			.profile-form-grid {
				gap: 20px 24px;
			}

			.profile-fieldset,
			.estimate-panel {
				padding: 0;
				border: 0;
				border-radius: 0;
				background: transparent;
			}

			.profile-fieldset legend {
				margin-bottom: 10px;
				color: var(--text);
				font-size: 0.95rem;
			}

			.estimate-panel {
				padding-left: 24px;
				border-left: 2px solid color-mix(in oklab, var(--rail), transparent 45%);
			}

			.estimate-panel strong {
				font-size: 1rem;
			}

			.zone-fieldset {
				padding-top: 20px;
				border-top: 1px solid color-mix(in oklab, var(--line), transparent 35%);
			}

			.settings-ledger {
				padding-bottom: 18px;
			}

			.settings-control {
				border-top: 1px solid color-mix(in oklab, var(--line), transparent 35%);
			}

			.security-control:last-child,
			.data-control:last-of-type {
				border-bottom: 1px solid color-mix(in oklab, var(--line), transparent 35%);
			}

			.settings-control > summary {
				min-height: 62px;
				padding: 12px 2px;
				font-size: 1rem;
				cursor: pointer;
			}

			.settings-control > summary > span {
				display: inline-flex;
				align-items: baseline;
				justify-content: space-between;
				gap: 18px;
				width: calc(100% - 26px);
				margin-left: 8px;
				overflow-wrap: anywhere;
			}

			.settings-control small {
				color: var(--muted);
				font-size: 0.86rem;
				font-weight: 500;
			}

			.control-body,
			.security-body {
				display: grid;
				gap: 16px;
				padding: 4px 0 24px 26px;
			}

			.data-control {
				grid-column: 1 / -1;
			}

			.danger-control {
				margin-top: 2px;
			}

			.visually-hidden {
				position: absolute;
				width: 1px;
				height: 1px;
				padding: 0;
				margin: -1px;
				overflow: hidden;
				clip: rect(0, 0, 0, 0);
				white-space: nowrap;
				border: 0;
			}

			.security-body form {
				max-width: 440px;
			}

			.setup-qr {
				display: grid;
				justify-items: start;
				gap: 10px;
				max-width: 460px;
				padding: 16px 0;
				border-block: 1px solid color-mix(in oklab, var(--line), transparent 35%);
			}

			.setup-qr img {
				max-width: min(220px, 100%);
			}

			.setup-qr code {
				max-width: 100%;
				overflow-wrap: anywhere;
			}

			.setup-codes {
				max-width: 520px;
			}

			.setup-codes pre {
				max-width: 100%;
				overflow-x: auto;
			}

			.passkey-list {
				display: grid;
				gap: 0;
				max-width: 620px;
			}

			.passkey-row {
				display: flex;
				align-items: center;
				justify-content: space-between;
				gap: 16px;
				padding: 14px 0;
				border-top: 1px solid color-mix(in oklab, var(--line), transparent 35%);
			}

			.passkey-row > div {
				display: grid;
				gap: 3px;
			}

			.passkey-row form {
				display: block;
			}

			.data-actions {
				display: flex;
				flex-wrap: wrap;
				gap: 10px;
			}

			.route-privacy-form {
				display: grid;
				justify-items: start;
				gap: 14px;
				padding-bottom: 18px;
				border-bottom: 1px solid color-mix(in oklab, var(--line), transparent 35%);
			}

			.route-privacy-form fieldset {
				display: grid;
				gap: 12px;
				margin: 0;
				padding: 0;
				border: 0;
			}

			.route-privacy-form legend {
				margin-bottom: 8px;
				font-weight: 700;
			}

			.route-privacy-form label {
				display: flex;
				align-items: flex-start;
				gap: 10px;
				max-width: 68ch;
				line-height: 1.45;
			}

			.route-privacy-form input {
				flex: 0 0 auto;
				margin-top: 0.2rem;
			}

			.mobile-account-actions {
				display: none;
				padding-top: 18px;
				border-top: 1px solid color-mix(in oklab, var(--line), transparent 35%);
			}

			@media (max-width: 820px) {
				.page-heading {
					padding-top: 2px;
				}

				.settings-section {
					grid-template-columns: 1fr;
					padding: 24px 0;
				}

				.section-heading,
				.settings-group,
				.settings-ledger,
				.security-control,
				.data-control {
					grid-column: 1;
					grid-row: auto;
				}

				.section-heading {
					margin-bottom: 22px;
				}

				.profile-form-grid {
					grid-template-columns: 1fr;
				}

				.estimate-panel {
					padding: 16px 0 0;
					border-top: 2px solid color-mix(in oklab, var(--rail), transparent 45%);
					border-left: 0;
				}

				.zone-input-grid {
					grid-template-columns: repeat(2, minmax(0, 1fr));
				}

				.mobile-account-actions {
					display: block;
				}
			}

			@media (max-width: 520px) {
				.zone-input-grid {
					grid-template-columns: 1fr;
				}

				.control-body,
				.security-body {
					padding-left: 0;
				}

				.settings-control > summary > span {
					margin-left: 4px;
				}

				.passkey-row {
					align-items: stretch;
					flex-direction: column;
				}
			}
		}
	}
</style>
