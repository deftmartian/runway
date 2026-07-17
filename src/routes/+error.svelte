<script lang="ts">
	import { page } from '$app/state';
	import { resolve } from '$app/paths';
	import RunwayMark from '$lib/components/visual/RunwayMark.svelte';
</script>

<main class="page">
	<section class="status-surface stack">
		<a class="brand" href={resolve('/')}>
			<RunwayMark />
			<span>runway</span>
		</a>
		<p class="status-code">Error {page.status}</p>
		<h1>{page.status === 404 ? 'Page not found' : 'This page could not be loaded'}</h1>
		<p class="lede">
			{page.status === 404
				? 'Check the address or return to the calendar.'
				: 'Try again. If the problem continues, contact the workspace owner.'}
		</p>
		<a class="button primary" href={resolve('/app')}>Go to calendar</a>
	</section>
</main>

<style>
	.status-surface {
		width: min(100%, 620px);
		margin: clamp(28px, 10vh, 96px) auto 0;
		padding: clamp(22px, 4vw, 36px);
		border-top: 3px solid var(--accent);
		background: color-mix(in oklab, var(--surface), transparent 8%);
	}

	.status-surface h1,
	.status-surface p {
		margin-block: 0;
	}

	.status-surface h1 {
		font-size: clamp(2rem, 6vw, 3.4rem);
		line-height: 1.04;
	}

	.status-code {
		color: var(--muted);
		font-weight: 700;
	}

	.status-surface .button {
		width: fit-content;
		margin-top: 8px;
	}
</style>
