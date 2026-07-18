<script lang="ts">
	import InstallAppControl from '$lib/components/InstallAppControl.svelte';
	import ThemeToggle from '$lib/components/ThemeToggle.svelte';
	import LedgerRow from '$lib/components/visual/LedgerRow.svelte';
</script>

<section class="settings-section" aria-labelledby="appearance-settings-heading">
	<header class="section-heading">
		<h2 id="appearance-settings-heading">Appearance</h2>
	</header>
	<div class="settings-group">
		<LedgerRow label="Theme" value="Light or dark interface">
			{#snippet action()}<ThemeToggle />{/snippet}
		</LedgerRow>
	</div>
	<div class="settings-group">
		<InstallAppControl />
	</div>
</section>
