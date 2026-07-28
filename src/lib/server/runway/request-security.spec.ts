import { describe, expect, test } from 'vitest';
import {
	hasExactRequestOrigin,
	isAndroidNativeApiRequest,
	isMutationRequest
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
		const healthConnect = new Request(
			'https://runway.example.test/api/android/health-connect/changes',
			{
				method: 'POST',
				headers: {
					authorization: 'Bearer rwy1_device_secret',
					'content-type': 'application/json',
					'x-runway-client': 'runway-android/1'
				}
			}
		);
		expect(isAndroidNativeApiRequest(pairing, '/api/android/pair')).toBe(true);
		expect(isAndroidNativeApiRequest(upload, '/api/android/import')).toBe(true);
		expect(isAndroidNativeApiRequest(disconnect, '/api/android/status')).toBe(true);
		expect(isAndroidNativeApiRequest(healthConnect, '/api/android/health-connect/changes')).toBe(
			true
		);
	});

	test('allows the versioned native session boundary, but never its legacy import credential', () => {
		const deviceCode = new Request('https://runway.example.test/api/auth/device/code', {
			method: 'POST',
			headers: {
				'content-type': 'application/json',
				'x-runway-client': 'runway-android/2'
			}
		});
		const mobileAction = new Request(
			'https://runway.example.test/api/mobile/v1/action/record-feedback',
			{
				method: 'POST',
				headers: {
					authorization: 'Bearer better-auth-session-token',
					'content-type': 'application/json; charset=utf-8',
					'x-runway-client': 'runway-android/2'
				}
			}
		);
		const legacyCredential = new Request(
			'https://runway.example.test/api/mobile/v1/action/record-feedback',
			{
				method: 'POST',
				headers: {
					authorization: 'Bearer rwy1_device_secret',
					'content-type': 'application/json',
					'x-runway-client': 'runway-android/2'
				}
			}
		);

		expect(isAndroidNativeApiRequest(deviceCode, '/api/auth/device/code')).toBe(true);
		expect(isAndroidNativeApiRequest(mobileAction, '/api/mobile/v1/action/record-feedback')).toBe(
			true
		);
		expect(
			isAndroidNativeApiRequest(legacyCredential, '/api/mobile/v1/action/record-feedback')
		).toBe(false);
	});

	test.each([
		['browser origin', { origin: 'https://attacker.example' }, false],
		['missing JSON content type', {}, true],
		['wrong client generation', { 'x-runway-client': 'runway-android/1' }, false],
		['missing bearer', { authorization: '' }, false]
	])(
		'rejects malformed no-origin mobile action requests with %s',
		(_label, override, omitContentType) => {
			const headers = new Headers({
				authorization: 'Bearer better-auth-session-token',
				'x-runway-client': 'runway-android/2',
				...override
			});
			if (!omitContentType) headers.set('content-type', 'application/json');
			const request = new Request(
				'https://runway.example.test/api/mobile/v1/action/record-feedback',
				{ method: 'POST', headers }
			);
			expect(isAndroidNativeApiRequest(request, '/api/mobile/v1/action/record-feedback')).toBe(
				false
			);
		}
	);

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
