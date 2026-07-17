<script lang="ts">
	import type { Snippet } from 'svelte';

	let {
		label,
		value,
		detail = '',
		action
	}: { label: string; value: string; detail?: string; action?: Snippet } = $props();
</script>

<div class="ledger-row">
	<div class="label">
		<span>{label}</span>{#if detail}<small>{detail}</small>{/if}
	</div>
	<strong>{value}</strong>
	{#if action}<div class="action">{@render action()}</div>{/if}
</div>

<style>
	.ledger-row {
		display: grid;
		grid-template-columns: minmax(150px, 0.45fr) minmax(0, 1fr) auto;
		gap: 16px;
		align-items: center;
		min-height: 58px;
		padding: 9px 0;
		border-bottom: 1px solid var(--line);
	}

	.label {
		display: grid;
		gap: 2px;
		color: var(--muted);
	}

	.label small {
		font-size: 0.76rem;
	}

	strong {
		min-width: 0;
		overflow-wrap: anywhere;
	}

	.action {
		display: flex;
		justify-content: flex-end;
	}

	@media (max-width: 620px) {
		.ledger-row {
			grid-template-columns: 1fr auto;
			gap: 6px 12px;
		}

		strong {
			grid-column: 1 / -1;
			grid-row: 2;
		}

		.action {
			grid-column: 2;
			grid-row: 1 / 3;
		}
	}
</style>
