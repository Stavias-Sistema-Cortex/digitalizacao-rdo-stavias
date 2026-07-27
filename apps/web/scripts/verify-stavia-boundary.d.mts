export const LEGACY_LOCAL_STORAGE_KEYS: readonly string[];
export const LEGACY_SNAPSHOT_STORE: string;
export const CORPORATE_SOURCE_ALLOWLIST: ReadonlySet<string>;
export const CORPORATE_ASSET_ALLOWLIST: ReadonlySet<string>;

export function findAssistantTokens(
  text: string,
): Array<{ index: number; token: string }>;
export function inspectDistCorporateContent(content: string): string[];
export function isViteRuntimeSourceFile(file: string): boolean;
export function inspectLegacySource(
  files: Array<{ path: string; content: string }>,
): string[];
export function inspectPackageBuildScripts(
  scripts: Record<string, string>,
): string[];
export function inspectSourceBoundary(
  files: Array<{ path: string; content: string }>,
  options?: { requireNormalRuntimeFiles?: boolean },
): string[];
export function verifySourceBoundary(repositoryRoot?: string): void;
export function verifyDist(distRoot?: string): void;
