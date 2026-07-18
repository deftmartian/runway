<script lang="ts">
	import { resolve } from '$app/paths';
	import ThemeToggle from '$lib/components/ThemeToggle.svelte';
	import RunwayMark from '$lib/components/visual/RunwayMark.svelte';
	import { sourceCodeUrl } from '$lib/project';
	import type { PageData } from './$types';

	let { data }: { data: PageData } = $props();
	const loginHref = resolve('/login');
	const signupHref = resolve('/login#create-account');
</script>

<main class="page">
	<div class="public-controls">
		<ThemeToggle />
	</div>
	<section class="hero landing-instrument">
		<div class="landing-copy stack">
			<div class="landing-name">
				<RunwayMark size={42} />
				<h1 class="headline">runway</h1>
			</div>
			<p class="lede">
				Plan toward a running goal, record completed work, and see how missed or extra runs change
				what comes next.
			</p>
			<ol class="product-loop" aria-label="How runway works">
				<li>
					<span>01</span>
					<strong>Plan</strong>
					<small>Start from the week you can run now.</small>
				</li>
				<li>
					<span>02</span>
					<strong>Run</strong>
					<small>Follow the workout or change it.</small>
				</li>
				<li>
					<span>03</span>
					<strong>Compare</strong>
					<small>Record what you actually did.</small>
				</li>
				<li>
					<span>04</span>
					<strong>Decide</strong>
					<small>See whether the plan should change.</small>
				</li>
			</ol>
			<p class="product-boundary">
				<strong>Self-hosted on your server.</strong>
				<span>No live GPS tracking. No social feed.</span>
			</p>
			<div class="check-row landing-actions">
				<a class="button primary" href={loginHref}>Sign in</a>
				{#if data.localSignupsEnabled}
					<a class="button" href={signupHref}>Create local account</a>
				{/if}
			</div>
			<p class="landing-source">
				<!-- eslint-disable-next-line svelte/no-navigation-without-resolve -- external corresponding-source repository -->
				<a href={sourceCodeUrl} target="_blank" rel="noreferrer">Source code</a>
				<span>AGPL-3.0-only</span>
			</p>
		</div>

		<div class="runway-visual" aria-label="Plan ramp preview">
			<div class="runway-visual-top">
				<span>May block</span>
				<strong>12.9 km target</strong>
			</div>
			<div class="runway-rail">
				<span style="--progress: 18%"></span>
				<span style="--progress: 42%"></span>
				<span style="--progress: 72%"></span>
			</div>
			<div class="runway-week">
				<span class="tick done">3.1</span>
				<span class="tick rest">Rest</span>
				<span class="tick review">Missed</span>
				<span class="tick plan">5.8</span>
			</div>
			<div class="runway-readout">
				<div>
					<span>next</span>
					<strong>long run 5.8 km</strong>
				</div>
				<div>
					<span>review</span>
					<strong>missed workout</strong>
				</div>
			</div>
		</div>
	</section>
</main>

<style>
	.product-loop {
		display: grid;
		grid-template-columns: repeat(4, minmax(0, 1fr));
		gap: 0;
		max-width: 680px;
		margin: clamp(16px, 3vw, 28px) 0 4px;
		padding: 0;
		border-block: 1px solid var(--line);
		list-style: none;
	}

	.product-loop li {
		display: grid;
		align-content: start;
		gap: 5px;
		min-width: 0;
		padding: 15px 12px 17px 0;
	}

	.product-loop li + li {
		padding-left: 12px;
		border-left: 1px solid var(--line);
	}

	.product-loop span {
		color: color-mix(in oklab, var(--accent), var(--muted) 34%);
		font-size: 0.72rem;
		font-weight: 780;
		font-variant-numeric: tabular-nums;
		letter-spacing: 0.08em;
	}

	.product-loop strong {
		font-size: 1rem;
	}

	.product-loop small {
		color: var(--muted);
		font-size: 0.8rem;
		line-height: 1.35;
	}

	.product-boundary {
		display: flex;
		flex-wrap: wrap;
		gap: 5px 12px;
		margin: 0;
		font-size: 0.9rem;
	}

	.product-boundary span {
		color: var(--muted);
	}

	.landing-copy::after {
		display: none;
	}

	@media (max-width: 760px) {
		.product-loop {
			grid-template-columns: repeat(2, minmax(0, 1fr));
		}

		.product-loop li:nth-child(3) {
			padding-left: 0;
			border-top: 1px solid var(--line);
			border-left: 0;
		}

		.product-loop li:nth-child(4) {
			border-top: 1px solid var(--line);
		}
	}
</style>
