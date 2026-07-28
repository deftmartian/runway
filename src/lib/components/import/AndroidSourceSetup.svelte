<script lang="ts">
	let { androidApplicationId }: { androidApplicationId: string | null } = $props();

	function openAndroidFolderSettings() {
		if (!androidApplicationId) return;
		window.location.href = `intent://folder#Intent;scheme=runway-native;package=${androidApplicationId};end`;
	}
</script>

<section class="setup-section" aria-labelledby="android-app-heading">
	<h3 id="android-app-heading">Android app</h3>
	<p>
		Open Android imports in the signed-in runway app. From there you can connect Health Connect,
		choose a Gadgetbridge folder, or use Android’s GPX share action.
	</p>
	{#if androidApplicationId}
		<button type="button" class="button-link" onclick={openAndroidFolderSettings}>
			Open Android imports
		</button>
	{:else}
		<p class="privacy-note">Open runway on your phone, then choose Settings and Android imports.</p>
	{/if}
	<p class="privacy-note">
		The Android app creates its limited background-import connection after you sign in. There is no
		code to copy between apps.
	</p>
</section>

<style>
	.setup-section {
		display: grid;
		align-content: start;
		gap: 12px;
		width: min(100%, 700px);
		min-width: 0;
		padding: 20px;
		border-block: 1px solid var(--line);
	}

	.setup-section h3,
	.setup-section p {
		margin: 0;
	}

	.setup-section h3 {
		font-size: 1rem;
	}

	.setup-section > p {
		color: var(--muted);
		font-size: 0.9rem;
		line-height: 1.45;
	}

	.setup-section > .button-link {
		justify-self: start;
	}

	.privacy-note {
		padding-left: 10px;
		border-left: 2px solid var(--line);
	}
</style>
