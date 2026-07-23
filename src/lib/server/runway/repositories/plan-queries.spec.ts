import { describe, expect, it } from 'vitest';
import { groupRowsByPlan } from './plan-queries';

describe('plan history row grouping', () => {
	it('keeps each plan row set separate while preserving query order', () => {
		const grouped = groupRowsByPlan([
			{ planId: 'plan-a', id: 'a-1' },
			{ planId: 'plan-b', id: 'b-1' },
			{ planId: 'plan-a', id: 'a-2' }
		]);

		expect(grouped.get('plan-a')).toEqual([
			{ planId: 'plan-a', id: 'a-1' },
			{ planId: 'plan-a', id: 'a-2' }
		]);
		expect(grouped.get('plan-b')).toEqual([{ planId: 'plan-b', id: 'b-1' }]);
		expect(grouped.has('plan-c')).toBe(false);
	});
});
