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

  // As bolhas deixaram de ser teal sólido; texto claro sobre elas sumiria.
  it("mantém o estado de falha legível sobre bolha clara", () => {
    expect(rule(".mensagem-retry")).toContain("color: #a3312a;");
    expect(rule(".mensagem-retry button")).toContain("color: #a3312a;");
    expect(css).not.toContain("#ffd0c9");
    expect(css).not.toContain("color: rgb(255 255 255");
  });

  /*
   * O painel único é decidido por container query. Se o botão voltar ficar
   * preso a uma media query de viewport, existe uma faixa (viewport larga,
   * frame estreito por causa da sidebar redimensionável) em que a lista some
   * e não há como voltar para ela.
   */
  it("revela o botão voltar pela mesma consulta que decide o painel único", () => {
    const escopoDoBotao = css.slice(0, css.indexOf(".mensagens-mobile-back {\n    display: inline-grid"));
    const ultimaAbertura = Math.max(
      escopoDoBotao.lastIndexOf("@container (max-width: 639px)"),
      escopoDoBotao.lastIndexOf("@media"),
    );
    expect(escopoDoBotao.slice(ultimaAbertura)).toContain("@container (max-width: 639px)");
  });

  it("não deixa cromo de gaveta na faixa de painel único", () => {
    const faixaUnica = css.slice(css.indexOf("@container (max-width: 639px)"));
    expect(faixaUnica).toContain(".mensagens-workspace .mensagens-drawer-backdrop");
  });

  /*
   * O busto passa das bordas do viewBox de propósito. Se o recorte subir para
   * `.mensagens-avatar`, o ponto de fila — irmão do disco, meio fora dele —
   * some junto, e a lista deixa de mostrar o que não subiu.
   */
  it("recorta o busto no disco, não no elemento que hospeda o ponto", () => {
    expect(rule(".mensagens-avatar-disco")).toContain("overflow: hidden;");
    expect(rule(".mensagens-avatar-disco")).toContain(
      "border-radius: var(--radius-control);",
    );
    expect(rule(".mensagens-avatar")).not.toContain("overflow: hidden;");
  });

  /* A tinta por conversa é o que devolve o reconhecimento de relance; a camada
     2.1 institucionaliza só a geometria e não pode achatar todos num cinza. */
  it("preserva a tinta por conversa sob a camada institucional", () => {
    expect(css).toContain("--avatar-bg: #d5e5df;");
    expect(css).not.toMatch(
      /\.mensagens-avatar,\s*\n\.mensagens-context-people li > span \{[^}]*background: #dfe5e1;/,
    );
  });

  /* Os seletores de elemento da busca pegavam também o span do avatar. */
  it("mantém o line-clamp da busca preso ao corpo do resultado", () => {
    expect(css).not.toContain(".mensagens-search-results span {");
    expect(rule(".mensagens-search-result-body > span")).toContain(
      "-webkit-line-clamp: 2;",
    );
  });

  /*
   * Quem decide quantas colunas o workspace tem é o @container. Uma media query
   * de viewport que também declare colunas vence por ordem e desmonta o layout
   * na faixa em que as duas consultas discordam: entre 640 e 700px o container
   * já tinha ligado lista e thread, e a coluna única empilhava as duas dentro
   * da altura fixa. Mesma armadilha da altura — mínimo maior que o workspace
   * estoura a moldura em tela baixa.
   */
  it("deixa colunas e altura para o container, mesmo na media query de 700px", () => {
    const inicio = css.indexOf("@media (max-width: 700px)");
    const faixa = css.slice(inicio, css.indexOf("@media", inicio + 1));
    const naFaixa = (seletor: string) => {
      const abre = faixa.indexOf(`${seletor} {`);
      if (abre < 0) throw new Error(`Regra ausente na faixa de 700px: ${seletor}`);
      return faixa.slice(abre, faixa.indexOf("\n  }", abre));
    };
    expect(naFaixa(".mensagens-workspace")).not.toContain("grid-template-columns");
    expect(naFaixa(".mensagens-thread")).not.toContain("min-height: 560px");
  });

  it("distingue o registro que ainda não saiu do aparelho", () => {
    expect(rule(".mensagem-bubble--pendente")).toContain("border-style: dashed;");
    expect(rule(".mensagem-bubble--pendente")).toContain(
      "border-left-color: var(--color-brand-yellow);",
    );
  });

  /*
   * O recorte 2.1 lê a conversa como livro de ocorrências: coluna cronológica
   * única. Se a autoria voltar a mudar o lado ou o fundo, viram bolhas de chat.
   */
  it("mantém os registros numa coluna única, sem bolhas alternadas", () => {
    expect(rule(".mensagem-item")).toContain("align-items: stretch;");
    expect(css).not.toContain(".mensagem-item--mine {");
    expect(rule(".mensagem-bubble")).toContain("border-radius: 3px;");
    expect(rule(".mensagem-bubble--mine")).toContain(
      "border-left-color: var(--color-ink);",
    );
    expect(rule(".mensagem-bubble--mine")).not.toContain("background:");
  });
});
