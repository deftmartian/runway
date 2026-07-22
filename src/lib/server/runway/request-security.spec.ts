import { describe, expect, test } from 'vitest';
import {
	hasExactRequestOrigin,
	isAndroidNativeApiRequest,
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

	test('allows only narrowly shaped no-origin Android API mutations', () => {
		const pairing = new Request('https://runway.example.test/api/android/pair', {
			method: 'POST',
			headers: {
				'content-type': 'application/json',
				'x-runway-client': 'runway-android/1'
			}
		});
		const upload = new Request('https://runway.example.test/api/android/import', {
			method: 'POST',
			headers: {
				authorization: 'Bearer rwy1_device_secret',
				'content-type': 'application/gpx+xml',
				'x-runway-client': 'runway-android/1'
			}
		});
		const disconnect = new Request('https://runway.example.test/api/android/status', {
			method: 'DELETE',
			headers: {
				authorization: 'Bearer rwy1_device_secret',
				'x-runway-client': 'runway-android/1'
			}
		});
		expect(isAndroidNativeApiRequest(pairing, '/api/android/pair')).toBe(true);
		expect(isAndroidNativeApiRequest(upload, '/api/android/import')).toBe(true);
		expect(isAndroidNativeApiRequest(disconnect, '/api/android/status')).toBe(true);
	});

	test('rejects malformed native device disconnection requests', () => {
		const missingBearer = new Request('https://runway.example.test/api/android/status', {
			method: 'DELETE',
			headers: { 'x-runway-client': 'runway-android/1' }
		});
		const browserOrigin = new Request('https://runway.example.test/api/android/status', {
			method: 'DELETE',
			headers: {
				authorization: 'Bearer rwy1_device_secret',
				origin: 'https://attacker.example',
				'x-runway-client': 'runway-android/1'
			}
		});
		expect(isAndroidNativeApiRequest(missingBearer, '/api/android/status')).toBe(false);
		expect(isAndroidNativeApiRequest(browserOrigin, '/api/android/status')).toBe(false);
	});

	test.each([
		['browser origin', { origin: 'https://attacker.example' }, '/api/android/pair'],
		[
			'form content type',
			{ 'content-type': 'application/x-www-form-urlencoded' },
			'/api/android/pair'
		],
		['missing client marker', { 'x-runway-client': '' }, '/api/android/pair'],
		['missing bearer', { authorization: '' }, '/api/android/import'],
		['generic binary', { 'content-type': 'application/octet-stream' }, '/api/android/import'],
		['unlisted path', {}, '/api/android/other']
	])('rejects Android mutation exception with %s', (_label, override, pathname) => {
		const headers = new Headers({
			authorization: 'Bearer rwy1_device_secret',
			'content-type': pathname === '/api/android/pair' ? 'application/json' : 'application/gpx+xml',
			'x-runway-client': 'runway-android/1',
			...override
		});
		const request = new Request(`https://runway.example.test${pathname}`, {
			method: 'POST',
			headers
		});
		expect(isAndroidNativeApiRequest(request, pathname)).toBe(false);
	});
});
