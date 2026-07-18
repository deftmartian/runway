import { XMLParser } from 'fast-xml-parser';
import { building } from '$app/environment';
import { env } from '$env/dynamic/private';
import { openSecret } from './secrets';

export type ParsedNextcloudShare = {
	shareHost: string;
	shareToken: string;
};

export type NextcloudShareCredentials = {
	shareHost: string;
	shareTokenSecret: string;
	sharePasswordSecret: string;
};

type OpenedNextcloudShare = ParsedNextcloudShare & {
	sharePassword: string;
};

export type NextcloudRemoteFile = {
	href: string;
	name: string;
	etag: string | null;
	contentLength: number | null;
	lastModifiedAt: Date | null;
};

const parser = new XMLParser({
	ignoreAttributes: false,
	attributeNamePrefix: '',
	parseTagValue: true,
	parseAttributeValue: true,
	processEntities: false,
	trimValues: true,
	removeNSPrefix: true
});

const propfindBody = `<?xml version="1.0"?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:getlastmodified />
    <d:getetag />
    <d:getcontentlength />
    <d:resourcetype />
  </d:prop>
</d:propfind>`;
const requestTimeoutMs = 15_000;
const maxPropfindBytes = 1 * 1024 * 1024;
const maxDownloadBytes = 10 * 1024 * 1024;
export const maxNextcloudVisibleGpxFiles = 2_000;

type NextcloudRequestErrorCode =
	| 'authentication_rejected'
	| 'folder_not_found'
	| 'invalid_webdav_response'
	| 'read_failed';

class NextcloudRequestError extends Error {
	readonly code: NextcloudRequestErrorCode;

	constructor(code: NextcloudRequestErrorCode, message: string) {
		super(message);
		this.name = 'NextcloudRequestError';
		this.code = code;
	}
}

export function parseNextcloudShareUrl(input: string): ParsedNextcloudShare {
	let url: URL;
	try {
		url = new URL(input.trim());
	} catch {
		throw new Error('Nextcloud share URL is not valid.');
	}
	const hostname = normalizeHostname(url.hostname);
	if (url.username || url.password) {
		throw new Error('Nextcloud share URL must not include credentials.');
	}
	if (url.protocol !== 'https:' && !(url.protocol === 'http:' && isDevelopmentLoopback(hostname))) {
		throw new Error('Nextcloud share URL must use HTTPS.');
	}
	if (!isAllowedNextcloudOrigin(url)) {
		throw new Error('Nextcloud share origin is not allowed.');
	}

	const pathParts = url.pathname.split('/').filter(Boolean);
	const shareMarkerIndex = pathParts.indexOf('s');
	const shareToken = shareMarkerIndex >= 0 ? pathParts[shareMarkerIndex + 1] : undefined;
	if (!shareToken || !/^[A-Za-z0-9_-]+$/.test(shareToken)) {
		throw new Error('Nextcloud share URL must include a folder share token.');
	}

	return {
		shareHost: url.origin,
		shareToken
	};
}

export async function listNextcloudGpxFiles(
	source: NextcloudShareCredentials
): Promise<NextcloudRemoteFile[]> {
	const opened = await openNextcloudShare(source);
	const response = await fetchWebDav(webdavUrl(opened), {
		method: 'PROPFIND',
		headers: webdavHeaders(opened.sharePassword, {
			Depth: '1',
			'Content-Type': 'application/xml; charset=utf-8'
		}),
		body: propfindBody
	});

	if (!response.ok) {
		throw nextcloudError(response.status);
	}

	const xml = await readResponseText(response, maxPropfindBytes);
	if (/<\s*!DOCTYPE\b/i.test(xml)) throw invalidWebDavResponse();

	let parsed: WebDavResponse;
	try {
		parsed = parser.parse(xml) as WebDavResponse;
	} catch {
		throw invalidWebDavResponse();
	}
	const responses = asArray(parsed.multistatus?.response);
	if (responses.length === 0 || !hasShareRootCollection(opened, responses)) {
		throw invalidWebDavResponse();
	}
	const files = responses
		.map((record) => remoteFileFromResponse(opened, record))
		.filter((file): file is NextcloudRemoteFile => Boolean(file))
		.filter((file) => file.name.toLowerCase().endsWith('.gpx'));
	return enforceNextcloudVisibleGpxLimit(files);
}

export function enforceNextcloudVisibleGpxLimit<T>(files: T[]): T[] {
	if (files.length > maxNextcloudVisibleGpxFiles) {
		throw new Error(
			`Nextcloud folders can expose at most ${maxNextcloudVisibleGpxFiles} GPX files per sync.`
		);
	}
	return files;
}

export async function downloadNextcloudFile(
	source: NextcloudShareCredentials,
	file: NextcloudRemoteFile
): Promise<Buffer> {
	const opened = await openNextcloudShare(source);
	const response = await fetchWebDav(new URL(file.href, opened.shareHost), {
		method: 'GET',
		headers: webdavHeaders(opened.sharePassword)
	});

	if (!response.ok) throw nextcloudError(response.status);
	return readResponseBuffer(response, maxDownloadBytes);
}

function webdavUrl(source: ParsedNextcloudShare): URL {
	return new URL(
		`/public.php/dav/files/${encodeURIComponent(source.shareToken)}/`,
		source.shareHost
	);
}

async function fetchWebDav(input: URL, init: RequestInit): Promise<Response> {
	try {
		return await fetch(input, {
			...init,
			redirect: 'manual',
			signal: AbortSignal.timeout(requestTimeoutMs)
		});
	} catch {
		throw new Error('Nextcloud share could not be reached.');
	}
}

function webdavHeaders(password: string, extra: Record<string, string> = {}): Headers {
	const headers = new Headers(extra);
	headers.set('Authorization', `Basic ${Buffer.from(`anonymous:${password}`).toString('base64')}`);
	headers.set('X-Requested-With', 'XMLHttpRequest');
	return headers;
}

async function openNextcloudShare(
	source: NextcloudShareCredentials
): Promise<OpenedNextcloudShare> {
	try {
		const [shareToken, sharePassword] = await Promise.all([
			openSecret(source.shareTokenSecret),
			openSecret(source.sharePasswordSecret)
		]);
		return { shareHost: source.shareHost, shareToken, sharePassword };
	} catch {
		throw new Error('Nextcloud source credentials could not be opened.');
	}
}

function normalizeHostname(hostname: string): string {
	return hostname.replace(/^\[|\]$/g, '').toLowerCase();
}

function isDevelopmentLoopback(hostname: string): boolean {
	return !isProductionRuntime() && isLoopbackHost(hostname);
}

function isAllowedNextcloudOrigin(url: URL): boolean {
	const configuredOrigins = (readPrivateEnv('NEXTCLOUD_ALLOWED_ORIGINS') ?? '')
		.split(',')
		.map((origin) => origin.trim())
		.filter(Boolean);
	if (configuredOrigins.length > 0) {
		const allowedOrigins = new Set(configuredOrigins.map(parseAllowedOrigin));
		return allowedOrigins.has(url.origin);
	}
	return !isProductionRuntime() && isLoopbackHost(normalizeHostname(url.hostname));
}

function parseAllowedOrigin(input: string): string {
	let url: URL;
	try {
		url = new URL(input);
	} catch {
		throw new Error('NEXTCLOUD_ALLOWED_ORIGINS contains an invalid origin.');
	}

	const hostname = normalizeHostname(url.hostname);
	const developmentLoopback = !isProductionRuntime() && isLoopbackHost(hostname);
	if (
		(url.protocol !== 'https:' && !(url.protocol === 'http:' && developmentLoopback)) ||
		url.username ||
		url.password ||
		url.pathname !== '/' ||
		url.search ||
		url.hash
	) {
		throw new Error('NEXTCLOUD_ALLOWED_ORIGINS must contain exact HTTPS origins.');
	}

	return url.origin;
}

function isProductionRuntime(): boolean {
	return readPrivateEnv('NODE_ENV') === 'production' && !building;
}

function readPrivateEnv(name: string): string | undefined {
	return process.env[name] ?? env[name];
}

function isLoopbackHost(hostname: string): boolean {
	return (
		hostname === 'localhost' ||
		hostname === '127.0.0.1' ||
		hostname === '::1' ||
		hostname === '0:0:0:0:0:0:0:1'
	);
}

export function isNextcloudAuthenticationRejection(error: unknown): boolean {
	return error instanceof NextcloudRequestError && error.code === 'authentication_rejected';
}

function nextcloudError(status: number): Error {
	if (status === 401 || status === 403) {
		return new NextcloudRequestError(
			'authentication_rejected',
			'Nextcloud share password was rejected.'
		);
	}
	if (status === 404) {
		return new NextcloudRequestError('folder_not_found', 'Nextcloud share folder was not found.');
	}
	return new NextcloudRequestError('read_failed', 'Nextcloud share could not be read.');
}

function invalidWebDavResponse(): NextcloudRequestError {
	return new NextcloudRequestError(
		'invalid_webdav_response',
		'Nextcloud share returned an invalid WebDAV response.'
	);
}

function remoteFileFromResponse(
	source: ParsedNextcloudShare,
	response: WebDavResponseRecord
): NextcloudRemoteFile | null {
	const href = typeof response.href === 'string' ? response.href : '';
	const prop = firstProp(response);
	if (!href || !prop || isCollection(prop.resourcetype)) return null;
	const safeHref = safeRemoteHref(source, href);
	if (!safeHref) return null;
	const name = safeFileName(safeHref);
	if (!name) return null;

	return {
		href: safeHref,
		name,
		etag: typeof prop.getetag === 'string' ? prop.getetag.replace(/^"|"$/g, '') : null,
		contentLength: parseNullableInteger(prop.getcontentlength),
		lastModifiedAt: parseNullableDate(prop.getlastmodified)
	};
}

function safeRemoteHref(source: ParsedNextcloudShare, href: string): string | null {
	try {
		const url = new URL(href, source.shareHost);
		if (url.origin !== source.shareHost) return null;
		const shareBase = shareBasePath(source);
		if (url.pathname !== shareBase && !url.pathname.startsWith(`${shareBase}/`)) return null;
		return `${url.pathname}${url.search}`;
	} catch {
		return null;
	}
}

function hasShareRootCollection(
	source: ParsedNextcloudShare,
	responses: WebDavResponseRecord[]
): boolean {
	const expectedPath = shareBasePath(source);
	return responses.some((response) => {
		if (typeof response.href !== 'string') return false;
		const href = safeRemoteHref(source, response.href);
		if (!href) return false;
		const path = new URL(href, source.shareHost).pathname.replace(/\/$/, '');
		const prop = firstProp(response);
		return path === expectedPath && Boolean(prop && isCollection(prop.resourcetype));
	});
}

function shareBasePath(source: ParsedNextcloudShare): string {
	return `/public.php/dav/files/${encodeURIComponent(source.shareToken)}`;
}

function firstProp(response: WebDavResponseRecord): WebDavProp | null {
	const propstats = asArray(response.propstat);
	const propstat =
		propstats.find((candidate) => isSuccessfulPropStatus(candidate.status)) ??
		propstats.find((candidate) => candidate.status === undefined);
	return propstat?.prop ?? null;
}

function isSuccessfulPropStatus(status: unknown): boolean {
	return typeof status === 'string' && /^HTTP\/\S+\s+2\d\d(?:\s|$)/i.test(status.trim());
}

function isCollection(resourceType: unknown): boolean {
	if (!resourceType || typeof resourceType !== 'object') return false;
	return 'collection' in resourceType;
}

function safeFileName(href: string): string {
	try {
		const pathname = new URL(href, 'https://nextcloud.invalid').pathname;
		return decodeURIComponent(pathname.split('/').filter(Boolean).at(-1) ?? '');
	} catch {
		return '';
	}
}

function parseNullableInteger(value: unknown): number | null {
	if (value === undefined || value === null || value === '') return null;
	const number = Number(value);
	return Number.isFinite(number) ? Math.max(0, Math.round(number)) : null;
}

function parseNullableDate(value: unknown): Date | null {
	if (typeof value !== 'string') return null;
	const date = new Date(value);
	return Number.isNaN(date.getTime()) ? null : date;
}

function asArray<T>(value: T | T[] | undefined): T[] {
	if (value === undefined) return [];
	return Array.isArray(value) ? value : [value];
}

async function readResponseText(response: Response, maxBytes: number): Promise<string> {
	return (await readResponseBuffer(response, maxBytes)).toString('utf8');
}

async function readResponseBuffer(response: Response, maxBytes: number): Promise<Buffer> {
	const declaredLength = Number(response.headers.get('content-length'));
	if (Number.isFinite(declaredLength) && declaredLength > maxBytes) {
		throw new Error('Nextcloud response is too large.');
	}
	if (!response.body) {
		const buffer = Buffer.from(await response.arrayBuffer());
		if (buffer.byteLength > maxBytes) throw new Error('Nextcloud response is too large.');
		return buffer;
	}
	const reader = response.body.getReader();
	const chunks: Uint8Array[] = [];
	let total = 0;

	while (true) {
		const { done, value } = await reader.read();
		if (done) break;
		if (!value) continue;
		total += value.byteLength;
		if (total > maxBytes) {
			await reader.cancel();
			throw new Error('Nextcloud response is too large.');
		}
		chunks.push(value);
	}

	return Buffer.concat(chunks);
}

type WebDavResponse = {
	multistatus?: {
		response?: WebDavResponseRecord | WebDavResponseRecord[];
	};
};

type WebDavResponseRecord = {
	href?: unknown;
	propstat?: WebDavPropStat | WebDavPropStat[];
};

type WebDavPropStat = {
	status?: unknown;
	prop?: WebDavProp;
};

type WebDavProp = {
	getetag?: unknown;
	getcontentlength?: unknown;
	getlastmodified?: unknown;
	resourcetype?: unknown;
};
