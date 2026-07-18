<script lang="ts">
	import { enhance } from '$app/forms';
	import { flushSync, onMount, tick } from 'svelte';
	import type { ActionData, PageData } from './$types';
	import OnboardingGoalStep from './OnboardingGoalStep.svelte';
	import OnboardingReviewStep from './OnboardingReviewStep.svelte';
	import OnboardingScheduleStep from './OnboardingScheduleStep.svelte';
	import OnboardingStartingPointStep from './OnboardingStartingPointStep.svelte';
	import {
		errorStep,
		healthBlocksScheduling,
		minimumForStartMode,
		startingPathName,
		steps,
		targetWindowsForTimeZone,
		validationMessage,
		validationStep,
		type OnboardingFieldErrors,
		type OnboardingValues,
		type RaceStartMode,
		type TargetWindows
	} from './onboarding-model';
	import './onboarding.css';

	let { data, form }: { data: PageData; form: ActionData } = $props();
	const initialValues = readInitialValues();
	const initialWindows = readInitialWindows();
	let values = $state<OnboardingValues>({ ...initialValues });
	let windows = $state<TargetWindows>(initialWindows);
	let step = $state(0);
	let isSubmitting = $state(false);
	let hydrated = $state(false);
	let clientMessage = $state('');
	const fieldErrors = $derived((form?.fieldErrors ?? {}) as OnboardingFieldErrors);
	const healthBlocked = $derived(healthBlocksScheduling(values));

	function readInitialValues() {
		return form?.values ?? data.initialValues;
	}

	function readInitialWindows(): TargetWindows {
		return {
			established: data.minimumTargetDate,
			calibration: data.minimumCalibrationTargetDate,
			foundation: data.minimumFoundationTargetDate,
			maximum: data.maximumTargetDate
		};
	}

	onMount(() => {
		hydrated = true;
		if (!values.timeZone) values.timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
		updateTargetWindow(values.timeZone);
	});

	function chooseGoal(kind: OnboardingValues['goalKind']) {
		values.goalKind = kind;
		if (kind === 'foundation') {
			values.startMode = 'foundation_only';
			values.raceDistance = '';
			values.targetDate = '';
		} else if (values.startMode === 'foundation_only') {
			values.startMode = '';
		}
	}

	function chooseStartMode(mode: RaceStartMode) {
		const nextMinimum = minimumForStartMode(mode, windows);
		if (
			values.targetDate &&
			(values.targetDate < nextMinimum || values.targetDate > windows.maximum)
		) {
			values.targetDate = '';
			clientMessage = `${startingPathName(mode)} needs a different target date. Choose the path first, then set the date.`;
		} else {
			clientMessage = '';
		}
		values.startMode = mode;
	}

	function nextStep() {
		goToStep(Math.min(steps.length - 1, step + 1));
	}

	function previousStep() {
		step = Math.max(0, step - 1);
		requestAnimationFrame(focusStep);
	}

	function goToStep(index: number) {
		if (index > step) {
			const problemStep = validationStep(values, windows, Boolean(data.activeGoal));
			if (problemStep !== null && problemStep < index) {
				clientMessage = validationMessage(problemStep, values, windows);
				void focusProblem(problemStep);
				return;
			}
		}
		clientMessage = '';
		step = index;
		requestAnimationFrame(focusStep);
	}

	function focusStep() {
		const container = document.querySelector<HTMLElement>(`#onboarding-step-${step + 1}`);
		if (!container) return;
		container.focus({ preventScroll: true });
		container.scrollIntoView({ block: 'start' });
	}

	function updateTargetWindow(zone: string) {
		const updated = targetWindowsForTimeZone(zone);
		if (updated) windows = updated;
		// The server supplies the field-scoped error for an invalid zone.
	}

	async function focusProblem(targetStep: number) {
		step = targetStep;
		await tick();
		const container = document.querySelector<HTMLElement>(`#onboarding-step-${targetStep + 1}`);
		(
			container?.querySelector<HTMLElement>('[aria-invalid="true"]') ??
			container?.querySelector<HTMLElement>('input:invalid, select:invalid, textarea:invalid') ??
			container?.querySelector<HTMLElement>('input:required, select:required') ??
			container
		)?.focus();
	}

	function validateBeforeSubmit(event: SubmitEvent) {
		const problemStep = validationStep(values, windows, Boolean(data.activeGoal));
		if (problemStep === null) {
			clientMessage = '';
			return;
		}
		event.preventDefault();
		clientMessage = validationMessage(problemStep, values, windows);
		void focusProblem(problemStep);
	}

	$effect(() => {
		if (!form?.message) return;
		const problemStep = errorStep(fieldErrors);
		if (problemStep !== null) void focusProblem(problemStep);
	});
</script>

<main class="page onboarding-page">
	<header class="onboarding-header">
		<div>
			<p class="eyebrow">Plan setup</p>
			<h1>{data.activeGoal ? 'Change goal' : 'Build a plan'}</h1>
		</div>
		{#if data.activeGoal?.state === 'pending'}
			<p class="pending-note" role="status">
				Goal saved. No workouts will be created while pain is present now or a clinician has limited
				running.
			</p>
		{/if}
	</header>

	<nav class="step-rail" aria-label="Plan setup progress">
		{#each steps as label, index (label)}
			<button
				type="button"
				class:current={step === index}
				aria-current={step === index ? 'step' : undefined}
				onclick={() => {
					goToStep(index);
				}}
			>
				<span>{index + 1}</span>{label}
			</button>
		{/each}
	</nav>

	<form
		method="post"
		action="?/createPlan"
		novalidate
		aria-busy={isSubmitting}
		onsubmit={validateBeforeSubmit}
		use:enhance={() => {
			flushSync(() => (isSubmitting = true));
			return async ({ update }) => {
				await update();
				isSubmitting = false;
			};
		}}
	>
		{#if form?.message || clientMessage}
			<p class="message form-message" role="alert">{form?.message ?? clientMessage}</p>
		{/if}

		<fieldset disabled={!hydrated || isSubmitting}>
			<legend class="sr-only">Plan setup</legend>
			<OnboardingGoalStep
				bind:values
				errors={fieldErrors}
				{windows}
				active={step === 0}
				onchoosegoal={chooseGoal}
				onchoosestartmode={chooseStartMode}
			/>
			<OnboardingStartingPointStep bind:values errors={fieldErrors} active={step === 1} />
			<OnboardingScheduleStep
				bind:values
				errors={fieldErrors}
				active={step === 2}
				ontimezoneinput={updateTargetWindow}
			/>
			<OnboardingReviewStep
				bind:values
				errors={fieldErrors}
				active={step === 3}
				hasActiveGoal={Boolean(data.activeGoal)}
			/>

			<div class="step-actions">
				{#if step > 0}
					<button class="button secondary" type="button" onclick={previousStep}>Back</button>
				{/if}
				{#if step < steps.length - 1}
					<button class="button primary" type="button" onclick={nextStep}>Continue</button>
				{:else}
					<button class="button primary" type="submit" disabled={isSubmitting}>
						{isSubmitting
							? 'Saving…'
							: healthBlocked
								? 'Save pending goal'
								: data.activeGoal
									? 'Replace active plan'
									: 'Create plan'}
					</button>
				{/if}
			</div>
		</fieldset>
	</form>
</main>
