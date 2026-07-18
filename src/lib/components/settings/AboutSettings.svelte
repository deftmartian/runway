<script lang="ts">
	import LedgerRow from '$lib/components/visual/LedgerRow.svelte';
	import { sourceCodeUrl } from '$lib/project';
</script>

<section class="settings-section" aria-labelledby="about-settings-heading">
	<header class="section-heading">
		<h2 id="about-settings-heading">About</h2>
	</header>
	<div class="settings-group">
		<LedgerRow label="Source code" value="GNU AGPL v3.0 only">
			{#snippet action()}
				<!-- eslint-disable-next-line svelte/no-navigation-without-resolve -- external corresponding-source repository -->
				<a class="button" href={sourceCodeUrl} target="_blank" rel="noreferrer">View source</a>
			{/snippet}
		</LedgerRow>
	</div>
</section>
