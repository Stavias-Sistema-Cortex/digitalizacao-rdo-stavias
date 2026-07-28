import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const css = readFileSync(
  new URL("./gestao/gestaoObras.css", import.meta.url),
  "utf8",
);
const formCss = readFileSync(
  new URL("./gestao/NovaObraForm.css", import.meta.url),
  "utf8",
);

function rule(source: string, selector: string): string {
  const start = source.indexOf(`${selector} {`);
  if (start < 0) throw new Error(`Regra ausente: ${selector}`);
  const end = source.indexOf("\n}", start);
  if (end < 0) throw new Error(`Regra sem fechamento: ${selector}`);
  return source.slice(start, end + 2);
}

function lastRule(source: string, selector: string): string {
  const start = source.lastIndexOf(`${selector} {`);
  if (start < 0) throw new Error(`Regra ausente: ${selector}`);
  const end = source.indexOf("\n}", start);
  if (end < 0) throw new Error(`Regra sem fechamento: ${selector}`);
  return source.slice(start, end + 2);
}

describe("Obras responsive and focus contracts", () => {
  it("sizes Trash from its real container when the resizable sidebar is wide", () => {
    expect(rule(css, ".obras-trash")).toContain(
      "container-type: inline-size;",
    );
    expect(rule(css, ".obras-trash")).toContain("max-inline-size: 100%;");
    expect(css).toContain("@container (max-width: 680px)");
    const compact = css.slice(css.indexOf("@container (max-width: 680px)"));
    expect(compact).toContain(".obras-trash-list li");
    expect(compact).toContain(
      "grid-template-columns: minmax(0, 1fr) auto;",
    );
  });

  it("keeps visible keyboard focus instead of suppressing the outline", () => {
    expect(css).not.toMatch(/outline:\s*none/);
    const focused = rule(
      css,
      ".obras-lifecycle-actions button:focus-visible",
    );
    expect(focused).toContain("outline: 2px solid");
    expect(focused).toContain("outline-offset: 2px;");
  });

  it("pads edit and confirmation bodies and caps create actions at weight 600", () => {
    expect(rule(css, ".obras-edit-form")).toContain("padding: 20px;");
    expect(rule(css, ".obras-confirm-dialog > p")).toContain(
      "padding: 0 20px;",
    );
    expect(lastRule(css, ".obras-confirm-dialog footer")).toContain(
      "padding: 0 20px 20px;",
    );
    expect(rule(css, ".obras-create-action")).toContain(
      "font-weight: 600;",
    );
    expect(formCss).toMatch(
      /\.nova-obra-form footer button \{[^}]*font-weight: 600;/,
    );
  });
});
