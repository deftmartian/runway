import { writable } from 'svelte/store';

export type UpdateReloadState = {
	hasDirtyForms: boolean;
	hasPendingForms: boolean;
	hasBusyElement: boolean;
};

export type ServiceWorkerSetupState =
	| 'checking'
	| 'ready'
	| 'failed'
	| 'unsupported'
	| 'development';

export const enhancedFormSavedEvent = 'runway:enhanced-form-saved';
export const serviceWorkerSetupState = writable<ServiceWorkerSetupState>('checking');

export function notifyEnhancedFormSaved(form: HTMLFormElement): void {
	form.ownerDocument.dispatchEvent(
		new CustomEvent(enhancedFormSavedEvent, {
			detail: { form }
		})
	);
}

export function enhancedFormsShareSaveScope(
	candidate: HTMLFormElement,
	saved: HTMLFormElement
): boolean {
	if (candidate === saved) return true;
	const scope = saved.dataset['pwaDirtyScope'];
	return Boolean(scope && candidate.dataset['pwaDirtyScope'] === scope);
}

export function serviceWorkerSetupMessage(state: ServiceWorkerSetupState): string | null {
	if (state === 'failed') {
		return 'runway could not finish browser app setup. Retry before installing it; the website still works while online.';
	}
	if (state === 'unsupported') {
		return 'This browser cannot set up runway as an installed browser app. You can keep using the website.';
	}
	return null;
}

export function updateReloadBlockReason(
	state: UpdateReloadState
): 'unsaved-changes' | 'pending-action' | null {
	if (state.hasPendingForms || state.hasBusyElement) return 'pending-action';
	if (state.hasDirtyForms) return 'unsaved-changes';
	return null;
}
