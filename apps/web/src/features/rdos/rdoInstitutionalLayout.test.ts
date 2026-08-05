import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";

import { describe, expect, it } from "vitest";

function readSource(relativePath: string): string {
  return readFileSync(resolve(process.cwd(), relativePath), "utf8");
}

function readSourceOrEmpty(relativePath: string): string {
  const path = resolve(process.cwd(), relativePath);
  return existsSync(path) ? readFileSync(path, "utf8") : "";
}

const workspace = readSource(
  "./src/features/rdos/RdoWorkspacePage.tsx",
);
const localList = readSource(
  "./src/features/rdos/RdoLocalList.tsx",
);
const createPage = readSource(
  "./src/features/rdos/RdoCreatePage.tsx",
);
const css = readSourceOrEmpty(
  "./src/features/rdos/RdoWorkspacePage.css",
);

describe("RDO institutional document workspace", () => {
  it("uses the shared institutional header once per RDO view", () => {
    expect(localList).toContain("InstitutionalPageHeader");
    expect(createPage).toContain("InstitutionalPageHeader");
    expect(localList).not.toContain("<h1");
    expect(createPage).not.toContain("<h1");
  });

  it("presents a narrow document index with draft-derived completion", () => {
    expect(createPage).toContain('aria-label="Índice do RDO"');
    expect(createPage).toContain("const rdoSections = useMemo");
    expect(createPage).toContain("section.isComplete");

    for (const sectionId of [
      "identificacao",
      "condicoes",
      "fotos",
      "servicos",
      "rateio",
      "mao-de-obra",
      "equipamentos",
      "materiais",
    ]) {
      expect(createPage).toContain(`id="rdo-${sectionId}"`);
    }

    // O controle geométrico saiu do RDO: a Stavias não o usa, e uma etapa que
    // ninguém preenche só alonga o índice e a sensação de formulário infinito.
    expect(createPage).not.toContain('id="rdo-controle-geometrico"');

    expect(css).toMatch(
      /\.rdo-document-layout\s*\{[^}]*grid-template-columns:\s*minmax\(180px,\s*220px\)\s+minmax\(0,\s*1fr\);/s,
    );
  });

  it("keeps automatic, local and server persistence states separate", () => {
    expect(createPage).toContain("Autossalvamento");
    expect(createPage).toContain("Não habilitado");
    expect(createPage).toContain("Cópia local");
    expect(createPage).toContain("Confirmação do servidor");
    expect(createPage).toContain("InstitutionalStatus");

    expect(css).toMatch(
      /@media \(max-width: 1080px\)\s*\{[\s\S]*?\.rdo-document-status\s*\{[\s\S]*?grid-template-columns:\s*1fr;[\s\S]*?\.rdo-document-status dl\s*\{[\s\S]*?grid-template-columns:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\);/,
    );
  });

  it("wraps long sync states according to the document width instead of the viewport", () => {
    const documentSurfaceRule = css.match(
      /\.rdo-document-surface\s*\{(?<body>[^}]*)\}/s,
    )?.groups?.body ?? "";
    const statusRule = css.match(
      /\.rdo-document-status \.institutional-status\s*\{(?<body>[^}]*)\}/s,
    )?.groups?.body ?? "";
    const datumRule = css.match(
      /\.rdo-document-status dd\s*\{(?<body>[^}]*)\}/s,
    )?.groups?.body ?? "";

    expect(documentSurfaceRule).toContain("container-type: inline-size;");
    expect(statusRule).toContain("max-inline-size: 100%;");
    expect(statusRule).toContain("white-space: normal;");
    expect(statusRule).toContain("overflow-wrap: anywhere;");
    expect(datumRule).toContain("min-inline-size: 0;");
    expect(datumRule).toContain("overflow-wrap: anywhere;");
    expect(css).toMatch(
      /@container \(max-width: 760px\)\s*\{[\s\S]*?\.rdo-document-status dl\s*\{[\s\S]*?grid-template-columns:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\);/,
    );
    expect(css).toMatch(
      /@container \(max-width: 440px\)\s*\{[\s\S]*?\.rdo-document-status dl\s*\{[\s\S]*?grid-template-columns:\s*1fr;/,
    );
  });

  it("uses canonical sync and compact trace primitives in the document register", () => {
    expect(localList).toContain("useSyncStatus");
    expect(localList).toContain("SyncStateStrip");
    expect(localList).toContain("TraceReference");
    expect(localList).toContain("latestEvent");
    expect(workspace).toContain('import "./RdoWorkspacePage.css";');
  });

  it("uses a glass document shell with quiet solid inner cards", () => {
    const statusRule = css.match(
      /\.rdo-document-status\s*\{(?<body>[^}]*)\}/s,
    )?.groups?.body ?? "";
    const documentSurfaceRule = css.match(
      /\.rdo-document-surface\s*\{(?<body>[^}]*)\}/s,
    )?.groups?.body ?? "";
    const formCardRule = css.match(
      /\.rdo-create-workspace \.form-card\s*\{(?<body>[^}]*)\}/s,
    )?.groups?.body ?? "";

    expect(statusRule).toContain("border: 1px solid var(--color-border);");
    expect(statusRule).toContain(
      "border-radius: var(--radius-control);",
    );
    expect(documentSurfaceRule).toContain(
      "border: 1px solid var(--color-border);",
    );
    expect(documentSurfaceRule).toContain(
      "background: var(--surface-glass-fallback);",
    );
    expect(documentSurfaceRule).toContain(
      "box-shadow: var(--glass-shadow);",
    );
    expect(formCardRule).toContain(
      "border: 1px solid var(--color-border);",
    );
    expect(formCardRule).toContain("background: var(--color-surface);");
    expect(formCardRule).toContain("box-shadow: none;");
    expect(css).not.toContain("border: 2px solid var(--color-ink);");
    expect(localList).not.toContain("MetricCard");
    expect(localList).not.toContain('className="metric-card"');
    expect(css).not.toMatch(/border-radius:\s*999px/);
  });
});
