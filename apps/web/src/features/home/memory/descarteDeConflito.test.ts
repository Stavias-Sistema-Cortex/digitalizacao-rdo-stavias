import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

function source(path: string): string {
  return readFileSync(new URL(path, import.meta.url), "utf8");
}

function rule(css: string, selector: string): string {
  const start = css.indexOf(`${selector} {`);
  if (start < 0) throw new Error(`Regra CSS ausente: ${selector}`);
  const end = css.indexOf("\n}", start);
  if (end < 0) throw new Error(`Regra CSS sem fechamento: ${selector}`);
  return css.slice(start, end + 2);
}

const ledgerCss = source("./MemoryLedger.css");
const ledgerSource = source("./MemoryLedger.tsx");
const hookSource = source("./useMemoryLedger.ts");
const storageSource = source("../../../lib/sync/syncStorage.ts");

/**
 * A decisão que destrava o conflito insolúvel — e o que ela não pode levar
 * junto.
 *
 * Quando os dois lados alteram o mesmo campo não existe fusão a propor, e sem
 * uma forma de abrir mão da própria versão o registro fica preso para sempre.
 * O risco do remédio é maior que o da doença: apagar a mutação junto com a
 * evidência trocaria um aviso incômodo por um buraco no histórico, que é
 * justamente o que este produto promete não ter.
 */
describe("descarte da edição em conflito", () => {
  it("remove a mutação da fila e preserva o evento na Memória", () => {
    const funcao = storageSource.slice(
      storageSource.indexOf("export async function descartarEdicaoEmConflito"),
      storageSource.indexOf("export async function returnMutationToPending"),
    );

    expect(funcao).toContain("outbox.delete(clientMutationId)");
    // O evento é reescrito, nunca removido: é ele que prova que a edição
    // existiu e foi abandonada por decisão de alguém.
    expect(funcao).toContain('result: "DISCARDED"');
    expect(funcao).toContain("eventStore.put");
    expect(funcao).not.toContain("eventStore.delete");
    // Só conflito é descartável por aqui; pendente e recusado têm outra saída.
    expect(funcao).toContain('mutation.status !== "CONFLICT"');
  });

  it("oferece o descarte apenas onde há conflito", () => {
    expect(ledgerSource).toContain('review.status === "CONFLICT"');
    expect(ledgerSource).toContain("Descartar minha versão");
    expect(ledgerSource).toContain("ledger.descartarConflito");
    expect(hookSource).toContain("descartarEdicaoEmConflito");
  });

  /** Quem descarta precisa saber o que ficou para trás. */
  it("explica que o rastro permanece", () => {
    expect(hookSource).toContain(
      "O registro da tentativa continua na Memória.",
    );
  });

  /**
   * O campo de busca é item de um grid ao lado da coluna de filtros, bem mais
   * alta. Sem ancorar o alinhamento, ele estica até a altura da linha e o
   * `min-height` do input vira piso em vez de altura — era assim que a
   * pesquisa virava uma caixa de trezentos pixels.
   */
  it("mantém a pesquisa na altura de um campo", () => {
    /*
     * O que precisa ser garantido é a ausência de esticamento: `stretch` é o
     * padrão do grid, e era ele que transformava o input numa caixa de
     * trezentos pixels. Qualquer alinhamento explícito resolve — `start` e
     * `end` igualmente —, e qual deles usar é decisão de layout, não contrato.
     * A asserção fixava a palavra e não o efeito, e por isso quebrou numa
     * mudança que preservava exatamente aquilo que ela existe para proteger.
     */
    expect(rule(ledgerCss, ".memory-query"))
      .toMatch(/align-items:\s*(?:start|end|center|baseline);/);
    const busca = rule(ledgerCss, ".memory-query__search input");
    expect(busca).toContain("height: 38px;");
    expect(busca).not.toContain("min-height: 44px;");
  });
});
