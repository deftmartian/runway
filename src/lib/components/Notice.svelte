<script lang="ts">
	import type { Snippet } from 'svelte';

	let {
		title,
		tone = 'info',
		role = 'status',
		label,
		children,
		actions
	}: {
		title: string;
		tone?: 'info' | 'review' | 'warning' | 'danger';
		role?: 'status' | 'alert' | 'region';
		label?: string;
		children?: Snippet;
		actions?: Snippet;
	} = $props();
</script>

<section class="notice" data-tone={tone} {role} aria-label={label}>
	<div class="notice-copy">
		<strong>{title}</strong>
		{@render children?.()}
	</div>
	{#if actions}
		<div class="notice-actions">{@render actions()}</div>
	{/if}
</section>

<style>
	.notice {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 16px;
		width: 100%;
		padding: 12px 14px;
		border: 1px solid var(--line);
		border-radius: var(--radius-small);
		background: color-mix(in oklab, var(--surface-strong), transparent 3%);
		box-shadow: 0 12px 34px color-mix(in oklab, #000, transparent 82%);
	}

	.notice[data-tone='review'],
	.notice[data-tone='warning'] {
		border-color: color-mix(in oklab, var(--review), var(--line) 45%);
	}

	.notice[data-tone='danger'] {
		border-color: color-mix(in oklab, var(--danger), var(--line) 40%);
	}

	.notice-copy {
		display: grid;
		gap: 2px;
		min-width: 0;
	}

	.notice-copy :global(span) {
		color: var(--muted);
		font-size: 0.9rem;
		line-height: 1.35;
	}

	.notice-copy :global(.notice-error) {
		color: var(--danger);
	}

	.notice-actions {
		display: flex;
		align-items: center;
		gap: 10px;
		white-space: nowrap;
	}

	@media (max-width: 560px) {
		.notice {
			align-items: stretch;
			flex-direction: column;
			gap: 10px;
		}

		.notice-actions {
			justify-content: space-between;
		}
	}
</style>
