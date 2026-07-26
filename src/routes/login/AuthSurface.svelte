<script lang="ts">
	import { resolve } from '$app/paths';
	import RunwayMark from '$lib/components/visual/RunwayMark.svelte';
	import type { Snippet } from 'svelte';

	let { title, children }: { title: string; children: Snippet } = $props();
</script>

<main class="page auth-page">
	<section class="auth-surface stack">
		<header class="auth-header">
			<a class="brand" href={resolve('/')}>
				<RunwayMark />
				<span>runway</span>
			</a>
			<h1>{title}</h1>
		</header>
		{@render children()}
	</section>
</main>

<style>
	.auth-page {
		display: grid;
		place-items: start center;
		padding-top: clamp(24px, 6vh, 64px);
	}

	.auth-surface {
		width: min(100%, 520px);
		padding: clamp(24px, 4vw, 38px);
		border: 0;
		border-radius: var(--radius);
		background: var(--surface);
		box-shadow: var(--elevation);
	}

	.auth-header {
		display: grid;
		gap: 22px;
		margin-bottom: 10px;
	}

	.auth-header h1 {
		margin: 0;
		font-size: clamp(1.8rem, 5vw, 2.2rem);
		font-weight: 690;
		line-height: 1.05;
		letter-spacing: -0.03em;
	}

	:global(.auth-surface .auth-section) {
		display: grid;
		gap: 12px;
		padding-top: 20px;
		border-top: 1px solid var(--line-passive);
	}

	:global(.auth-surface .auth-section > h2) {
		margin: 0;
		font-size: 1.05rem;
	}

	:global(.auth-surface .auth-disclosure) {
		padding-top: 18px;
		border-top: 1px solid var(--line-passive);
	}

	:global(.auth-surface .auth-disclosure > summary) {
		display: flex;
		align-items: center;
		min-height: 44px;
		width: fit-content;
		color: var(--accent-strong);
		font-weight: 700;
		cursor: pointer;
	}

	:global(.auth-surface .auth-disclosure[open] > summary) {
		margin-bottom: 14px;
	}

	@media (max-width: 680px) {
		.auth-page {
			padding-top: 10px;
		}

		.auth-surface {
			padding: 22px 18px 26px;
			box-shadow: var(--elevation-soft);
		}
	}
</style>
