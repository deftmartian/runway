<script lang="ts">
	import { enhance } from '$app/forms';
	import type { ActionData, PageData } from './$types';

	let { data, form }: { data: PageData; form: ActionData } = $props();
</script>

<main class="page device-page">
	<section class="device-card stack">
		<p class="eyebrow">Android sign-in</p>
		<h1>Connect this phone</h1>
		<p>
			{#if data.nativeAppReturn}
				Continue to runway on this phone as <strong>{data.accountName}</strong>.
			{:else}
				The runway app is asking to use <strong>{data.accountName}</strong> on this server.
			{/if}
		</p>
		{#if !data.nativeAppReturn}
			<dl>
				<div>
					<dt>Code</dt>
					<dd>{data.userCode}</dd>
				</div>
			</dl>
		{/if}
		{#if form?.message}
			<p class="message" role="status">{form.message}</p>
		{/if}
		{#if form?.approved}
			<p class="success">Return to the runway app. This browser page can be closed.</p>
		{:else if form?.denied}
			<p>Return to the runway app to cancel or start again.</p>
		{:else if data.status === 'pending'}
			<div class="actions">
				{#if data.nativeAppReturn}
					<form method="post" action="?/approve">
						<input type="hidden" name="userCode" value={data.userCode} />
						<input type="hidden" name="returnTo" value={data.returnTo} />
						<button class="primary">Continue to runway</button>
					</form>
					<form method="post" action="?/deny">
						<input type="hidden" name="userCode" value={data.userCode} />
						<input type="hidden" name="returnTo" value={data.returnTo} />
						<button>Deny</button>
					</form>
				{:else}
					<form method="post" action="?/approve" use:enhance>
						<input type="hidden" name="userCode" value={data.userCode} />
						<input type="hidden" name="returnTo" value={data.returnTo} />
						<button class="primary">Allow this phone</button>
					</form>
					<form method="post" action="?/deny" use:enhance>
						<input type="hidden" name="userCode" value={data.userCode} />
						<input type="hidden" name="returnTo" value={data.returnTo} />
						<button>Deny</button>
					</form>
				{/if}
			</div>
		{:else}
			<p>This request has already been handled.</p>
		{/if}
	</section>
</main>

<style>
	.device-page {
		display: grid;
		place-items: start center;
		padding-top: clamp(24px, 8vh, 80px);
	}

	.device-card {
		width: min(100%, 520px);
		padding: clamp(24px, 5vw, 40px);
		border-radius: var(--radius);
		background: var(--surface);
		box-shadow: var(--elevation);
	}

	h1,
	dl,
	dl div {
		margin: 0;
	}

	dt {
		color: var(--muted);
		font-size: 0.85rem;
		font-weight: 700;
		text-transform: uppercase;
	}

	dd {
		margin: 4px 0 0;
		font-family: ui-monospace, monospace;
		font-size: 1.35rem;
		font-weight: 750;
		letter-spacing: 0.08em;
	}

	.actions {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: 10px;
	}

	.actions button {
		width: 100%;
	}

	.success {
		color: var(--accent-strong);
		font-weight: 650;
	}
</style>
