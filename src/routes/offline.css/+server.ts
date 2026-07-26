import type { RequestHandler } from './$types';

const offlineCss = `
:root {
	color-scheme: light dark;
	font-family:
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
	background: #F4F2EC;
	color: #1D2926;
}

main {
	width: 100%;
	max-width: 540px;
	min-width: 0;
	padding: 24px;
	border-top: 3px solid #236B80;
	background: #FFFDF8;
	overflow-wrap: anywhere;
}

p {
	color: #596963;
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
	border: 1px solid #236B80;
	border-radius: 9px;
	background: #236B80;
	color: #FFFFFF;
	font-weight: 700;
	text-decoration: none;
}

a:focus-visible {
	outline: 3px solid #15566D;
	outline-offset: 3px;
}

@media (prefers-color-scheme: dark) {
	body {
		background: #151A18;
		color: #F0EEE7;
	}

	main {
		border-color: #79BBCD;
		background: #1D2421;
	}

	p {
		color: #B3BBB4;
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
