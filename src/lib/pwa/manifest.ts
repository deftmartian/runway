export const runwayManifest = {
	id: '/',
	name: 'runway',
	short_name: 'runway',
	description:
		'A self-hosted decision ledger for conservative running plans and plan-versus-actual review.',
	lang: 'en',
	start_url: '/app',
	scope: '/',
	display: 'standalone',
	background_color: '#edf3f7',
	theme_color: '#1d6f91',
	categories: ['fitness', 'productivity'],
	icons: [
		{
			src: '/pwa/icon-192.png',
			sizes: '192x192',
			type: 'image/png',
			purpose: 'any'
		},
		{
			src: '/pwa/icon-512.png',
			sizes: '512x512',
			type: 'image/png',
			purpose: 'any'
		},
		{
			src: '/pwa/maskable-icon-192.png',
			sizes: '192x192',
			type: 'image/png',
			purpose: 'maskable'
		},
		{
			src: '/pwa/maskable-icon-512.png',
			sizes: '512x512',
			type: 'image/png',
			purpose: 'maskable'
		}
	],
	screenshots: [
		{
			src: '/pwa/screenshots/calendar-mobile.png',
			sizes: '390x844',
			type: 'image/png',
			form_factor: 'narrow',
			label: 'Training calendar on mobile'
		},
		{
			src: '/pwa/screenshots/calendar-desktop.png',
			sizes: '1440x900',
			type: 'image/png',
			form_factor: 'wide',
			label: 'Training calendar on desktop'
		}
	],
	shortcuts: [
		{
			name: 'Training calendar',
			short_name: 'Calendar',
			description: 'Open the current training calendar.',
			url: '/app',
			icons: [{ src: '/pwa/icon-192.png', sizes: '192x192', type: 'image/png' }]
		},
		{
			name: 'Activity inbox',
			short_name: 'Activity inbox',
			description: 'Review imported and unmatched activities.',
			url: '/app/import',
			icons: [{ src: '/pwa/icon-192.png', sizes: '192x192', type: 'image/png' }]
		},
		{
			name: 'Training stats',
			short_name: 'Stats',
			description: 'Review plan and training trends.',
			url: '/app/stats',
			icons: [{ src: '/pwa/icon-192.png', sizes: '192x192', type: 'image/png' }]
		}
	],
	share_target: {
		action: '/app/import/share',
		method: 'POST',
		enctype: 'multipart/form-data',
		params: {
			files: [
				{
					name: 'gpx',
					accept: ['.gpx', 'application/gpx+xml', 'application/xml', 'text/xml']
				}
			]
		}
	},
	prefer_related_applications: false
} as const;
