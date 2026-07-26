import { describe, expect, test } from 'vitest';
import { normalizeBuildCommit } from './build-metadata';

describe('build metadata', () => {
	test('normalizes a full Git commit for display', () => {
		expect(normalizeBuildCommit(' 0123456789ABCDEF0123456789ABCDEF01234567 ')).toBe(
			'0123456789abcdef0123456789abcdef01234567'
		);
	});

	test.each(['', 'development', '0123456', 'g123456789abcdef0123456789abcdef01234567'])(
		'does not present %j as a commit',
		(buildId) => {
			expect(normalizeBuildCommit(buildId)).toBeNull();
		}
	);
});
