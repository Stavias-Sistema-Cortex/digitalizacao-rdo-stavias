import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

describe("Cortex 2.1 institutional UI policy", () => {
  const css = readFileSync(
    resolve(process.cwd(), "src/index.css"),
    "utf8",
  );

  it("defines the institutional palette and geometry", () => {
    expect(css).toContain("--color-ink: #111312");
    expect(css).toContain("--radius-control: 4px");
    expect(css).toContain("--radius-container: 6px");
  });

  it("provides restrained motion without changing StavIA geometry", () => {
    expect(css).toContain("@media (prefers-reduced-motion: reduce)");
    expect(css).not.toMatch(
      /\.stavia-launcher\s*\{[^}]*border-radius:\s*4px/s,
    );
  });
});
