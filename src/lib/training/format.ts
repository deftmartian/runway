export function formatPace(secondsPerKm: number | null): string {
	if (!secondsPerKm) return '—';
	const rounded = Math.round(secondsPerKm);
	const minutes = Math.floor(rounded / 60);
	const seconds = rounded % 60;
	return `${minutes}:${String(seconds).padStart(2, '0')}/km`;
}
