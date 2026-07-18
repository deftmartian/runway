import { describe, expect, test } from 'vitest';
import { readBoundedRequestBody } from './bounded-request-body';

describe('bounded request body reader', () => {
	test('reads a body at the exact limit', async () => {
		const request = new Request('https://runway.example.test/api/android/import', {
			method: 'POST',
			body: Buffer.from('12345')
		});
		await expect(readBoundedRequestBody(request, 5)).resolves.toEqual({
			result: 'ok',
			buffer: Buffer.from('12345')
		});
	});

	test('rejects a declared oversized body without reading it', async () => {
		const request = new Request('https://runway.example.test/api/android/import', {
			method: 'POST',
			headers: { 'content-length': '6' },
			body: Buffer.from('123456')
		});
		await expect(readBoundedRequestBody(request, 5)).resolves.toEqual({ result: 'too-large' });
	});

	test('stops a chunked stream once its accumulated size exceeds the limit', async () => {
		const stream = new ReadableStream<Uint8Array>({
			start(controller) {
				controller.enqueue(Buffer.from('123'));
				controller.enqueue(Buffer.from('456'));
				controller.close();
			}
		});
		const request = new Request('https://runway.example.test/api/android/import', {
			method: 'POST',
			body: stream,
			duplex: 'half'
		} as RequestInit & { duplex: 'half' });
		await expect(readBoundedRequestBody(request, 5)).resolves.toEqual({ result: 'too-large' });
	});

	test('distinguishes an empty body', async () => {
		const request = new Request('https://runway.example.test/api/android/import', {
			method: 'POST'
		});
		await expect(readBoundedRequestBody(request, 5)).resolves.toEqual({ result: 'empty' });
	});
});
