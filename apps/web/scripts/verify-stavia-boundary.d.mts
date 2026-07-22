export const LEGACY_LOCAL_STORAGE_KEYS: readonly string[];
export const LEGACY_SNAPSHOT_STORE: string;

export function findAssistantTokens(
  text: string,
): Array<{ index: number; token: string }>;
export function inspectLegacySource(
  files: Array<{ path: string; content: string }>,
): string[];
export function inspectSourceBoundary(
  files: Array<{ path: string; content: string }>,
): string[];
export function verifySourceBoundary(repositoryRoot?: string): void;
export function verifyDist(distRoot?: string): void;
