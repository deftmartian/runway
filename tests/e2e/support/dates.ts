import { testDate } from '../../support/test-clock';

export function addIsoDays(date: string, days: number): string {
	const parsed = new Date(`${date}T00:00:00.000Z`);
	parsed.setUTCDate(parsed.getUTCDate() + days);
	return parsed.toISOString().slice(0, 10);
}

export function currentCalendarMonth(): string {
	return testDate.slice(0, 7);
}

export function shiftCalendarMonth(month: string, offset: number): string {
	const [yearText, monthText] = month.split('-');
	const date = new Date(Date.UTC(Number(yearText), Number(monthText) - 1 + offset, 1));
	return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, '0')}`;
}

export function calendarMonthLabel(month: string): string {
	return new Date(`${month}-01T00:00:00`).toLocaleDateString(undefined, {
		month: 'long',
		year: 'numeric'
	});
}
