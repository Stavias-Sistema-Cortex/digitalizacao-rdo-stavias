import "fake-indexeddb/auto";

import { afterEach, describe, expect, it, vi } from "vitest";

const AUTOR = "10000000-0000-4000-8000-000000000001";
const COLEGA = "10000000-0000-4000-8000-000000000002";

vi.mock("../auth/authSession", () => ({
  getSession: () => ({
    colaboradorId: AUTOR,
    nome: "Quem cria",
    papelAcesso: "ALFA",
    escopoGlobal: true,
    obraIds: [] as string[],
    expiraEm: "2099-01-01T00:00:00.000Z",
  }),
  requireDataScope: () => ({ ownerId: AUTOR, scopeMaterial: "ALFA:GLOBAL" }),
}));

const {
  listLocalConversations,
  queueConversation,
  queueMessage,
  storeServerConversations,
} = await import("./mensagensRepository");
const { getCortexDb } = await import("../../lib/db/cortexDb");

async function outbox() {
  return (await getCortexDb()).getAll("outbox_mutations");
}

afterEach(async () => {
  // O nome do banco vem do escopo do sujeito, então apagar por nome fixo não
  // serve: limpa-se o conteúdo, que é o que um teste precisa isolar.
  const database = await getCortexDb();
  for (const store of [
    "mensagem_conversas",
    "mensagens",
    "mensagem_anexos",
    "outbox_mutations",
  ] as const) {
    await database.clear(store);
  }
});

/**
 * Criar conversa era a única escrita de Mensagens que falava direto com o
 * servidor. Sem rede, o botão ficava desabilitado — e começar uma conversa é
 * exatamente o que se precisa fazer em campo, onde não há rede.
 */
describe("conversa criada no dispositivo", () => {
  it("nasce local e entra na fila de subida", async () => {
    const conversa = await queueConversation({
      tipo: "GRUPO",
      titulo: "Frente da SP-310",
      obraId: null,
      equipeId: null,
      participantes: [{ colaboradorId: COLEGA, nome: "Colega" }],
    });

    expect((await listLocalConversations()).map((item) => item.id)).toEqual([
      conversa.id,
    ]);
    const mutacoes = await outbox();
    expect(mutacoes).toHaveLength(1);
    expect(mutacoes[0]).toMatchObject({
      entidadeTipo: "CONVERSA",
      operacao: "CRIAR_CONVERSA",
      entidadeId: conversa.id,
    });
    // O servidor aceita o id do cliente nesta operação; é ele que amarra as
    // mensagens escritas antes de a conversa subir.
    expect(mutacoes[0].payload.id).toBe(conversa.id);
  });

  /** Quem cria participa, e como administrador — é o que o servidor faz. */
  it("põe quem criou entre os participantes", async () => {
    const conversa = await queueConversation({
      tipo: "DIRETA",
      titulo: null,
      obraId: null,
      equipeId: null,
      participantes: [{ colaboradorId: COLEGA, nome: "Colega" }],
    });

    expect(conversa.participantes).toEqual([
      expect.objectContaining({ colaboradorId: AUTOR, papel: "ADMIN" }),
      expect.objectContaining({ colaboradorId: COLEGA, papel: "MEMBRO" }),
    ]);
    expect(conversa.versaoEntidade).toBeNull();
  });

  /**
   * Sem a espera, o servidor receberia a mensagem antes da conversa e a
   * recusaria por conversa inexistente — e a recusa travaria a fila inteira
   * atrás dela, que é o pior desfecho para quem apontou em campo.
   */
  it("faz a mensagem esperar a conversa que ainda não subiu", async () => {
    const conversa = await queueConversation({
      tipo: "GRUPO",
      titulo: "Frente da SP-310",
      obraId: null,
      equipeId: null,
      participantes: [{ colaboradorId: COLEGA, nome: "Colega" }],
    });

    await queueMessage({
      conversaId: conversa.id,
      corpo: "Chegamos ao km 172.",
      files: [],
    });

    const daMensagem = (await outbox()).find(
      (mutacao) => mutacao.operacao === "CRIAR_MENSAGEM",
    );
    expect(daMensagem?.dependsOnMutationIds).toContain(conversa.id);
  });

  /**
   * A conversa que ainda não subiu não está na resposta do servidor — ele não
   * a conhece. Apagá-la por isso destruiria a conversa, as mensagens escritas
   * nela e a própria fila que as levaria para cima, tudo em silêncio.
   */
  it("sobrevive à releitura autoritativa do servidor", async () => {
    const conversa = await queueConversation({
      tipo: "GRUPO",
      titulo: "Frente da SP-310",
      obraId: null,
      equipeId: null,
      participantes: [{ colaboradorId: COLEGA, nome: "Colega" }],
    });
    await queueMessage({
      conversaId: conversa.id,
      corpo: "Chegamos ao km 172.",
      files: [],
    });

    // O servidor responde a lista sem ela, porque ainda não a recebeu.
    await storeServerConversations([], { authoritative: true });

    expect((await listLocalConversations()).map((item) => item.id)).toEqual([
      conversa.id,
    ]);
    expect(await outbox()).toHaveLength(2);
  });

  /** As exigências de cada tipo continuam valendo sem o servidor por perto. */
  it("recusa o que o servidor recusaria", async () => {
    await expect(
      queueConversation({
        tipo: "DIRETA",
        titulo: null,
        obraId: null,
        equipeId: null,
        participantes: [],
      }),
    ).rejects.toThrow(/uma pessoa/i);
    await expect(
      queueConversation({
        tipo: "GRUPO",
        titulo: "   ",
        obraId: null,
        equipeId: null,
        participantes: [{ colaboradorId: COLEGA, nome: "Colega" }],
      }),
    ).rejects.toThrow(/nome do grupo/i);
    await expect(
      queueConversation({
        tipo: "OBRA",
        titulo: "Obra",
        obraId: null,
        equipeId: null,
        participantes: [],
      }),
    ).rejects.toThrow(/obra da conversa/i);
    expect(await outbox()).toHaveLength(0);
  });
});
