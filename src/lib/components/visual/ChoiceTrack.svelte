<script lang="ts">
	import type { Snippet } from 'svelte';

	let { label, children }: { label: string; children: Snippet } = $props();
</script>

<fieldset class="choice-track-shared">
	<legend>{label}</legend>
	<div>{@render children()}</div>
</fieldset>

<style>
	.choice-track-shared {
		margin: 0;
		padding: 0;
		border: 0;
	}

	legend {
		margin-bottom: 8px;
		font-weight: 700;
	}

	.choice-track-shared > div {
		display: grid;
		gap: 1px;
		border: 1px solid var(--line);
		background: var(--line);
	}
</style>
