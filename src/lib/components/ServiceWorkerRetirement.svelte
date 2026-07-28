<script lang="ts">
	import { onMount } from 'svelte';
	import { updateExistingRunwayWorker } from '$lib/pwa/service-worker-retirement';

	// Temporary handoff for clients that were previously controlled by runway's
	// offline worker. The worker removes itself after clearing runway-owned caches.
	onMount(() => {
		if (!('serviceWorker' in navigator)) return;
		void navigator.serviceWorker
			.getRegistrations()
			.then((registrations) => updateExistingRunwayWorker(registrations, location.origin))
			.catch(() => undefined);
	});
</script>
