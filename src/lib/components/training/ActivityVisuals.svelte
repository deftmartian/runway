<script lang="ts">
	import type {
		ActivityRouteTrace,
		HeartRateActivitySummary,
		HeartRateSeries,
		HeartRateZoneKey
	} from '$lib/training/types';

	let {
		id,
		routeTrace,
		heartRateSeries,
		heartRateSummary,
		averageHeartRate,
		maxHeartRate,
		durationSeconds
	}: {
		id: string;
		routeTrace: ActivityRouteTrace | null;
		heartRateSeries: HeartRateSeries | null;
		heartRateSummary: HeartRateActivitySummary | null;
		averageHeartRate: number | null;
		maxHeartRate: number | null;
		durationSeconds: number | null;
	} = $props();

	type SpeedCategory = 'slower' | 'typical' | 'faster';
	type ProjectedRoutePoint = ActivityRouteTrace['points'][number] & { x: number; y: number };
	const routeWidth = 640;
	const routeHeight = 320;
	const routePadding = 22;
	const chartWidth = 640;
	const chartHeight = 230;
	const chartPadding = { top: 18, right: 20, bottom: 34, left: 48 };
	const zoneLabels: Record<HeartRateZoneKey, string> = {
		z1: 'Recovery',
		z2: 'Easy',
		z3: 'Steady',
		z4: 'Hard',
		z5: 'Max'
	};

	const routePlot = $derived.by(() => projectRoute(routeTrace));
	const speedBreaks = $derived.by(() => {
		const speeds = (routeTrace?.points ?? [])
			.flatMap((point) =>
				point.speedMetersPerSecond !== null && point.speedMetersPerSecond > 0
					? [point.speedMetersPerSecond]
					: []
			)
			.sort((left, right) => left - right);
		return {
			slow: percentile(speeds, 0.33),
			fast: percentile(speeds, 0.67)
		};
	});
	const routeSegments = $derived.by(() =>
		routePlot.slice(0, -1).flatMap((point, index) => {
			const next = routePlot[index + 1];
			if (!next || point.segmentIndex !== next.segmentIndex) return [];
			return [
				{
					id: `${index}-${point.elapsedSeconds}`,
					x1: point.x,
					y1: point.y,
					x2: next.x,
					y2: next.y,
					category: speedCategory(point.speedMetersPerSecond, speedBreaks)
				}
			];
		})
	);
	const heartPlot = $derived.by(() => projectHeartRate(heartRateSeries));
	const heartPath = $derived(
		heartPlot.length > 1
			? heartPlot.map((point, index) => `${index === 0 ? 'M' : 'L'}${point.x},${point.y}`).join(' ')
			: ''
	);
	const heartAreaPath = $derived.by(() => {
		const first = heartPlot[0];
		const last = heartPlot.at(-1);
		if (!first || !last || !heartPath) return '';
		const baseline = chartHeight - chartPadding.bottom;
		return `${heartPath} L${last.x},${baseline} L${first.x},${baseline} Z`;
	});
	const zoneBars = $derived.by(() => {
		const secondsByZone = heartRateSummary?.secondsByZone;
		if (!secondsByZone) return [];
		const total = Object.values(secondsByZone).reduce((sum, seconds) => sum + seconds, 0);
		if (total <= 0) return [];
		let offset = 0;
		return (Object.keys(zoneLabels) as HeartRateZoneKey[]).map((key) => {
			const seconds = secondsByZone[key];
			const width = (seconds / total) * 100;
			const bar = { key, seconds, x: offset, width };
			offset += width;
			return bar;
		});
	});
	const heartRange = $derived.by(() => {
		const values = heartRateSeries?.points.map((point) => point.bpm) ?? [];
		return {
			min: values.length > 0 ? Math.min(...values) : averageHeartRate,
			max: values.length > 0 ? Math.max(...values) : maxHeartRate
		};
	});

	function projectRoute(trace: ActivityRouteTrace | null): ProjectedRoutePoint[] {
		const points = trace?.points ?? [];
		if (points.length === 0) return [];
		const meanLatitude =
			points.reduce((sum, point) => sum + point.latitudeE6 / 1_000_000, 0) / points.length;
		const longitudeScale = Math.max(0.1, Math.cos((meanLatitude * Math.PI) / 180));
		const coordinates = points.map((point) => ({
			point,
			x: (point.longitudeE6 / 1_000_000) * longitudeScale,
			y: point.latitudeE6 / 1_000_000
		}));
		const minX = Math.min(...coordinates.map((point) => point.x));
		const maxX = Math.max(...coordinates.map((point) => point.x));
		const minY = Math.min(...coordinates.map((point) => point.y));
		const maxY = Math.max(...coordinates.map((point) => point.y));
		const availableWidth = routeWidth - routePadding * 2;
		const availableHeight = routeHeight - routePadding * 2;
		const scale = Math.min(
			availableWidth / Math.max(maxX - minX, Number.EPSILON),
			availableHeight / Math.max(maxY - minY, Number.EPSILON)
		);
		const usedWidth = (maxX - minX) * scale;
		const usedHeight = (maxY - minY) * scale;
		const offsetX = routePadding + (availableWidth - usedWidth) / 2;
		const offsetY = routePadding + (availableHeight - usedHeight) / 2;
		return coordinates.map(({ point, x, y }) => ({
			...point,
			x: offsetX + (x - minX) * scale,
			y: routeHeight - (offsetY + (y - minY) * scale)
		}));
	}

	function projectHeartRate(series: HeartRateSeries | null) {
		const points = series?.points ?? [];
		if (points.length === 0) return [];
		const minValue = Math.min(...points.map((point) => point.bpm));
		const maxValue = Math.max(...points.map((point) => point.bpm));
		const low = Math.max(30, minValue - 5);
		const high = Math.min(260, Math.max(low + 10, maxValue + 5));
		const maxElapsed = Math.max(durationSeconds ?? 0, points.at(-1)?.elapsedSeconds ?? 0, 1);
		const innerWidth = chartWidth - chartPadding.left - chartPadding.right;
		const innerHeight = chartHeight - chartPadding.top - chartPadding.bottom;
		return points.map((point) => ({
			...point,
			x: chartPadding.left + (point.elapsedSeconds / maxElapsed) * innerWidth,
			y: chartPadding.top + ((high - point.bpm) / (high - low)) * innerHeight
		}));
	}

	function percentile(values: number[], fraction: number): number | null {
		if (values.length === 0) return null;
		return values[Math.min(values.length - 1, Math.floor((values.length - 1) * fraction))] ?? null;
	}

	function speedCategory(
		speed: number | null,
		breaks: { slow: number | null; fast: number | null }
	): SpeedCategory {
		if (speed === null || breaks.slow === null || breaks.fast === null) return 'typical';
		if (speed < breaks.slow) return 'slower';
		if (speed > breaks.fast) return 'faster';
		return 'typical';
	}

	function formatElapsed(seconds: number): string {
		const minutes = Math.floor(seconds / 60);
		const remainder = seconds % 60;
		return `${minutes}:${String(remainder).padStart(2, '0')}`;
	}

	function formatZoneTime(seconds: number): string {
		if (seconds < 60) return `${seconds} sec`;
		return `${Math.round(seconds / 60)} min`;
	}
</script>

{#if routePlot.length > 1 || heartPlot.length > 0 || zoneBars.length > 0}
	<section class="activity-visuals" aria-label="Activity visuals">
		{#if routePlot.length > 1}
			<article class="activity-visual route-visual" aria-labelledby={`${id}-route-title`}>
				<header>
					<div>
						<h3 id={`${id}-route-title`}>Route map</h3>
						<p>Relative speed · local route trace</p>
					</div>
					<span>{routeTrace?.sourcePointCount ?? routePlot.length} source points</span>
				</header>
				<svg
					class="route-map"
					viewBox={`0 0 ${routeWidth} ${routeHeight}`}
					role="img"
					aria-labelledby={`${id}-route-svg-title ${id}-route-svg-description`}
				>
					<title id={`${id}-route-svg-title`}>Activity route coloured by relative speed</title>
					<desc id={`${id}-route-svg-description`}>
						Dashed narrow segments are slower, solid segments are typical, and wider segments are
						faster within this activity. A circle marks the start and a square marks the finish.
					</desc>
					<path class="map-grid" d="M160 0V320M320 0V320M480 0V320M0 80H640M0 160H640M0 240H640" />
					{#each routeSegments as segment (segment.id)}
						<line
							class={`route-segment ${segment.category}`}
							x1={segment.x1}
							y1={segment.y1}
							x2={segment.x2}
							y2={segment.y2}
						/>
					{/each}
					{#if routePlot[0]}
						<circle class="route-start" cx={routePlot[0].x} cy={routePlot[0].y} r="6" />
					{/if}
					{#if routePlot.at(-1)}
						<rect
							class="route-finish"
							x={(routePlot.at(-1)?.x ?? 0) - 5}
							y={(routePlot.at(-1)?.y ?? 0) - 5}
							width="10"
							height="10"
						/>
					{/if}
					<text class="north" x="620" y="24">N</text>
				</svg>
				<ul class="visual-legend" aria-label="Relative speed legend">
					<li><span class="legend-line slower"></span>Slower</li>
					<li><span class="legend-line typical"></span>Typical</li>
					<li><span class="legend-line faster"></span>Faster</li>
					<li><span class="legend-start"></span>Start</li>
					<li><span class="legend-finish"></span>Finish</li>
				</ul>
			</article>
		{/if}

		{#if heartPlot.length > 0 || zoneBars.length > 0}
			<article class="activity-visual heart-visual" aria-labelledby={`${id}-heart-title`}>
				<header>
					<div>
						<h3 id={`${id}-heart-title`}>Heart rate</h3>
						<p>Recorded response over elapsed time</p>
					</div>
					{#if averageHeartRate !== null}<span
							>avg {averageHeartRate} · max {maxHeartRate ?? '—'} bpm</span
						>{/if}
				</header>

				{#if heartPlot.length > 0}
					<svg
						class="heart-chart"
						viewBox={`0 0 ${chartWidth} ${chartHeight}`}
						role="img"
						aria-labelledby={`${id}-heart-svg-title ${id}-heart-svg-description`}
					>
						<defs>
							<linearGradient id={`${id}-heart-fill`} x1="0" y1="0" x2="0" y2="1">
								<stop offset="0" stop-color="var(--accent)" stop-opacity="0.28" />
								<stop offset="1" stop-color="var(--accent)" stop-opacity="0.02" />
							</linearGradient>
						</defs>
						<title id={`${id}-heart-svg-title`}>Heart rate over elapsed time</title>
						<desc id={`${id}-heart-svg-description`}>
							{heartRateSeries?.sourceSampleCount ?? heartPlot.length} recorded samples. Average
							{averageHeartRate ?? 'unknown'} beats per minute and maximum {maxHeartRate ??
								'unknown'} beats per minute.
						</desc>
						<path
							class="chart-grid"
							d={`M${chartPadding.left} ${chartPadding.top}V${chartHeight - chartPadding.bottom}H${chartWidth - chartPadding.right}`}
						/>
						{#if heartAreaPath}<path d={heartAreaPath} fill={`url(#${id}-heart-fill)`} />{/if}
						{#if heartPath}<path class="heart-line" d={heartPath} />{/if}
						{#if heartPlot.length === 1 && heartPlot[0]}
							<circle class="heart-point" cx={heartPlot[0].x} cy={heartPlot[0].y} r="5" />
						{/if}
						<text class="axis-label" x="4" y={chartPadding.top + 5}
							>{heartRange.max ?? '—'} bpm</text
						>
						<text class="axis-label" x="4" y={chartHeight - chartPadding.bottom}
							>{heartRange.min ?? '—'} bpm</text
						>
						<text class="axis-label" x={chartPadding.left} y={chartHeight - 8}>0:00</text>
						<text
							class="axis-label axis-end"
							x={chartWidth - chartPadding.right}
							y={chartHeight - 8}
						>
							{formatElapsed(
								durationSeconds ?? heartRateSeries?.points.at(-1)?.elapsedSeconds ?? 0
							)}
						</text>
					</svg>
				{/if}

				{#if zoneBars.length > 0}
					<div class="zone-summary">
						<svg
							viewBox="0 0 100 10"
							preserveAspectRatio="none"
							role="img"
							aria-label="Time in heart-rate zones"
						>
							{#each zoneBars as bar (bar.key)}
								<rect class={`zone ${bar.key}`} x={bar.x} y="0" width={bar.width} height="10" />
							{/each}
						</svg>
						<ul class="zone-legend">
							{#each zoneBars as bar (bar.key)}
								<li>
									<span class={`zone-swatch ${bar.key}`}></span>{bar.key.toUpperCase()}
									{zoneLabels[bar.key]} · {formatZoneTime(bar.seconds)}
								</li>
							{/each}
						</ul>
					</div>
				{/if}

				{#if heartRateSeries && heartRateSeries.points.length > 0}
					<details class="sample-table">
						<summary>Exact retained heart-rate samples</summary>
						<div>
							<table>
								<thead><tr><th scope="col">Elapsed</th><th scope="col">Heart rate</th></tr></thead>
								<tbody>
									{#each heartRateSeries.points as point, index (`${index}-${point.elapsedSeconds}`)}
										<tr><td>{formatElapsed(point.elapsedSeconds)}</td><td>{point.bpm} bpm</td></tr>
									{/each}
								</tbody>
							</table>
						</div>
					</details>
				{/if}
			</article>
		{/if}
	</section>
{/if}

<style>
	.activity-visuals {
		display: grid;
		gap: 18px;
		padding-block: 18px;
		border-block: 1px solid var(--line);
	}

	.activity-visual {
		display: grid;
		gap: 12px;
		min-width: 0;
	}

	.activity-visual header {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
		gap: 16px;
	}

	.activity-visual h3,
	.activity-visual p {
		margin: 0;
	}

	.activity-visual h3 {
		font-size: 1rem;
	}

	.activity-visual p,
	.activity-visual header > span,
	.sample-table summary {
		color: var(--muted);
		font-size: 0.8rem;
		line-height: 1.4;
	}

	.activity-visual svg {
		display: block;
		width: 100%;
		height: auto;
	}

	.route-map,
	.heart-chart {
		border: 1px solid var(--line);
		border-radius: 10px;
		background: color-mix(in oklab, var(--surface), var(--background) 35%);
	}

	.route-map {
		background:
			radial-gradient(
				circle at 20% 18%,
				color-mix(in oklab, var(--accent), transparent 91%),
				transparent 36%
			),
			color-mix(in oklab, var(--surface), var(--background) 35%);
	}

	.heart-chart {
		background: linear-gradient(
			180deg,
			color-mix(in oklab, var(--accent), var(--surface) 94%),
			color-mix(in oklab, var(--surface), var(--background) 38%)
		);
	}

	.map-grid,
	.chart-grid {
		fill: none;
		stroke: color-mix(in oklab, var(--line), transparent 45%);
		stroke-width: 1;
	}

	.route-segment {
		fill: none;
		stroke-linecap: round;
	}

	.route-segment.slower {
		stroke: var(--muted);
		stroke-width: 2;
		stroke-dasharray: 4 4;
	}

	.route-segment.typical {
		stroke: var(--accent);
		stroke-width: 3;
	}

	.route-segment.faster {
		stroke: var(--completed);
		stroke-width: 5;
	}

	.route-start {
		fill: var(--completed);
		stroke: var(--background);
		stroke-width: 2;
	}

	.route-finish {
		fill: var(--danger);
		stroke: var(--background);
		stroke-width: 2;
	}

	.north,
	.axis-label {
		fill: var(--muted);
		font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
		font-size: 12px;
	}

	.visual-legend,
	.zone-legend {
		display: flex;
		flex-wrap: wrap;
		gap: 8px 14px;
		margin: 0;
		padding: 0;
		color: var(--muted);
		font-size: 0.78rem;
		list-style: none;
	}

	.visual-legend li,
	.zone-legend li {
		display: inline-flex;
		align-items: center;
		gap: 6px;
	}

	.legend-line {
		display: inline-block;
		width: 22px;
		height: 0;
	}

	.legend-line.slower {
		border-top: 2px dashed var(--muted);
	}

	.legend-line.typical {
		border-top: 3px solid var(--accent);
	}

	.legend-line.faster {
		border-top: 5px solid var(--completed);
	}

	.legend-start,
	.legend-finish {
		display: inline-block;
		width: 9px;
		height: 9px;
	}

	.legend-start {
		background: var(--completed);
	}

	.legend-finish {
		background: var(--danger);
	}

	.legend-start {
		border-radius: 50%;
	}

	.heart-line {
		fill: none;
		stroke: var(--accent);
		stroke-width: 3;
		stroke-linecap: round;
		stroke-linejoin: round;
	}

	.heart-point {
		fill: var(--accent);
	}

	.axis-end {
		text-anchor: end;
	}

	.zone-summary {
		display: grid;
		gap: 8px;
	}

	.zone-summary > svg {
		height: 12px;
		border-radius: 6px;
		overflow: hidden;
	}

	.zone.z1,
	.zone-swatch.z1 {
		fill: var(--muted);
		background: var(--muted);
	}

	.zone.z2,
	.zone-swatch.z2 {
		fill: var(--rail);
		background: var(--rail);
	}

	.zone.z3,
	.zone-swatch.z3 {
		fill: var(--accent);
		background: var(--accent);
	}

	.zone.z4,
	.zone-swatch.z4 {
		fill: var(--review);
		background: var(--review);
	}

	.zone.z5,
	.zone-swatch.z5 {
		fill: var(--danger);
		background: var(--danger);
	}

	.zone-swatch {
		width: 9px;
		height: 9px;
		border-radius: 2px;
	}

	.sample-table summary {
		width: fit-content;
		font-weight: 700;
		cursor: pointer;
	}

	.sample-table > div {
		max-height: 260px;
		margin-top: 10px;
		overflow: auto;
		border-block: 1px solid var(--line);
	}

	.sample-table table {
		width: 100%;
		border-collapse: collapse;
		font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
		font-size: 0.78rem;
		font-variant-numeric: tabular-nums;
	}

	.sample-table th,
	.sample-table td {
		padding: 7px 9px;
		border-bottom: 1px solid color-mix(in oklab, var(--line), transparent 45%);
		text-align: left;
	}

	.sample-table th {
		position: sticky;
		top: 0;
		background: var(--surface);
	}

	@media (max-width: 520px) {
		.activity-visual header {
			align-items: flex-start;
			flex-direction: column;
			gap: 5px;
		}

		.visual-legend,
		.zone-legend {
			gap: 7px 11px;
		}
	}
</style>
