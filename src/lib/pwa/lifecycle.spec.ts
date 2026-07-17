import { describe, expect, it } from 'vitest';
import { updateReloadBlockReason } from './lifecycle';

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
