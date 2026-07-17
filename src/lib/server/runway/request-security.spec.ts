import { describe, expect, test } from 'vitest';
import {
	hasExactRequestOrigin,
	isMutationRequest,
	isWebShareTargetNavigation
} from './request-security';

describe('state-changing request origin checks', () => {
	test('requires an exact origin and rejects missing or sibling origins', () => {
		expect.assertions(3);
		expect(
			hasExactRequestOrigin('https://runway.example.test', 'https://runway.example.test')
		).toBe(true);
		expect(hasExactRequestOrigin(null, 'https://runway.example.test')).toBe(false);
		expect(hasExactRequestOrigin('https://admin.example.test', 'https://runway.example.test')).toBe(
			false
		);
	});

	test('covers every supported state-changing method', () => {
		expect.assertions(5);
		expect(isMutationRequest('POST')).toBe(true);
		expect(isMutationRequest('put')).toBe(true);
		expect(isMutationRequest('PATCH')).toBe(true);
		expect(isMutationRequest('DELETE')).toBe(true);
		expect(isMutationRequest('GET')).toBe(false);
	});

	test('recognizes only a native-style top-level multipart share navigation', () => {
		const request = new Request('https://runway.example.test/app/import/share', {
			method: 'POST',
			headers: {
				'content-type': 'multipart/form-data; boundary=share-boundary',
				'sec-fetch-site': 'none',
				'sec-fetch-mode': 'navigate',
				'sec-fetch-dest': 'document'
			}
		});
		expect(isWebShareTargetNavigation(request, '/app/import/share')).toBe(true);
		expect(isWebShareTargetNavigation(request, '/app/import')).toBe(false);
	});

	test.each([
		['cross-site initiator', { 'sec-fetch-site': 'cross-site' }],
		['same-site initiator', { 'sec-fetch-site': 'same-site' }],
		['non-navigation mode', { 'sec-fetch-mode': 'cors' }],
		['non-document destination', { 'sec-fetch-dest': 'empty' }],
		['explicit origin', { origin: 'https://attacker.example' }],
		['wrong content type', { 'content-type': 'application/x-www-form-urlencoded' }]
	])('rejects a share-target-shaped request with %s', (_label, override) => {
		const headers = new Headers({
			'content-type': 'multipart/form-data; boundary=share-boundary',
			'sec-fetch-site': 'none',
			'sec-fetch-mode': 'navigate',
			'sec-fetch-dest': 'document',
			...override
		});
		const request = new Request('https://runway.example.test/app/import/share', {
			method: 'POST',
			headers
		});
		expect(isWebShareTargetNavigation(request, '/app/import/share')).toBe(false);
	});
});
