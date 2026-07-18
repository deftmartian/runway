const applicationIdPattern = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/;
const certificateFingerprintPattern = /^(?:[0-9A-F]{2}:){31}[0-9A-F]{2}$/;

export type AndroidAssetLinksStatement = {
	relation: ['delegate_permission/common.handle_all_urls'];
	target: {
		namespace: 'android_app';
		package_name: string;
		sha256_cert_fingerprints: string[];
	};
};

export function buildAndroidAssetLinks(
	applicationIdInput: string | undefined,
	fingerprintsInput: string | undefined
): AndroidAssetLinksStatement[] | null {
	const applicationId = applicationIdInput?.trim() ?? '';
	if (!applicationIdPattern.test(applicationId)) return null;
	const fingerprints = Array.from(
		new Set(
			(fingerprintsInput ?? '')
				.split(',')
				.map((value) => value.trim().toUpperCase())
				.filter(Boolean)
		)
	);
	if (
		fingerprints.length === 0 ||
		fingerprints.some((value) => !certificateFingerprintPattern.test(value))
	) {
		return null;
	}
	return [
		{
			relation: ['delegate_permission/common.handle_all_urls'],
			target: {
				namespace: 'android_app',
				package_name: applicationId,
				sha256_cert_fingerprints: fingerprints
			}
		}
	];
}
