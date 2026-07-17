<script lang="ts">
	let {
		label,
		value,
		unit = '',
		detail = ''
	}: { label: string; value: string | number; unit?: string; detail?: string } = $props();
</script>

<div class="measurement-readout">
	<span>{label}</span>
	<strong
		>{value}{#if unit}<small>{unit}</small>{/if}</strong
	>
	{#if detail}<small class="detail">{detail}</small>{/if}
</div>

<style>
	.measurement-readout {
		display: grid;
		gap: 3px;
		min-width: 0;
		padding-block: 10px;
		border-top: 2px solid var(--rail);
	}

	.measurement-readout > span,
	.detail {
		color: var(--muted);
		font-size: 0.76rem;
	}

	.measurement-readout > span {
		font-weight: 700;
		letter-spacing: 0.04em;
		text-transform: uppercase;
	}

	strong {
		font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
		font-size: clamp(1.25rem, 3vw, 1.85rem);
		font-variant-numeric: tabular-nums;
		line-height: 1;
		overflow-wrap: anywhere;
	}

	strong small {
		margin-left: 4px;
		color: var(--muted);
		font-size: 0.68em;
		font-weight: 600;
	}
</style>
