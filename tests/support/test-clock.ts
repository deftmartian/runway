const isoDatePattern = /^\d{4}-\d{2}-\d{2}$/;

export const defaultTestDate = '2026-05-15';
export const testDate = process.env['RUNWAY_FIXED_DATE'] ?? defaultTestDate;

if (!isoDatePattern.test(testDate)) {
	throw new Error('RUNWAY_FIXED_DATE must use YYYY-MM-DD.');
}

const parsedTestDate = new Date(`${testDate}T00:00:00.000Z`);
if (
	Number.isNaN(parsedTestDate.getTime()) ||
	parsedTestDate.toISOString().slice(0, 10) !== testDate
) {
	throw new Error('RUNWAY_FIXED_DATE must be a real calendar date.');
}

export const testNowIso = `${testDate}T12:00:00.000Z`;

export function fixedBrowserClockScript(): string {
	return `
		(() => {
			const fixedNow = new Date(${JSON.stringify(testNowIso)}).getTime();
			const NativeDate = Date;
			function FixedDate(...args) {
				if (!new.target) return new NativeDate(fixedNow).toString();
				return args.length === 0 ? new NativeDate(fixedNow) : new NativeDate(...args);
			}
			Object.setPrototypeOf(FixedDate, NativeDate);
			FixedDate.now = () => fixedNow;
			FixedDate.parse = NativeDate.parse;
			FixedDate.UTC = NativeDate.UTC;
			FixedDate.prototype = NativeDate.prototype;
			globalThis.Date = FixedDate;
		})();
	`;
}
