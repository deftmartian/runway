const fullGitCommitPattern = /^[0-9a-f]{40}$/i;

export function normalizeBuildCommit(buildId: string): string | null {
	const normalized = buildId.trim();
	return fullGitCommitPattern.test(normalized) ? normalized.toLowerCase() : null;
}
