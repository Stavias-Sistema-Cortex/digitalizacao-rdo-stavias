import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

function source(path: string): string {
  return readFileSync(new URL(path, import.meta.url), "utf8");
}

/**
 * Só o código conta. Os comentários citam a redação antiga para explicar por
 * que ela saiu, e uma proibição que enxergasse comentário impediria justamente
 * o registro do motivo.
 */
function semComentarios(fonte: string): string {
  return fonte
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/^\s*\/\/.*$/gm, "");
}

const bannerSource = semComentarios(source("./SyncStatusBanner.tsx"));
const homeSource = source("../features/home/HomePage.tsx");
const ledgerSource = source("../features/home/memory/MemoryLedger.tsx");

/**
 * Todo aviso de sincronização precisa terminar numa ação possível.
 *
 * O conflito de versão não é resolvido por retentativa: quando os dois lados
 * mudam o mesmo campo, nenhuma sincronização desfaz o impasse — ele fica até
 * alguém decidir qual versão vale. A tarja, porém, só oferecia "Sincronizar
 * agora" e prometia desbloqueio "temporário", então quem tinha registros
 * presos ficava apertando um botão que nunca mudaria nada, com o marcador
 * vermelho permanente no cabeçalho. A tela de decisão existia na Memória, sem
 * nada que levasse até ela.
 */
describe("saída do conflito de versão", () => {
  it("a tarja leva à Memória filtrada nas pendências", () => {
    expect(bannerSource).toContain("snapshot.conflictCount > 0");
    expect(bannerSource).toContain('to="/home?tab=memory&pendencias=1"');
    expect(bannerSource).toContain("registro em conflito");
  });

  /** Prometer que passa sozinho é o que fazia a pessoa insistir à toa. */
  it("a tarja não promete desbloqueio automático", () => {
    expect(bannerSource).not.toContain("temporariamente bloqueado");
    expect(bannerSource).toContain("decisão sua sobre qual vale");
  });

  it("a Memória abre nas pendências quando chamada pela tarja", () => {
    expect(homeSource).toContain('search.get("pendencias") === "1"');
    expect(homeSource).toContain("somenteRevisaoInicial");
    expect(ledgerSource).toContain("somenteRevisaoInicial");
  });

  /** O atalho de volta ao histórico completo evita prender quem chegou lá. */
  it("o recorte de pendências pode ser desfeito na própria tela", () => {
    expect(ledgerSource).toContain("Mostrar toda a Memória");
    expect(ledgerSource).toContain("ledger.setSomenteRevisao");
  });
});
