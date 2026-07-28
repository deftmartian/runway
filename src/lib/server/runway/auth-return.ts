const deviceCodePattern = /^[A-HJ-NP-Z2-9]{8}$/;

export function safeAuthReturnTo(input: string | null | undefined): string {
	const candidate = input?.trim();
	if (!candidate || candidate === '/app') return '/app';
	if (!candidate.startsWith('/') || candidate.startsWith('//')) return '/app';

	let parsed: URL;
	try {
		parsed = new URL(candidate, 'https://runway.invalid');
	} catch {
		return '/app';
	}
	if (parsed.origin !== 'https://runway.invalid' || parsed.hash || parsed.pathname !== '/device') {
		return '/app';
	}
	const userCode = parsed.searchParams.get('user_code')?.replaceAll('-', '').toUpperCase();
	if (
		!userCode ||
		!deviceCodePattern.test(userCode) ||
		Array.from(parsed.searchParams.keys()).some((key) => key !== 'user_code')
	) {
		return '/app';
	}
	return `/device?user_code=${encodeURIComponent(userCode)}`;
}
