import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

function source(path: string): string {
  return readFileSync(new URL(path, import.meta.url), "utf8");
}

const equipesCss = source("./EquipesPage.css");
const mensagensCss = source("../mensagens/MensagensPage.css");
const equipesTsx = source("./EquipesPage.tsx");

/**
 * Cores livres que sobrevivem por terem função declarada.
 *
 * A escada de avatares de Mensagens é calibrada: cinco pares figura/disco
 * entre 4.2:1 e 4.9:1, para que nenhuma conversa grite mais alto que outra por
 * sorteio de cor. Achatá-los nos tokens genéricos deixaria dois deles idênticos
 * e desfaria a calibração — são a exceção, e por isso estão nomeados aqui.
 */
const CORES_DE_AVATAR = new Set([
  "#d5e5df", "#2f6b5f",
  "#dee7d4", "#566d3f",
  "#f0e5c8", "#7d5f1e",
  "#eddcd2", "#855136",
  "#dfe3e4", "#5f6b70",
]);

function coresLivres(css: string): string[] {
  return [...css.matchAll(/#[0-9a-fA-F]{3,8}\b/g)]
    .map((match) => match[0].toLowerCase())
    .filter((cor) => !CORES_DE_AVATAR.has(cor));
}

/**
 * Equipes e Mensagens tiram cor dos tokens da marca, como o resto do app.
 *
 * As duas abas carregavam paletas próprias — Equipes tinha mais de cento e
 * cinquenta cores soltas contra uma dúzia de tokens — e o efeito era o de
 * telas montadas por gente diferente em semanas diferentes: o mesmo cinza de
 * apoio aparecia em cinco valores, o teal do cabeçalho não era o teal do app.
 * É disso que vem a sensação de tela genérica, antes de qualquer decisão de
 * layout.
 */
describe("política visual de Equipes e Mensagens", () => {
  it("não declara paleta própria fora da escada de avatares", () => {
    expect(coresLivres(equipesCss)).toEqual([]);
    expect(coresLivres(mensagensCss)).toEqual([]);
  });

  /**
   * Cada seção do detalhe tinha uma linha de apoio acima do título dizendo o
   * que o título já dizia — "Composição atual" sobre "Membros ativos",
   * "Memória temporal" sobre "Histórico de membros". Duas alturas de texto
   * para um dado só é o que faz uma tela de trabalho ler como apresentação de
   * produto.
   */
  it("não reintroduz rótulos de apoio acima dos títulos de seção", () => {
    for (const jargao of [
      "Composição atual",
      "Relações consultáveis",
      "Memória temporal",
      "Histórico ontológico",
      "Participação temporal",
      "Administração Alfa",
    ]) {
      expect(equipesTsx).not.toContain(jargao);
    }
    expect(equipesCss).not.toContain(".teams-section header p");
  });

  /** Marcas de inicial e barrinhas decorativas não voltam como enfeite. */
  it("não reintroduz enfeite sem função", () => {
    expect(equipesTsx).not.toContain("teams-list-marker");
    expect(equipesTsx).not.toContain("teams-title-mark");
    expect(equipesTsx).not.toContain("teams-danger-zone");
    expect(equipesCss).not.toContain("teams-list-marker");
    expect(equipesCss).not.toContain("teams-title-mark");
  });
});
