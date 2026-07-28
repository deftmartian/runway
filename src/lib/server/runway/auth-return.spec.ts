import { describe, expect, test } from 'vitest';
import { safeAuthReturnTo } from './auth-return';

describe('authentication return paths', () => {
	test.each([
		[undefined, '/app'],
		['', '/app'],
		['/app', '/app'],
		['/device?user_code=ABCDEFGH', '/device?user_code=ABCDEFGH'],
		['/device?user_code=ABCD-EFGH', '/device?user_code=ABCDEFGH']
	])('accepts a bounded local return path', (input, expected) => {
		expect(safeAuthReturnTo(input)).toBe(expected);
	});

	test.each([
		'https://attacker.example/device?user_code=ABCDEFGH',
		'//attacker.example/device?user_code=ABCDEFGH',
		'/device?user_code=ABC123',
		'/device?user_code=ABCDEFGH&next=https://attacker.example',
		'/device?user_code=ABCDEFGH#fragment',
		'/app/settings'
	])('rejects an untrusted return path: %s', (input) => {
		expect(safeAuthReturnTo(input)).toBe('/app');
	});
});
