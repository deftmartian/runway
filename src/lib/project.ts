import { env } from '$env/dynamic/public';

const defaultSourceCodeUrl = 'https://github.com/deftmartian/runway';

export const sourceCodeUrl = safeSourceCodeUrl(env['PUBLIC_SOURCE_URL']);

function safeSourceCodeUrl(value: string | undefined): string {
	if (!value) return defaultSourceCodeUrl;
	try {
		const parsed = new URL(value);
		return parsed.protocol === 'https:' || parsed.protocol === 'http:'
			? parsed.href
			: defaultSourceCodeUrl;
	} catch {
		return defaultSourceCodeUrl;
	}
}
