<script lang="ts">
	type Tone = 'planned' | 'completed' | 'review' | 'rest' | 'danger' | 'edited' | 'neutral';

	let { label, tone = 'neutral' }: { label: string; tone?: Tone } = $props();

	const symbol = $derived(
		(
			{
				planned: '│',
				completed: '✓',
				review: '!',
				rest: '–',
				danger: '×',
				edited: '↺',
				neutral: '·'
			} as const
		)[tone]
	);
</script>

<span class="state-marker {tone}"><span aria-hidden="true">{symbol}</span>{label}</span>

<style>
	.state-marker {
		display: inline-flex;
		align-items: center;
		gap: 5px;
		width: fit-content;
		min-height: 24px;
		padding: 2px 8px;
		border: 1px solid var(--line);
		border-radius: 999px;
		color: var(--muted);
		background: var(--surface);
		font-size: 0.74rem;
		font-weight: 720;
		line-height: 1;
	}

	.state-marker > span {
		font-family: ui-monospace, monospace;
		font-weight: 800;
	}

	.planned,
	.edited {
		border-color: color-mix(in oklab, var(--accent), var(--line) 58%);
		color: var(--accent);
	}

	.completed {
		border-color: color-mix(in oklab, var(--completed), var(--line) 58%);
		color: var(--completed);
	}

	.review {
		border-color: color-mix(in oklab, var(--review), var(--line) 58%);
		color: var(--review);
	}

	.rest {
		border-color: color-mix(in oklab, var(--rest), var(--line) 58%);
		color: var(--rest);
	}

	.danger {
		border-color: color-mix(in oklab, var(--danger), var(--line) 58%);
		color: var(--danger);
	}
</style>
