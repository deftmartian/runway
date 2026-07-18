import { describe, expect, it } from 'vitest';
import {
	enhancedFormsShareSaveScope,
	enhancedFormSavedEvent,
	notifyEnhancedFormSaved,
	serviceWorkerSetupMessage,
	updateReloadBlockReason
} from './lifecycle';

describe('PWA update reload safety', () => {
	it('allows an update when the page has no edits or pending actions', () => {
		expect(
			updateReloadBlockReason({
				hasDirtyForms: false,
				hasPendingForms: false,
				hasBusyElement: false
			})
		).toBeNull();
	});

	it('emits an explicit save signal containing the enhanced form', () => {
		const ownerDocument = new EventTarget();
		const form = { ownerDocument } as unknown as HTMLFormElement;
		let saved: unknown;
		ownerDocument.addEventListener(enhancedFormSavedEvent, (event) => {
			saved = (event as CustomEvent<{ form: unknown }>).detail.form;
		});

		notifyEnhancedFormSaved(form);

		expect(saved).toBe(form);
	});

	it('clears only the saved form or forms in its explicit dirty scope', () => {
		const form = (scope?: string) =>
			({ dataset: scope ? { pwaDirtyScope: scope } : {} }) as unknown as HTMLFormElement;
		const workoutEditor = form('workout:one');
		const workoutApply = form('workout:one');
		const otherWorkout = form('workout:two');

		expect(enhancedFormsShareSaveScope(workoutEditor, workoutEditor)).toBe(true);
		expect(enhancedFormsShareSaveScope(workoutEditor, workoutApply)).toBe(true);
		expect(enhancedFormsShareSaveScope(otherWorkout, workoutApply)).toBe(false);
		expect(enhancedFormsShareSaveScope(form(), form())).toBe(false);
	});

	it('offers useful service-worker failure guidance without exposing an exception', () => {
		expect(serviceWorkerSetupMessage('failed')).toBe(
			'runway could not finish browser app setup. Retry before installing it; the website still works while online.'
		);
		expect(serviceWorkerSetupMessage('ready')).toBeNull();
		expect(serviceWorkerSetupMessage('development')).toBeNull();
	});

	it('blocks an update that would discard an edited form', () => {
		expect(
			updateReloadBlockReason({
				hasDirtyForms: true,
				hasPendingForms: false,
				hasBusyElement: false
			})
		).toBe('unsaved-changes');
	});

	it('gives in-flight actions priority over dirty-form state', () => {
		expect(
			updateReloadBlockReason({
				hasDirtyForms: true,
				hasPendingForms: true,
				hasBusyElement: true
			})
		).toBe('pending-action');
	});
});
