<script lang="ts">
	let {
		pending,
		disabled,
		message,
		onSignIn
	}: {
		pending: boolean;
		disabled: boolean;
		message: string;
		onSignIn: () => void;
	} = $props();
</script>

<button class="ghost" type="button" onclick={onSignIn} {disabled}>
	{pending ? 'Waiting for passkey…' : 'Sign in with passkey'}
</button>
{#if message}
	<p class="message" role="status" aria-live="polite">{message}</p>
{/if}
