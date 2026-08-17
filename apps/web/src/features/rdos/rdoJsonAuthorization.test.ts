import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { describe, expect, it } from "vitest";

const createPage = readFileSync(
  resolve(process.cwd(), "./src/features/rdos/RdoCreatePage.tsx"),
  "utf8",
);

/**
 * O payload cru carrega o RDO inteiro, e quem o vê é só o Alfa.
 *
 * <p>Este teste lê o arquivo porque o que ele guarda é estrutural: que não
 * exista nenhum caminho de renderização do payload fora do portão. Um teste de
 * tela prova o caso que ele monta; este prova que não há um segundo lugar.
 * Quem cuida do comportamento — a gaveta fechada, a barra sem o botão, o
 * sumiço para quem não é Alfa — é RdoCreatePage.barraDeAcoes.test.tsx.
 */
describe("RDO raw JSON authorization", () => {
  it("deriva o direito de ver da sessão, e não da tela", () => {
    expect(createPage).toContain(
      'import { getSession, isAlfa } from "../auth/authSession";',
    );
    expect(createPage).toContain(
      "const canViewRawPayload = isAlfa(getSession());",
    );
  });

  /*
   * O payload nem chega a ser montado para quem não pode vê-lo: sem isto, o
   * RDO inteiro ficaria serializado em memória à espera de um descuido de
   * renderização.
   */
  it("não constrói o payload para quem não pode vê-lo", () => {
    expect(createPage).toMatch(
      /canViewRawPayload\s*\?\s*buildPayload\(draft\)\s*:\s*null/,
    );
  });

  it("tem um único caminho de renderização, e ele passa pelo portão", () => {
    const renderizacoes = createPage.match(/JSON\.stringify\(payload/g) ?? [];
    expect(renderizacoes).toHaveLength(1);

    const portao = createPage.indexOf("{canViewRawPayload && (");
    const gaveta = createPage.indexOf('<details className="json-preview">');
    const impressao = createPage.indexOf("JSON.stringify(payload");

    expect(portao).toBeGreaterThan(-1);
    expect(gaveta).toBeGreaterThan(portao);
    expect(impressao).toBeGreaterThan(gaveta);
  });
});
