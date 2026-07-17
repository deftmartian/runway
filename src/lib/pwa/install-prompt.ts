import { writable } from 'svelte/store';

type BeforeInstallPromptEvent = Event & {
	prompt: () => Promise<void>;
	userChoice: Promise<{ outcome: 'accepted' | 'dismissed'; platform: string }>;
};

type InstallPromptState = {
	prompt: BeforeInstallPromptEvent | null;
	installed: boolean;
	guidance: 'ios' | 'browser';
};

export const installPromptState = writable<InstallPromptState>({
	prompt: null,
	installed: false,
	guidance: 'browser'
});

export function startInstallPromptCapture(): () => void {
	installPromptState.set({
		prompt: null,
		installed: isInstalled(),
		guidance: isIosLikePlatform() ? 'ios' : 'browser'
	});

	const handleInstallPrompt = (event: Event) => {
		event.preventDefault();
		installPromptState.update((state) => ({
			...state,
			prompt: event as BeforeInstallPromptEvent
		}));
	};
	const handleInstalled = () => {
		installPromptState.update((state) => ({ ...state, prompt: null, installed: true }));
	};

	globalThis.addEventListener('beforeinstallprompt', handleInstallPrompt);
	globalThis.addEventListener('appinstalled', handleInstalled);

	return () => {
		globalThis.removeEventListener('beforeinstallprompt', handleInstallPrompt);
		globalThis.removeEventListener('appinstalled', handleInstalled);
	};
}

export async function installRunway(prompt: BeforeInstallPromptEvent): Promise<void> {
	await prompt.prompt();
	const choice = await prompt.userChoice;
	installPromptState.update((state) => ({
		...state,
		prompt: null,
		installed: choice.outcome === 'accepted' || state.installed
	}));
}

function isInstalled(): boolean {
	const navigatorWithStandalone = navigator as Navigator & { standalone?: boolean };
	return (
		globalThis.matchMedia('(display-mode: standalone)').matches ||
		navigatorWithStandalone.standalone === true
	);
}

function isIosLikePlatform(): boolean {
	return (
		/iPad|iPhone|iPod/i.test(navigator.userAgent) ||
		(navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)
	);
}
