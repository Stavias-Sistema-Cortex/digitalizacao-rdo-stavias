export const SIDEBAR_WIDTH_KEY = "cortex.sidebar.width";
export const SIDEBAR_WIDTH_DEFAULT = 248;
export const SIDEBAR_WIDTH_MIN = 200;
export const SIDEBAR_WIDTH_MAX = 360;

export function clampSidebarWidth(width: number): number {
  if (!Number.isFinite(width)) {
    return SIDEBAR_WIDTH_DEFAULT;
  }

  return Math.min(
    SIDEBAR_WIDTH_MAX,
    Math.max(SIDEBAR_WIDTH_MIN, Math.round(width)),
  );
}

export function readStoredSidebarWidth(raw: string | null): number {
  if (raw === null || raw.trim() === "") {
    return SIDEBAR_WIDTH_DEFAULT;
  }

  return clampSidebarWidth(Number(raw));
}
