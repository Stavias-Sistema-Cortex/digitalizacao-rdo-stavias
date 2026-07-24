export interface Viewport {
  name: string;
  width: number;
  height: number;
}

export const VIEWPORTS: readonly Viewport[];
export function readGeometryTokens(
  css: string,
  shellCss?: string,
): Record<string, number>;
export function measureMensagensGeometry(
  viewport: Viewport,
  tokens: Record<string, number>,
): {
  viewport: Viewport;
  frame: { left: number; right: number; width: number };
  workspaceHeight: number;
  columns: Array<{ left: number; right: number; width: number }>;
  controls: Record<string, { left: number; right: number; width: number }>;
};
export function geometryViolations(
  measurement: ReturnType<typeof measureMensagensGeometry>,
  tokens: Record<string, number>,
): string[];
export function verifyMensagensGeometry(
  css?: string,
): Array<ReturnType<typeof measureMensagensGeometry>>;
