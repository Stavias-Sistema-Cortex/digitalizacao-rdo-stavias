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
      "controle-geometrico",
    ]) {
      expect(createPage).toContain(`id="rdo-${sectionId}"`);
    }

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

  it("uses canonical sync and compact trace primitives in the document register", () => {
    expect(localList).toContain("useSyncStatus");
    expect(localList).toContain("SyncStateStrip");
    expect(localList).toContain("TraceReference");
    expect(localList).toContain("latestEvent");
    expect(workspace).toContain('import "./RdoWorkspacePage.css";');
  });

  it("uses complete rectangular frames without decorative RDO metric pills", () => {
    const statusRule = css.match(
      /\.rdo-document-status\s*\{(?<body>[^}]*)\}/s,
    )?.groups?.body ?? "";

    expect(statusRule).toContain("border: 1px solid var(--color-ink);");
    expect(statusRule).toContain(
      "border-radius: var(--radius-control);",
    );
    expect(localList).not.toContain("MetricCard");
    expect(localList).not.toContain('className="metric-card"');
    expect(css).not.toMatch(/border-radius:\s*999px/);
    expect(css).not.toContain("border-top:");
    expect(css).not.toContain("backdrop-filter");
    expect(css).not.toContain("box-shadow");
  });
});
