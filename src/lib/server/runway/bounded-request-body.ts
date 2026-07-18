export type BoundedRequestBodyResult =
	| { result: 'ok'; buffer: Buffer }
	| { result: 'empty' }
	| { result: 'too-large' }
	| { result: 'unreadable' };

export async function readBoundedRequestBody(
	request: Request,
	maximumBytes: number
): Promise<BoundedRequestBodyResult> {
	if (!Number.isSafeInteger(maximumBytes) || maximumBytes < 1) {
		throw new TypeError('maximumBytes must be a positive safe integer');
	}
	const declaredLength = Number(request.headers.get('content-length'));
	if (Number.isFinite(declaredLength) && declaredLength > maximumBytes) {
		return { result: 'too-large' };
	}
	if (!request.body) return { result: 'empty' };

	const reader = request.body.getReader();
	const chunks: Buffer[] = [];
	let total = 0;
	try {
		while (true) {
			const { done, value } = await reader.read();
			if (done) break;
			if (!value || value.byteLength === 0) continue;
			total += value.byteLength;
			if (total > maximumBytes) {
				await reader.cancel();
				return { result: 'too-large' };
			}
			chunks.push(Buffer.from(value));
		}
	} catch {
		return { result: 'unreadable' };
	} finally {
		reader.releaseLock();
	}
	return total === 0 ? { result: 'empty' } : { result: 'ok', buffer: Buffer.concat(chunks, total) };
}
