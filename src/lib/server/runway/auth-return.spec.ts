import { describe, expect, test } from 'vitest';
import {
	isNativeDeviceAuthorizationReturn,
	nativeDeviceAuthorizationCallback,
	safeAuthReturnTo
} from './auth-return';

describe('authentication return paths', () => {
	test.each([
		[undefined, '/app'],
		['', '/app'],
		['/app', '/app'],
		['/device?user_code=ABCDEFGH', '/device?user_code=ABCDEFGH'],
		['/device?user_code=ABCD-EFGH', '/device?user_code=ABCDEFGH'],
		[
			'/device?user_code=ABCD-EFGH&return_to_app=runway-native',
			'/device?user_code=ABCDEFGH&return_to_app=runway-native'
		]
	])('accepts a bounded local return path', (input, expected) => {
		expect(safeAuthReturnTo(input)).toBe(expected);
	});

	test.each([
		'https://attacker.example/device?user_code=ABCDEFGH',
		'//attacker.example/device?user_code=ABCDEFGH',
		'/device?user_code=ABC123',
		'/device?user_code=ABCDEFGH&next=https://attacker.example',
		'/device?user_code=ABCDEFGH&return_to_app=https://attacker.example',
		'/device?user_code=ABCDEFGH&return_to_app=runway-native&next=1',
		'/device?user_code=ABCDEFGH&return_to_app=runway-native&return_to_app=runway-native',
		'/device?user_code=ABCDEFGH&user_code=ABCDEFGH',
		'/device?user_code=ABCDEFGH#fragment',
		'/app/settings'
	])('rejects an untrusted return path: %s', (input) => {
		expect(safeAuthReturnTo(input)).toBe('/app');
	});

	test('recognizes only the exact native device return marker', () => {
		expect(
			isNativeDeviceAuthorizationReturn('/device?user_code=ABCDEFGH&return_to_app=runway-native')
		).toBe(true);
		expect(isNativeDeviceAuthorizationReturn('/device?user_code=ABCDEFGH')).toBe(false);
		expect(
			isNativeDeviceAuthorizationReturn(
				'/device?user_code=ABCDEFGH&return_to_app=https://attacker.example'
			)
		).toBe(false);
	});

	test.each(['approved', 'denied'] as const)(
		'builds a fixed native callback for %s without sensitive request data',
		(result) => {
			const callback = nativeDeviceAuthorizationCallback(result);
			expect(callback).toBe(`runway-native://auth?result=${result}`);
			const parsed = new URL(callback);
			expect(parsed.protocol).toBe('runway-native:');
			expect(parsed.hostname).toBe('auth');
			expect([...parsed.searchParams.entries()]).toEqual([['result', result]]);
		}
	);
});
