import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const dialogCss = readFileSync(
  new URL("./RdoCreationDialog.css", import.meta.url),
  "utf8",
);
const workforceCss = readFileSync(
  new URL("./RdoWorkforceEditor.css", import.meta.url),
  "utf8",
);

function dialogGeometry(viewportWidth: number) {
  const outerPadding = viewportWidth <= 620 ? 20 : 40;
  const width = Math.min(940, viewportWidth - outerPadding);
  return {
    width,
    overflow: width > viewportWidth,
    stacked: width <= 620,
  };
}

describe("geometria do fluxo de criação do RDO", () => {
  it.each([1440, 1280, 1100, 1000, 901, 620, 390])(
    "mantém o diálogo dentro do viewport de %ipx",
    (viewportWidth) => {
      const geometry = dialogGeometry(viewportWidth);
      expect(geometry.width).toBeGreaterThan(0);
      expect(geometry.overflow).toBe(false);
      expect(geometry.stacked).toBe(viewportWidth <= 620);
    },
  );

  it("amarra o modelo verificado ao CSS e contém rolagem nas regiões densas", () => {
    expect(dialogCss).toMatch(/width:\s*min\(940px, 100%\)/);
    expect(dialogCss).toMatch(/@container \(max-width: 620px\)/);
    expect(dialogCss).toMatch(/\.rdo-creation-worksite-list[\s\S]*?overflow-y:\s*auto/);
    expect(dialogCss).toMatch(/@media \(prefers-reduced-motion: reduce\)/);
    expect(workforceCss).toMatch(/\.rdo-workforce-table-region[\s\S]*?overflow:\s*auto/);
    expect(workforceCss).toMatch(/max-width:\s*100%/);
  });
});
