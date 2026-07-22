import type { RequestHandler } from './$types';

const offlineCss = `
:root {
	color-scheme: light dark;
	font-family:
		Inter,
		ui-sans-serif,
		system-ui,
		-apple-system,
		BlinkMacSystemFont,
		'Segoe UI',
		sans-serif;
}

*,
*::before,
*::after {
	box-sizing: border-box;
}

body {
	display: grid;
	width: 100%;
	max-width: 100%;
	min-width: 0;
	min-height: 100vh;
	min-height: 100dvh;
	margin: 0;
	padding:
		max(16px, env(safe-area-inset-top))
		max(16px, env(safe-area-inset-right))
		max(16px, env(safe-area-inset-bottom))
		max(16px, env(safe-area-inset-left));
	place-items: center;
	background: #edf3f7;
	color: #101923;
}

main {
	width: 100%;
	max-width: 540px;
	min-width: 0;
	padding: 24px;
	border-top: 3px solid #1d6f91;
	background: #ffffff;
	overflow-wrap: anywhere;
}

p {
	color: #5a6b76;
	line-height: 1.55;
}

a {
	display: inline-flex;
	align-items: center;
	justify-content: center;
	min-height: 44px;
	max-width: 100%;
	margin-top: 4px;
	padding: 0 14px;
	border: 1px solid #1d6f91;
	border-radius: 9px;
	background: #1d6f91;
	color: #f8fcff;
	font-weight: 700;
	text-decoration: none;
}

a:focus-visible {
	outline: 3px solid #214fc4;
	outline-offset: 3px;
}

@media (prefers-color-scheme: dark) {
	body {
		background: #071018;
		color: #eef7fb;
	}

	main {
		border-color: #5ec7de;
		background: #0d1822;
	}

	p {
		color: #a6b8c5;
	}
}
`.trimStart();

export const GET: RequestHandler = () =>
	new Response(offlineCss, {
		headers: {
			'Cache-Control': 'public, max-age=0, must-revalidate',
			'Content-Type': 'text/css; charset=utf-8'
		}
	});
