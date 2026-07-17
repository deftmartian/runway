<script lang="ts">
	let { size = 30 }: { size?: number } = $props();
</script>

<svg
	class="runway-mark"
	width={size}
	height={Math.round(size * 0.6)}
	viewBox="0 0 30 18"
	aria-hidden="true"
	focusable="false"
>
	<path class="rail" d="M4 2 1.5 16M26 2l2.5 14" />
	<path class="edge" d="M8 2 6.2 16M22 2l1.8 14" />
	<path class="center" d="M15 2v3m0 3v3m0 3v2" />
</svg>

<style>
	.runway-mark {
		display: block;
		flex: 0 0 auto;
		color: var(--accent);
	}

	path {
		fill: none;
		stroke: currentColor;
		stroke-linecap: square;
	}

	.rail {
		stroke-width: 2;
	}

	.edge {
		opacity: 0.45;
		stroke-width: 1;
	}

	.center {
		stroke-width: 1.5;
	}
</style>
