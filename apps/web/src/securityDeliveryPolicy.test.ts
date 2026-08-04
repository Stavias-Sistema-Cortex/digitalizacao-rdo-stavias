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
    expect(dockerfile).toContain(
      "RUN sh ./validate-docker-build-args.sh"
    );
  });
  /**
   * O `_headers` é o caminho por onde a PWA chega de verdade a quem usa: é o
   * Cloudflare Pages que serve produção. Nginx e o preview do Vite tinham
   * teste; ele não. Dava para apagar a CSP da entrega real e a suíte inteira
   * continuaria verde — a rede de proteção existia justamente onde ela menos
   * fazia falta.
   */
  it("aplica a mesma política na entrega do Cloudflare Pages", async () => {
    const pagesHeaders = await readFile(webFile("public/_headers"), "utf8");

    expect(pagesHeaders).toContain("Content-Security-Policy: default-src 'self'");
    expect(pagesHeaders).toContain("frame-ancestors 'none'");
    expect(pagesHeaders).toContain("object-src 'none'");
    expect(pagesHeaders).toContain("script-src 'self';");
    expect(pagesHeaders).toContain("X-Content-Type-Options: nosniff");
    expect(pagesHeaders).toContain("X-Frame-Options: DENY");
    expect(pagesHeaders).toContain(
      "Referrer-Policy: strict-origin-when-cross-origin",
    );
    expect(pagesHeaders).toContain(
      "Permissions-Policy: camera=(), microphone=(), geolocation=(self)",
    );
  });

  /**
   * Sem HSTS, a primeira visita numa rede hostil pode ser rebaixada para HTTP
   * antes de qualquer cabeçalho valer. O app trafega apontamento de obra e
   * documento pessoal; a promessa de canal seguro precisa estar no código, não
   * na configuração de borda que ninguém revisa junto com o diff.
   */
  it("promete canal seguro nos dois caminhos de produção", async () => {
    const [pagesHeaders, securityHeaders] = await Promise.all([
      readFile(webFile("public/_headers"), "utf8"),
      readFile(webFile("security-headers.conf"), "utf8"),
    ]);

    expect(pagesHeaders).toContain(
      "Strict-Transport-Security: max-age=31536000",
    );
    expect(securityHeaders).toContain(
      'add_header Strict-Transport-Security "max-age=31536000" always;',
    );
  });
});
