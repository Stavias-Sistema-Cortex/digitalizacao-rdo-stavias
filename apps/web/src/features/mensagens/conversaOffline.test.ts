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
  hasOnlineSession: () => true,
  requireDataScope: () => ({ ownerId: AUTOR, scopeMaterial: "ALFA:GLOBAL" }),
}));

const {
  gravarPreferenciaDaConversa,
  listLocalConversations,
  listLocalMessages,
  queueConversation,
  queueMessage,
  storeServerConversations,
} = await import("./mensagensRepository");
const { getCortexDb } = await import("../../lib/db/cortexDb");
const {
  applyPushResultAtomically,
  markMutationAsSyncing,
  podarMutacoesJaAplicadas,
} = await import("../../lib/sync/syncStorage");
const { listReadyPendingOutboxMutations } = await import(
  "../../lib/db/outboxRepository"
);

async function outbox() {
  return (await getCortexDb()).getAll("outbox_mutations");
}

afterEach(async () => {
  // O nome do banco vem do escopo do sujeito, então apagar por nome fixo não
  // serve: limpa-se o conteúdo, que é o que um teste precisa isolar.
  const database = await getCortexDb();
  for (const store of [
    "mensagem_conversas",
    "mensagem_preferencias",
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

  it("reabre a direta arquivada como nova sem criar uma duplicata local", async () => {
    const arquivada = await queueConversation({
      tipo: "DIRETA",
      titulo: null,
      obraId: null,
      equipeId: null,
      participantes: [{ colaboradorId: COLEGA, nome: "Colega" }],
    });
    const database = await getCortexDb();
    await database.put("mensagem_conversas", {
      ...arquivada,
      versaoEntidade: 1,
    });
    await database.delete("outbox_mutations", arquivada.id);
    await gravarPreferenciaDaConversa(arquivada.id, {
      arquivadoEm: "2026-08-19T16:00:00.000Z",
      pendente: false,
    });
    await database.put("mensagens", {
      id: "30000000-0000-4000-8000-000000000001",
      conversaId: arquivada.id,
      autorId: COLEGA,
      autorNome: "Colega",
      corpo: "Histórico da conversa arquivada",
      status: "ATIVA",
      clientMutationId: "30000000-0000-4000-8000-000000000001",
      criadaNoClienteEm: "2026-08-19T15:00:00.000Z",
      criadaEm: "2026-08-19T15:00:00.000Z",
      editadaEm: null,
      deletadaEm: null,
      versaoEntidade: 1,
      syncStatus: "SINCRONIZADO",
      ultimoErro: null,
      updatedAt: "2026-08-19T15:00:00.000Z",
    });

    const reiniciada = await queueConversation({
      tipo: "DIRETA",
      titulo: null,
      obraId: null,
      equipeId: null,
      participantes: [{ colaboradorId: COLEGA, nome: "Colega" }],
    });

    expect(reiniciada.id).toBe(arquivada.id);
    expect(await database.getAll("mensagem_conversas")).toHaveLength(1);
    expect(
      (await outbox()).filter(
        (mutation) => mutation.operacao === "CRIAR_CONVERSA",
      ),
    ).toHaveLength(0);
    expect(
      await database.get("mensagem_preferencias", arquivada.id),
    ).toMatchObject({
      conversaId: arquivada.id,
      arquivadoEm: null,
      limpoAte: expect.any(String),
      arquivadoPendente: true,
      limpoPendente: true,
      pendente: true,
    });
    expect(await listLocalMessages(arquivada.id)).toEqual([]);
    expect(
      await database.get(
        "mensagens",
        "30000000-0000-4000-8000-000000000001",
      ),
    ).toMatchObject({ corpo: "Histórico da conversa arquivada" });
  });

  it("leva a intenção de começar do zero ao id canônico descoberto no sync", async () => {
    const conversa = await queueConversation({
      tipo: "DIRETA",
      titulo: null,
      obraId: null,
      equipeId: null,
      participantes: [{ colaboradorId: COLEGA, nome: "Colega" }],
    });
    const database = await getCortexDb();
    const conversaCanonicaId = "20000000-0000-4000-8000-000000000014";
    await database.put("mensagem_preferencias", {
      conversaId: conversaCanonicaId,
      arquivadoEm: "2026-08-19T16:00:00.000Z",
      limpoAte: null,
      arquivadoAtualizadoEm: "2026-08-19T16:00:00.000Z",
      limpoAtualizadoEm: null,
      arquivadoPendente: false,
      limpoPendente: false,
      pendente: false,
    });
    const criacao = await database.get("outbox_mutations", conversa.id);
    if (!criacao) throw new Error("Mutação de conversa ausente no teste.");
    await markMutationAsSyncing(criacao);

    await applyPushResultAtomically({
      clientMutationId: conversa.id,
      status: "APLICADA",
      entidadeTipo: "CONVERSA",
      entidadeId: conversaCanonicaId,
      resultado: {
        id: conversaCanonicaId,
        tipo: "DIRETA",
        titulo: null,
        obraId: null,
        equipeId: null,
        status: "ATIVA",
        participantes: conversa.participantes,
        criadaEm: conversa.criadaEm,
        atualizadaEm: conversa.atualizadaEm,
        versao: 1,
      },
    });

    expect(await database.get(
      "mensagem_preferencias",
      conversaCanonicaId,
    )).toMatchObject({
      arquivadoEm: null,
      limpoAte: conversa.criadaEm,
      arquivadoPendente: true,
      limpoPendente: true,
      pendente: true,
    });
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

  it("adota a conversa direta canônica sem deixar a mensagem no id provisório", async () => {
    const conversa = await queueConversation({
      tipo: "DIRETA",
      titulo: null,
      obraId: null,
      equipeId: null,
      participantes: [{ colaboradorId: COLEGA, nome: "Colega" }],
    });
    const mensagem = await queueMessage({
      conversaId: conversa.id,
      corpo: "Chegamos ao km 172.",
      files: [],
    });
    const mensagemComAnexo = await queueMessage({
      conversaId: conversa.id,
      corpo: "Evidência do km 172.",
      files: [
        new File(["evidência"], "evidencia.txt", { type: "text/plain" }),
      ],
    });
    await gravarPreferenciaDaConversa(conversa.id, {
      arquivadoEm: "2026-08-19T16:00:00.000Z",
      pendente: true,
    });
    const database = await getCortexDb();
    const criacao = await database.get("outbox_mutations", conversa.id);
    if (!criacao) throw new Error("Mutação de conversa ausente no teste.");
    await markMutationAsSyncing(criacao);

    const conversaCanonicaId = "20000000-0000-4000-8000-000000000003";
    const participantesCanonicos = conversa.participantes.map(
      (participante) => ({
        ...participante,
        papel:
          participante.colaboradorId === AUTOR
            ? "MEMBRO" as const
            : "ADMIN" as const,
      }),
    );
    await applyPushResultAtomically({
      clientMutationId: conversa.id,
      status: "APLICADA",
      entidadeTipo: "CONVERSA",
      entidadeId: conversaCanonicaId,
      resultado: {
        id: conversaCanonicaId,
        tipo: "DIRETA",
        titulo: null,
        obraId: null,
        equipeId: null,
        status: "ATIVA",
        participantes: participantesCanonicos,
        criadaEm: conversa.criadaEm,
        atualizadaEm: conversa.atualizadaEm,
        versao: 1,
      },
    });

    expect(await database.get("mensagem_conversas", conversa.id))
      .toBeUndefined();
    expect(await database.get("mensagem_conversas", conversaCanonicaId))
      .toMatchObject({
        id: conversaCanonicaId,
        versaoEntidade: 1,
        participantes: participantesCanonicos,
      });
    expect(await database.get("mensagens", mensagem.id)).toMatchObject({
      conversaId: conversaCanonicaId,
    });
    expect(await database.get("outbox_mutations", mensagem.id)).toMatchObject({
      payload: { conversaId: conversaCanonicaId },
      dependsOnMutationIds: expect.arrayContaining([conversa.id]),
    });
    expect(
      (await database.getAll("mensagem_anexos"))[0],
    ).toMatchObject({
      mensagemId: mensagemComAnexo.id,
      conversaId: conversaCanonicaId,
      arquivo: expect.any(Blob),
    });
    const upload = (await outbox()).find(
      (mutation) => mutation.transport === "OBJECT_UPLOAD",
    );
    expect(upload).toMatchObject({
      payload: { conversaId: conversaCanonicaId },
    });
    expect(await database.get("mensagem_preferencias", conversa.id))
      .toBeUndefined();
    expect(await database.get("mensagem_preferencias", conversaCanonicaId))
      .toMatchObject({
        conversaId: conversaCanonicaId,
        arquivadoEm: "2026-08-19T16:00:00.000Z",
        pendente: true,
      });
    expect(
      (await listReadyPendingOutboxMutations(100)).map(
        (mutation) => mutation.clientMutationId,
      ),
    ).toContain(mensagem.id);

    await storeServerConversations([
      {
        id: conversaCanonicaId,
        tipo: "DIRETA",
        titulo: null,
        obraId: null,
        equipeId: null,
        status: "ATIVA",
        participantes: participantesCanonicos,
        criadaEm: conversa.criadaEm,
        atualizadaEm: conversa.atualizadaEm,
        versao: 1,
      },
    ], { authorizedConversationIds: [conversaCanonicaId] });

    expect((await listLocalConversations()).map((item) => item.id)).toEqual([
      conversaCanonicaId,
    ]);
  });

  it("merges pending archive and history gestures field by field during alias adoption", async () => {
    const conversa = await queueConversation({
      tipo: "DIRETA",
      titulo: null,
      obraId: null,
      equipeId: null,
      participantes: [{ colaboradorId: COLEGA, nome: "Colega" }],
    });
    const conversaCanonicaId = "20000000-0000-4000-8000-000000000006";
    await gravarPreferenciaDaConversa(conversaCanonicaId, {
      limpoAte: "2026-08-19T15:00:00.000Z",
      pendente: true,
    });
    await gravarPreferenciaDaConversa(conversa.id, {
      arquivadoEm: "2026-08-19T15:05:00.000Z",
      pendente: true,
    });

    const database = await getCortexDb();
    const criacao = await database.get("outbox_mutations", conversa.id);
    if (!criacao) throw new Error("Mutação de conversa ausente no teste.");
    await markMutationAsSyncing(criacao);
    await applyPushResultAtomically({
      clientMutationId: conversa.id,
      status: "APLICADA",
      entidadeTipo: "CONVERSA",
      entidadeId: conversaCanonicaId,
      resultado: {
        id: conversaCanonicaId,
        tipo: "DIRETA",
        titulo: null,
        obraId: null,
        equipeId: null,
        status: "ATIVA",
        participantes: conversa.participantes,
        criadaEm: conversa.criadaEm,
        atualizadaEm: conversa.atualizadaEm,
        versao: 1,
      },
    });

    expect(await database.get(
      "mensagem_preferencias",
      conversaCanonicaId,
    )).toMatchObject({
      conversaId: conversaCanonicaId,
      arquivadoEm: "2026-08-19T15:05:00.000Z",
      limpoAte: "2026-08-19T15:00:00.000Z",
      arquivadoPendente: true,
      limpoPendente: true,
      pendente: true,
      arquivadoAtualizadoEm: expect.any(String),
      limpoAtualizadoEm: expect.any(String),
    });
  });

  it("keeps the newest pending gesture when both aliases changed one field", async () => {
    const conversa = await queueConversation({
      tipo: "DIRETA",
      titulo: null,
      obraId: null,
      equipeId: null,
      participantes: [{ colaboradorId: COLEGA, nome: "Colega" }],
    });
    const conversaCanonicaId = "20000000-0000-4000-8000-000000000007";
    await gravarPreferenciaDaConversa(conversaCanonicaId, {
      arquivadoEm: null,
      pendente: true,
    });
    await gravarPreferenciaDaConversa(conversa.id, {
      arquivadoEm: "2026-08-19T15:00:00.000Z",
      pendente: true,
    });

    const database = await getCortexDb();
    const canonical = await database.get(
      "mensagem_preferencias",
      conversaCanonicaId,
    );
    const provisional = await database.get(
      "mensagem_preferencias",
      conversa.id,
    );
    if (!canonical || !provisional) {
      throw new Error("Preferências de alias ausentes no teste.");
    }
    await database.put("mensagem_preferencias", {
      ...canonical,
      arquivadoAtualizadoEm: "2026-08-19T15:10:00.000Z",
    });
    await database.put("mensagem_preferencias", {
      ...provisional,
      arquivadoAtualizadoEm: "2026-08-19T15:00:00.000Z",
    });
    const criacao = await database.get("outbox_mutations", conversa.id);
    if (!criacao) throw new Error("Mutação de conversa ausente no teste.");
    await markMutationAsSyncing(criacao);
    await applyPushResultAtomically({
      clientMutationId: conversa.id,
      status: "APLICADA",
      entidadeTipo: "CONVERSA",
      entidadeId: conversaCanonicaId,
      resultado: {
        id: conversaCanonicaId,
        tipo: "DIRETA",
        titulo: null,
        obraId: null,
        equipeId: null,
        status: "ATIVA",
        participantes: conversa.participantes,
        criadaEm: conversa.criadaEm,
        atualizadaEm: conversa.atualizadaEm,
        versao: 1,
      },
    });

    expect(await database.get(
      "mensagem_preferencias",
      conversaCanonicaId,
    )).toMatchObject({
      arquivadoEm: null,
      arquivadoAtualizadoEm: "2026-08-19T15:10:00.000Z",
      pendente: true,
    });
  });

  it("mantém o alias depois da poda e resolve escritas que chegam atrasadas", async () => {
    const conversa = await queueConversation({
      tipo: "DIRETA",
      titulo: null,
      obraId: null,
      equipeId: null,
      participantes: [{ colaboradorId: COLEGA, nome: "Colega" }],
    });
    const database = await getCortexDb();
    const criacao = await database.get("outbox_mutations", conversa.id);
    if (!criacao) throw new Error("Mutação de conversa ausente no teste.");
    const staleListStartedAt = "2000-01-01T00:00:00.000Z";
    await markMutationAsSyncing(criacao);

    const conversaCanonicaId = "20000000-0000-4000-8000-000000000004";
    await applyPushResultAtomically({
      clientMutationId: conversa.id,
      status: "APLICADA",
      entidadeTipo: "CONVERSA",
      entidadeId: conversaCanonicaId,
      resultado: {
        id: conversaCanonicaId,
        tipo: "DIRETA",
        titulo: null,
        obraId: null,
        equipeId: null,
        status: "ATIVA",
        participantes: conversa.participantes,
        criadaEm: conversa.criadaEm,
        atualizadaEm: conversa.atualizadaEm,
        versao: 1,
      },
    });

    expect(await podarMutacoesJaAplicadas()).toBe(0);
    expect(await database.get("outbox_mutations", conversa.id)).toMatchObject({
      status: "SYNCED",
      resultadoServidor: { id: conversaCanonicaId },
    });

    const mensagemAtrasada = await queueMessage({
      conversaId: conversa.id,
      corpo: "Escrita iniciada antes do retorno.",
      files: [],
    });
    await gravarPreferenciaDaConversa(conversa.id, {
      limpoAte: "2026-08-19T16:30:00.000Z",
      pendente: true,
    });

    expect(await database.get("mensagens", mensagemAtrasada.id)).toMatchObject({
      conversaId: conversaCanonicaId,
    });
    expect(await database.get(
      "outbox_mutations",
      mensagemAtrasada.id,
    )).toMatchObject({
      payload: { conversaId: conversaCanonicaId },
      dependsOnMutationIds: [],
    });
    expect(await database.get(
      "mensagem_preferencias",
      conversaCanonicaId,
    )).toMatchObject({
      conversaId: conversaCanonicaId,
      limpoAte: "2026-08-19T16:30:00.000Z",
    });
    expect(await database.get("mensagem_preferencias", conversa.id))
      .toBeUndefined();

    // Uma listagem iniciada antes do push pode chegar vazia depois do remap.
    // O recibo de alias deve impedir que ela apague a conversa canônica.
    await storeServerConversations([], {
      authorizedConversationIds: [],
      requestStartedAt: staleListStartedAt,
    });

    expect(await database.get(
      "mensagem_conversas",
      conversaCanonicaId,
    )).toBeDefined();
    expect(await database.get("mensagens", mensagemAtrasada.id)).toBeDefined();
  });

  it("remove o alias quando uma listagem autoritativa atual revoga o acesso", async () => {
    const conversa = await queueConversation({
      tipo: "DIRETA",
      titulo: null,
      obraId: null,
      equipeId: null,
      participantes: [{ colaboradorId: COLEGA, nome: "Colega" }],
    });
    const database = await getCortexDb();
    const criacao = await database.get("outbox_mutations", conversa.id);
    if (!criacao) throw new Error("Mutação de conversa ausente no teste.");
    await markMutationAsSyncing(criacao);

    const conversaCanonicaId = "20000000-0000-4000-8000-000000000005";
    await applyPushResultAtomically({
      clientMutationId: conversa.id,
      status: "APLICADA",
      entidadeTipo: "CONVERSA",
      entidadeId: conversaCanonicaId,
      resultado: {
        id: conversaCanonicaId,
        tipo: "DIRETA",
        titulo: null,
        obraId: null,
        equipeId: null,
        status: "ATIVA",
        participantes: conversa.participantes,
        criadaEm: conversa.criadaEm,
        atualizadaEm: conversa.atualizadaEm,
        versao: 1,
      },
    });

    await storeServerConversations([], {
      authorizedConversationIds: [],
      requestStartedAt: "2999-01-01T00:00:00.000Z",
    });

    expect(await database.get(
      "mensagem_conversas",
      conversaCanonicaId,
    )).toBeUndefined();
  });

  it("recusa identidades de conversa divergentes sem remapear dados locais", async () => {
    const conversa = await queueConversation({
      tipo: "DIRETA",
      titulo: null,
      obraId: null,
      equipeId: null,
      participantes: [{ colaboradorId: COLEGA, nome: "Colega" }],
    });
    const database = await getCortexDb();
    const criacao = await database.get("outbox_mutations", conversa.id);
    if (!criacao) throw new Error("Mutação de conversa ausente no teste.");
    await markMutationAsSyncing(criacao);

    await expect(applyPushResultAtomically({
      clientMutationId: conversa.id,
      status: "APLICADA",
      entidadeTipo: "CONVERSA",
      entidadeId: "20000000-0000-4000-8000-000000000003",
      resultado: {
        id: "30000000-0000-4000-8000-000000000003",
      },
    })).rejects.toThrow(/identidades divergentes/i);

    expect(await database.get("mensagem_conversas", conversa.id))
      .toMatchObject({ id: conversa.id });
    expect(await database.get(
      "mensagem_conversas",
      "20000000-0000-4000-8000-000000000003",
    )).toBeUndefined();
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
    await storeServerConversations([], { authorizedConversationIds: [] });

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
