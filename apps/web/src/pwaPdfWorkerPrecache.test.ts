import { execFileSync } from "node:child_process";
import {
  mkdtempSync,
  readdirSync,
  readFileSync,
  rmSync,
} from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { describe, expect, it } from "vitest";

const WEB_ROOT = fileURLToPath(new URL("..", import.meta.url));
const VITE_BIN = path.join(
  WEB_ROOT,
  "node_modules",
  "vite",
  "bin",
  "vite.js",
);

describe("PWA PDF worker delivery", () => {
  it("precaches the emitted PDF worker module for a first offline import", () => {
    const distRoot = mkdtempSync(
      path.join(tmpdir(), "cortex-pdf-worker-precache-"),
    );

    try {
      execFileSync(
        process.execPath,
        [VITE_BIN, "build", "--outDir", distRoot],
        {
          cwd: WEB_ROOT,
          env: {
            ...process.env,
            VITE_CORTEX_AUTH_MODE: "postgresql",
          },
          stdio: "pipe",
        },
      );

      const pdfWorker = readdirSync(path.join(distRoot, "assets"))
        .find((file) => /^pdf\.worker-[A-Za-z0-9_-]+\.mjs$/.test(file));

      expect(pdfWorker).toBeDefined();
      expect(readFileSync(path.join(distRoot, "sw.js"), "utf8"))
        .toContain(`assets/${pdfWorker}`);
    } finally {
      rmSync(distRoot, { recursive: true, force: true });
    }
  }, 30_000);
});
