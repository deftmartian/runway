<script lang="ts">
	import type { Snippet } from 'svelte';

	let {
		title,
		kicker = '',
		detail = '',
		actions,
		children
	}: {
		title: string;
		kicker?: string;
		detail?: string;
		actions?: Snippet;
		children: Snippet;
	} = $props();
</script>

<section class="section-rail">
	<header>
		<div>
			{#if kicker}<p>{kicker}</p>{/if}
			<h2>{title}</h2>
			{#if detail}<span>{detail}</span>{/if}
		</div>
		{#if actions}<div class="actions">{@render actions()}</div>{/if}
	</header>
	<div class="section-content">{@render children()}</div>
</section>

<style>
	.section-rail {
		display: grid;
		grid-template-columns: minmax(170px, 0.25fr) minmax(0, 1fr);
		gap: clamp(18px, 4vw, 52px);
		padding: 24px 0;
		border-top: 1px solid var(--line);
	}

	header,
	header > div:first-child {
		display: grid;
		gap: 5px;
		align-content: start;
	}

	header p,
	header h2,
	header span {
		margin: 0;
	}

	header p {
		color: var(--accent);
		font-size: 0.72rem;
		font-weight: 760;
		letter-spacing: 0.09em;
		text-transform: uppercase;
	}

	header h2 {
		font-size: 1.12rem;
	}

	header span {
		color: var(--muted);
		font-size: 0.88rem;
		line-height: 1.4;
	}

	.actions {
		margin-top: 10px;
	}

	.section-content {
		min-width: 0;
	}

	@media (max-width: 760px) {
		.section-rail {
			grid-template-columns: 1fr;
			gap: 14px;
		}
	}
</style>
