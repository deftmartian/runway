const dayMs = 24 * 60 * 60 * 1000;
const fixedDatePattern = /^\d{4}-\d{2}-\d{2}$/;
const timeZoneFormatterCache = new Map<string, Intl.DateTimeFormat>();

export function toIsoDate(date: Date): string {
	return date.toISOString().slice(0, 10);
}

export function toLocalIsoDate(date: Date): string {
	return date.toLocaleDateString('sv-SE');
}

export function isValidTimeZone(timeZone: string): boolean {
	if (timeZone.length === 0 || timeZone.length > 255) return false;
	try {
		new Intl.DateTimeFormat('en-US', { timeZone }).format(new Date(0));
		return true;
	} catch {
		return false;
	}
}

export function toIsoDateInTimeZone(date: Date, timeZone: string): string {
	if (!isValidTimeZone(timeZone)) throw new Error('Invalid IANA time zone.');
	let formatter = timeZoneFormatterCache.get(timeZone);
	if (!formatter) {
		formatter = new Intl.DateTimeFormat('en-US', {
			timeZone,
			year: 'numeric',
			month: '2-digit',
			day: '2-digit'
		});
		timeZoneFormatterCache.set(timeZone, formatter);
	}
	const parts = formatter.formatToParts(date);
	const year = parts.find((part) => part.type === 'year')?.value;
	const month = parts.find((part) => part.type === 'month')?.value;
	const day = parts.find((part) => part.type === 'day')?.value;
	if (!year || !month || !day) throw new Error('Could not derive the local activity date.');
	return `${year}-${month}-${day}`;
}

export function localDateAtNoon(date: string, timeZone: string): Date {
	const [year, month, day] = date.split('-').map(Number);
	if (!year || !month || !day || !isValidTimeZone(timeZone)) {
		throw new Error('Invalid local date or IANA time zone.');
	}

	const desiredUtc = Date.UTC(year, month - 1, day, 12);
	let instant = new Date(desiredUtc);
	for (let attempt = 0; attempt < 2; attempt += 1) {
		const parts = zonedDateTimeParts(instant, timeZone);
		const representedUtc = Date.UTC(
			parts.year,
			parts.month - 1,
			parts.day,
			parts.hour,
			parts.minute,
			parts.second
		);
		instant = new Date(instant.getTime() + desiredUtc - representedUtc);
	}
	return instant;
}

export function parseIsoDate(date: string): Date {
	if (!fixedDatePattern.test(date)) throw new Error(`Invalid date: ${date}`);
	const parsed = new Date(`${date}T00:00:00.000Z`);
	if (Number.isNaN(parsed.getTime()) || toIsoDate(parsed) !== date) {
		throw new Error(`Invalid date: ${date}`);
	}
	return parsed;
}

export function addDays(date: string, days: number): string {
	return toIsoDate(new Date(parseIsoDate(date).getTime() + days * dayMs));
}

export function daysBetween(startDate: string, endDate: string): number {
	return Math.ceil((parseIsoDate(endDate).getTime() - parseIsoDate(startDate).getTime()) / dayMs);
}

export function currentDate(): Date {
	const fixedDate =
		typeof process === 'undefined' ? undefined : process.env['RUNWAY_FIXED_DATE']?.trim();
	if (fixedDate && fixedDatePattern.test(fixedDate)) {
		return new Date(`${fixedDate}T12:00:00.000`);
	}
	return new Date();
}

export function todayIso(): string {
	return toLocalIsoDate(currentDate());
}

export function todayIsoInTimeZone(timeZone: string): string {
	return toIsoDateInTimeZone(currentDate(), timeZone);
}

export function weekStart(date = currentDate()): string {
	const copy = new Date(date.getFullYear(), date.getMonth(), date.getDate());
	const day = copy.getDay();
	const diff = day === 0 ? -6 : 1 - day;
	copy.setDate(copy.getDate() + diff);
	return toLocalIsoDate(copy);
}

function zonedDateTimeParts(date: Date, timeZone: string) {
	const formatter = new Intl.DateTimeFormat('en-US', {
		timeZone,
		year: 'numeric',
		month: '2-digit',
		day: '2-digit',
		hour: '2-digit',
		minute: '2-digit',
		second: '2-digit',
		hourCycle: 'h23'
	});
	const values = Object.fromEntries(
		formatter
			.formatToParts(date)
			.filter((part) => part.type !== 'literal')
			.map((part) => [part.type, Number(part.value)])
	);
	return {
		year: values['year'] ?? 0,
		month: values['month'] ?? 0,
		day: values['day'] ?? 0,
		hour: values['hour'] ?? 0,
		minute: values['minute'] ?? 0,
		second: values['second'] ?? 0
	};
}
