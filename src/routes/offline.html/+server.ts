import type { RequestHandler } from './$types';

const offlinePage = `<!doctype html>
<html lang="en">
\t<head>
\t\t<meta charset="utf-8" />
\t\t<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover" />
\t\t<title>runway offline</title>
\t\t<link rel="stylesheet" href="/offline.css" />
\t</head>
\t<body>
\t\t<main>
\t\t\t<h1>Offline</h1>
\t\t\t<p>Reconnect to open your calendar and training data.</p>
\t\t\t<a href="/app">Try again</a>
\t\t</main>
\t</body>
</html>
`;

export const GET: RequestHandler = () =>
	new Response(offlinePage, {
		headers: {
			'Cache-Control': 'public, max-age=0, must-revalidate',
			'Content-Type': 'text/html; charset=utf-8'
		}
	});
