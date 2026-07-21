import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

const css = readFileSync(
  new URL("./MensagensPage.css", import.meta.url),
  "utf8",
);

function rule(selector: string): string {
  const start = css.indexOf(`${selector} {`);
  if (start < 0) {
    throw new Error(`Regra CSS ausente: ${selector}`);
  }
  const end = css.indexOf("\n}", start);
  if (end < 0) {
    throw new Error(`Regra CSS sem fechamento: ${selector}`);
  }
  return css.slice(start, end + 2);
}

describe("layout da aba Mensagens", () => {
  it("consulta a largura do frame, não a da viewport", () => {
    expect(rule(".mensagens-frame")).toContain("container-type: inline-size;");
    expect(css).toContain("@container (min-width: 640px)");
    expect(css).toContain("@container (min-width: 1040px)");
  });

  it("empilha em painel único antes de 640px de frame", () => {
    expect(rule(".mensagens-workspace")).toContain(
      "grid-template-columns: minmax(0, 1fr);",
    );
  });

  it("mantém a terceira coluna fora do fluxo quando recolhida", () => {
    expect(rule(".mensagens-workspace--info-hidden")).toContain(
      "grid-template-columns: minmax(260px, 340px) minmax(0, 1fr);",
    );
  });

  it("não introduz tema escuro nesta aba", () => {
    expect(css).not.toContain("prefers-color-scheme: dark");
  });
});
