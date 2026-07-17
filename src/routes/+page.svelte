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
