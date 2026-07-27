import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

describe("local Cortex web origin", () => {
  it("uses localhost consistently for Vite development and preview", () => {
    const packageJson = JSON.parse(
      readFileSync(new URL("../../../package.json", import.meta.url), "utf8"),
    ) as {
      scripts: Record<string, string>;
    };
    const viteConfig = readFileSync(
      new URL("../../../vite.config.ts", import.meta.url),
      "utf8",
    );

    for (const script of [
      "dev:local",
      "dev:compose",
      "preview:local",
      "preview:compose",
    ]) {
      expect(packageJson.scripts[script]).toContain("--host localhost");
      expect(packageJson.scripts[script]).not.toContain("--host 127.0.0.1");
    }

    expect(viteConfig).toMatch(
      /server:\s*\{\s*host:\s*"localhost"/s,
    );
    expect(viteConfig).toMatch(
      /preview:\s*\{\s*host:\s*"localhost"/s,
    );
  });
});
