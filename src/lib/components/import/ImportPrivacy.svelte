<script lang="ts">
	let { routeDataMode }: { routeDataMode: 'private' | 'discard' } = $props();
</script>

<details class="import-privacy">
	<summary>What runway stores</summary>
	<p>
		Raw GPX files are discarded after import. runway keeps the activity start time, duration,
		distance, pace, point count, elevation presence, and average cadence when present. Heart-rate
		imports keep average and maximum values plus up to 600 samples with elapsed times.
		{routeDataMode === 'private'
			? ' Private route maps keep up to 600 representative coordinates, including the first and last points, with elapsed times and relative speed.'
			: ' Route coordinates are discarded after those totals are calculated.'}
		Browser folder permission stays in this browser. Android pairing does not store your password or browser
		session on the device.
	</p>
</details>

<style>
	.import-privacy {
		width: min(100%, 700px);
		padding-top: 2px;
		color: var(--muted);
		font-size: 0.86rem;
	}

	.import-privacy > summary {
		display: inline-flex;
		align-items: center;
		min-height: 44px;
		color: var(--text);
		font-weight: 700;
		cursor: pointer;
	}

	.import-privacy p {
		max-width: 72ch;
		margin: 4px 0 0;
		line-height: 1.5;
	}
</style>
