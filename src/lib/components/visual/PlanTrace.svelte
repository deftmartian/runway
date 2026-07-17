<script lang="ts">
	export type PlanTracePoint = {
		label: string;
		recommended: number | null;
		current: number | null;
		actual: number | null;
	};

	let {
		title,
		points,
		unit = 'km'
	}: { title: string; points: PlanTracePoint[]; unit?: string } = $props();

	const width = 720;
	const height = 236;
	const inset = { left: 42, right: 18, top: 18, bottom: 34 };
	const maximum = $derived(
		Math.max(
			1,
			...points.flatMap((point) => [point.recommended ?? 0, point.current ?? 0, point.actual ?? 0])
		)
	);

	function x(index: number) {
		if (points.length <= 1) return inset.left;
		return inset.left + (index / (points.length - 1)) * (width - inset.left - inset.right);
	}

	function y(value: number) {
		return inset.top + (1 - value / maximum) * (height - inset.top - inset.bottom);
	}

	function pathFor(key: 'recommended' | 'current' | 'actual') {
		return points
			.map((point, index) => (point[key] === null ? null : `${x(index)},${y(point[key])}`))
			.filter(Boolean)
			.join(' ');
	}

	function display(value: number | null) {
		return value === null ? '—' : `${Math.round(value * 10) / 10} ${unit}`;
	}
</script>

<section class="plan-trace" aria-labelledby="plan-trace-title">
	<header>
		<div>
			<h2 id="plan-trace-title">{title}</h2>
			<p>Generated recommendation, current plan, and recorded work.</p>
		</div>
		<ul aria-label="Trace legend">
			<li class="recommended"><span aria-hidden="true"></span>Generated</li>
			<li class="current"><span aria-hidden="true"></span>Current</li>
			<li class="actual"><span aria-hidden="true"></span>Actual</li>
		</ul>
	</header>

	<!-- svelte-ignore a11y_no_noninteractive_tabindex (keyboard focus makes the horizontal chart scrollable) -->
	<div
		class="trace-graphic"
		role="region"
		tabindex="0"
		aria-label={`${title} chart. Scroll horizontally for later weeks.`}
	>
		<svg
			viewBox={`0 0 ${width} ${height}`}
			role="img"
			aria-labelledby="trace-svg-title trace-svg-desc"
		>
			<title id="trace-svg-title">{title}</title>
			<desc id="trace-svg-desc">
				Three traces compare generated, current, and actual values. Exact values follow in a table.
			</desc>
			{#each [0, 0.25, 0.5, 0.75, 1] as ratio (ratio)}
				<line
					class="grid-line"
					x1={inset.left}
					x2={width - inset.right}
					y1={y(maximum * ratio)}
					y2={y(maximum * ratio)}
				/>
				<text x={inset.left - 8} y={y(maximum * ratio) + 4} text-anchor="end"
					>{Math.round(maximum * ratio * 10) / 10}</text
				>
			{/each}
			{#if pathFor('current')}<polyline class="trace current" points={pathFor('current')} />{/if}
			{#if pathFor('recommended')}<polyline
					class="trace recommended"
					points={pathFor('recommended')}
				/>{/if}
			{#if pathFor('actual')}<polyline class="trace actual" points={pathFor('actual')} />{/if}
			{#each points as point, index (point.label)}
				<text class="x-label" x={x(index)} y={height - 8} text-anchor="middle">{point.label}</text>
				{#if point.current !== null}<rect
						x={x(index) - 3}
						y={y(point.current) - 3}
						width="6"
						height="6"
						class="current-marker"
					/>{/if}
				{#if point.actual !== null}<circle
						cx={x(index)}
						cy={y(point.actual)}
						r="4"
						class="actual-marker"
					/>{/if}
			{/each}
		</svg>
	</div>

	<details class="trace-values" open={points.length <= 8}>
		<summary>Exact values</summary>
		<div class="trace-table-wrap">
			<table>
				<thead
					><tr
						><th scope="col">Period</th><th scope="col">Generated</th><th scope="col">Current</th
						><th scope="col">Actual</th></tr
					></thead
				>
				<tbody>
					{#each points as point (point.label)}
						<tr
							><th scope="row">{point.label}</th><td>{display(point.recommended)}</td><td
								>{display(point.current)}</td
							><td>{display(point.actual)}</td></tr
						>
					{/each}
				</tbody>
			</table>
		</div>
	</details>
</section>

<style>
	.plan-trace {
		display: grid;
		gap: 18px;
	}

	header {
		display: flex;
		flex-wrap: wrap;
		gap: 12px 24px;
		align-items: end;
		justify-content: space-between;
		padding-bottom: 12px;
		border-bottom: 1px solid var(--line);
	}

	header h2,
	header p {
		margin: 0;
	}

	header p {
		margin-top: 4px;
		color: var(--muted);
	}

	header ul {
		display: flex;
		flex-wrap: wrap;
		gap: 12px;
		margin: 0;
		padding: 0;
		list-style: none;
	}

	header li {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		font-size: 0.78rem;
		font-weight: 700;
	}

	header li span {
		width: 20px;
		height: 3px;
		background: currentColor;
	}

	header li.recommended span {
		background: repeating-linear-gradient(90deg, currentColor 0 4px, transparent 4px 7px);
	}

	header li.actual span {
		height: 0;
		border-top: 3px dotted currentColor;
		background: transparent;
	}

	.recommended {
		color: var(--muted);
	}

	.current {
		color: var(--accent);
	}

	.actual {
		color: var(--completed);
	}

	.trace-graphic {
		min-width: 0;
		overflow-x: auto;
	}

	svg {
		display: block;
		width: 100%;
		min-width: 560px;
		height: auto;
		font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
	}

	.grid-line {
		stroke: var(--line);
		stroke-width: 1;
	}

	text {
		fill: var(--muted);
		font-size: 10px;
	}

	.trace {
		fill: none;
		stroke: currentColor;
		stroke-linecap: square;
		stroke-linejoin: miter;
		stroke-width: 3;
		vector-effect: non-scaling-stroke;
	}

	.trace.recommended {
		stroke-dasharray: 7 5;
		stroke-width: 2;
	}

	.trace.actual {
		stroke-dasharray: 2 5;
	}

	.current-marker {
		fill: var(--accent);
	}

	.actual-marker {
		fill: var(--surface-strong);
		stroke: var(--completed);
		stroke-width: 3;
	}

	.trace-table-wrap {
		margin-top: 10px;
		overflow-x: auto;
	}

	.trace-values {
		border-top: 1px solid var(--line);
	}

	.trace-values summary {
		display: flex;
		align-items: center;
		width: fit-content;
		min-height: 44px;
		font-weight: 700;
		cursor: pointer;
	}

	table {
		width: 100%;
		border-collapse: collapse;
		font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
		font-size: 0.82rem;
		font-variant-numeric: tabular-nums;
	}

	th,
	td {
		padding: 9px 10px;
		border-bottom: 1px solid var(--line);
		text-align: right;
		white-space: nowrap;
	}

	th:first-child {
		text-align: left;
	}
</style>
