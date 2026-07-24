import { readFile } from "node:fs/promises";

import { describe, expect, it } from "vitest";

import { stripSourceMapReferences } from "../securityDeliveryPolicy";

const webFile = (path: string) => new URL(`../${path}`, import.meta.url);

describe("frontend security delivery policy", () => {
  it("removes source-map references from emitted JavaScript assets", () => {
    const emittedAsset = [
      "const worker = true;",
      "//# sourceMappingURL=pdf.worker.mjs.map",
      "/*# sourceMappingURL=styles.css.map */",
    ].join("\n");

    expect(stripSourceMapReferences(emittedAsset)).toBe(
      "const worker = true;\n"
    );
  });

  it("disables production source maps and keeps the strict CSP on preview", async () => {
    const viteConfig = await readFile(webFile("vite.config.ts"), "utf8");

    expect(viteConfig).toMatch(/build:\s*{[\s\S]*?sourcemap:\s*false/);
    expect(viteConfig.match(/headers:\s*securityHeaders/g)).toHaveLength(1);
    expect(viteConfig.match(/headers:\s*developmentSecurityHeaders/g)).toHaveLength(1);
    expect(viteConfig).toContain('"Content-Security-Policy"');
    expect(viteConfig).toContain("script-src 'self'");
    expect(viteConfig).toContain("script-src 'self' 'unsafe-inline'");
    expect(viteConfig).toContain('"X-Content-Type-Options": "nosniff"');
    expect(viteConfig).toContain("stripSourceMapReferencesPlugin()");
  });

  it("serves every Nginx response with the shared security header policy", async () => {
    const [nginxConfig, securityHeaders, dockerfile] = await Promise.all([
      readFile(webFile("nginx.conf"), "utf8"),
      readFile(webFile("security-headers.conf"), "utf8"),
      readFile(webFile("Dockerfile"), "utf8"),
    ]);

    expect(securityHeaders).toContain(
      'add_header Content-Security-Policy "default-src \'self\''
    );
    expect(securityHeaders).toContain("script-src 'self'");
    expect(securityHeaders).toContain(
      'add_header X-Content-Type-Options "nosniff" always;'
    );
    expect(securityHeaders).toContain(
      'add_header X-Frame-Options "DENY" always;'
    );
    expect(securityHeaders).toContain(
      'add_header Referrer-Policy "strict-origin-when-cross-origin" always;'
    );
    expect(nginxConfig).toContain(
      "include /etc/nginx/snippets/cortex-security-headers.conf;"
    );
    expect(dockerfile).toContain(
      "COPY security-headers.conf /etc/nginx/snippets/cortex-security-headers.conf"
    );
  });
});
