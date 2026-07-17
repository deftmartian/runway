export type UpdateReloadState = {
	hasDirtyForms: boolean;
	hasPendingForms: boolean;
	hasBusyElement: boolean;
};

export function updateReloadBlockReason(
	state: UpdateReloadState
): 'unsaved-changes' | 'pending-action' | null {
	if (state.hasPendingForms || state.hasBusyElement) return 'pending-action';
	if (state.hasDirtyForms) return 'unsaved-changes';
	return null;
}
