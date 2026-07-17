<script lang="ts">
	type Icon = 'calendar' | 'inbox' | 'stats' | 'history' | 'settings';
	let { name }: { name: Icon } = $props();
</script>

<svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" focusable="false">
	{#if name === 'calendar'}
		<path d="M5 4.5h14v15H5zM8 2.5v4M16 2.5v4M5 8.5h14M8 12h2m4 0h2m-8 4h2" />
	{:else if name === 'inbox'}
		<path d="M4 5h16v14H4zM4 14h4l2 2h4l2-2h4" />
	{:else if name === 'stats'}
		<path d="M4 19V5m0 14h16M7 15l4-4 3 2 5-7" />
	{:else if name === 'history'}
		<path d="M5 8H2V5M3 7a9 9 0 1 1 1 11M12 7v5l3 2" />
	{:else}
		<path
			d="M12 8.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7ZM19 12l2-1-2-3-2 .5-1.5-1L15 5h-6l-.5 2.5-1.5 1L5 8l-2 3 2 1v2l-2 1 2 3 2-.5 1.5 1L9 21h6l.5-2.5 1.5-1 2 .5 2-3-2-1v-2Z"
		/>
	{/if}
</svg>

<style>
	svg {
		display: block;
		fill: none;
		stroke: currentColor;
		stroke-linecap: square;
		stroke-linejoin: miter;
		stroke-width: 1.5;
		vector-effect: non-scaling-stroke;
	}
</style>
