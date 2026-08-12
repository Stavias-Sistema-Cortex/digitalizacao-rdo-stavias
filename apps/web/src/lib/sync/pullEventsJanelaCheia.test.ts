import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * Uma janela cheia não é uma janela quebrada.
 *
 * O aparelho que acabou de entrar começa no cursor zero e tem o histórico
 * inteiro da empresa pela frente. Ele atravessa isso em várias janelas, e cada
 * uma delas termina no teto de páginas — que é o comportamento previsto, não um
 * defeito. Enquanto o teto lançava exceção, toda sincronização desse aparelho
 * terminava em erro: a tarja vermelha ficava acesa, o aviso de conclusão nunca
 * era disparado e nenhuma tela recarregava com o que tinha acabado de chegar.
 * Quem já estava em dia — o dono do sistema, entre outros — nunca encostava no
 * teto e não tinha como reproduzir.
 */

const mocks = vi.hoisted(() => ({
  guard: { fingerprint: "sessao", userId: "usuario" },
  syncState: { lastPulledCommitSeq: 0 },
  pullEventsApi: vi.fn(),
  applyPulledEventsAtomically: vi.fn(),
}));

vi.mock("../db/syncStateRepository", () => ({
  getSyncState: vi.fn(async () => mocks.syncState),
}));

vi.mock("./syncApiClient", () => ({
  pullEventsApi: mocks.pullEventsApi,
}));

vi.mock("./syncStorage", () => ({
  applyPulledEventsAtomically: mocks.applyPulledEventsAtomically,
}));

vi.mock("./syncSession", () => ({
  captureOnlineSyncSession: vi.fn(() => mocks.guard),
  assertSyncSession: vi.fn(),
}));

import { pullEvents } from "./pullEvents";

const PAGINAS_POR_JANELA = 50;
const EVENTOS_POR_PAGINA = 100;

function paginaDeEventos(primeiroCommitSeq: number) {
  return Array.from({ length: EVENTOS_POR_PAGINA }, (_, indice) => ({
    commitSeq: primeiroCommitSeq + indice,
    eventoId: `evento-${primeiroCommitSeq + indice}`,
    tipoEvento: "RDO_ATUALIZADO",
    entidadeTipo: "RDO",
    entidadeId: "rdo",
    payload: {},
  }));
}

describe("pullEvents quando o servidor tem mais do que cabe na janela", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.syncState = { lastPulledCommitSeq: 0 };
    let cursor = 0;
    mocks.pullEventsApi.mockImplementation(async () => {
      const eventos = paginaDeEventos(cursor + 1);
      cursor += EVENTOS_POR_PAGINA;
      return {
        eventos,
        nextCommitSeq: cursor,
        hasMore: true,
      };
    });
    mocks.applyPulledEventsAtomically.mockImplementation(
      async (_eventos: unknown, nextCommitSeq: number) => nextCommitSeq,
    );
  });

  it("devolve a janela cheia como pendente, sem derrubar a sincronização", async () => {
    const resumo = await pullEvents("dispositivo");

    expect(resumo.pendente).toBe(true);
    expect(resumo.pulled).toBe(PAGINAS_POR_JANELA * EVENTOS_POR_PAGINA);
    // O cursor avançou e está gravado: a próxima janela continua daqui, e
    // nenhum evento é relido nem perdido.
    expect(resumo.lastAppliedCommitSeq).toBe(
      PAGINAS_POR_JANELA * EVENTOS_POR_PAGINA,
    );
    expect(mocks.pullEventsApi).toHaveBeenCalledTimes(PAGINAS_POR_JANELA);
  });

  it("para no teto de páginas em vez de puxar o histórico inteiro de uma vez", async () => {
    await pullEvents("dispositivo");

    expect(mocks.pullEventsApi).toHaveBeenCalledTimes(PAGINAS_POR_JANELA);
  });
});

describe("pullEvents quando o aparelho alcança o servidor", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.syncState = { lastPulledCommitSeq: 0 };
    mocks.applyPulledEventsAtomically.mockImplementation(
      async (_eventos: unknown, nextCommitSeq: number) => nextCommitSeq,
    );
  });

  it("não fica pendente quando o servidor diz que acabou", async () => {
    mocks.pullEventsApi.mockResolvedValue({
      eventos: paginaDeEventos(1).slice(0, 3),
      nextCommitSeq: 3,
      hasMore: false,
    });

    const resumo = await pullEvents("dispositivo");

    expect(resumo.pendente).toBe(false);
    expect(resumo.pulled).toBe(3);
    expect(mocks.pullEventsApi).toHaveBeenCalledTimes(1);
  });
});

describe("pullEvents diante de um cursor que não progride", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.syncState = { lastPulledCommitSeq: 10 };
  });

  /*
   * O guarda contra laço continua sendo este, e não o teto de páginas: aqui há
   * mais evento a vir e o cursor não se move, então repetir a pergunta nunca
   * terminaria. Isso permanece exceção de propósito.
   */
  it("recusa a janela quando o servidor promete mais e o cursor fica parado", async () => {
    mocks.pullEventsApi.mockResolvedValue({
      eventos: paginaDeEventos(11).slice(0, 1),
      nextCommitSeq: 10,
      hasMore: true,
    });
    mocks.applyPulledEventsAtomically.mockResolvedValue(10);

    await expect(pullEvents("dispositivo")).rejects.toThrow(
      "O servidor informou mais eventos, mas o cursor não avançou.",
    );
  });

  it("recusa a janela quando o cursor tenta regredir", async () => {
    mocks.pullEventsApi.mockResolvedValue({
      eventos: paginaDeEventos(11).slice(0, 1),
      nextCommitSeq: 9,
      hasMore: false,
    });
    mocks.applyPulledEventsAtomically.mockResolvedValue(9);

    await expect(pullEvents("dispositivo")).rejects.toThrow(
      "O cursor de pull tentou regredir.",
    );
  });
});
