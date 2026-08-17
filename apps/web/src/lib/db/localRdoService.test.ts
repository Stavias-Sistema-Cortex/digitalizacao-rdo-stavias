import { describe, expect, it } from "vitest";

import {
  createEmptyAlocacaoColaborador,
  createEmptyControleGeometrico,
  createEmptyEquipamento,
  createEmptyMaoObra,
  createEmptyMaterial,
  createEmptyRdo,
  createEmptyServicoExecutado,
} from "../../features/rdos/createEmptyRdo";
import type { LocalRdoRecord, OutboxMutationRecord } from "./db.types";
import {
  buildRdoSyncPayload,
  buildRdoSyncPayloadFromLocalRecord,
  canCoalesceLegacyRdoMutation,
  rdoDraftFromLocalRecord,
  servicoExecutadoNeedsCatalogSelection,
  validateRdoDraftForSync,
} from "./localRdoService";

const legacyCreateMutation = {
  clientMutationId: "legacy-1",
  entidadeTipo: "RDO",
  entidadeId: "rdo-1",
  operacao: "CRIAR_RDO",
  baseVersao: null,
  payload: {},
  status: "PENDING",
  tentativas: 0,
  ultimaTentativaEm: null,
  ultimoErro: null,
  conflito: null,
  criadaNoClienteEm: "2026-07-21T12:00:00.000Z",
  updatedAt: "2026-07-21T12:00:00.000Z",
} as OutboxMutationRecord;

function validDraft() {
  const draft = createEmptyRdo();

  draft.id = "rdo-local-1";
  draft.obraId = "obra-1";
  draft.numeroRdo = "RDO-001";
  draft.dataRdo = "2026-07-03";
  draft.previousRdoId = "rdo-anterior-1";
  draft.creationContextVersion = 48;
  draft.apontadorColaboradorId = "colaborador-1";
  draft.maoObra = [{
    ...createEmptyMaoObra(),
    localId: "mao-obra-stable-1",
    colaboradorId: "colaborador-1",
    nomeColaborador: "Maria Operadora",
    cargo: "Operadora",
    origemItemId: "mao-obra-anterior-1",
  }];
  draft.servicosExecutados = [{
    ...createEmptyServicoExecutado(),
    localId: "servico-stable-1",
    serviceId: "service-catalog-1",
    priceVersionId: "price-version-7",
    servicoNome: "Aplicação de CBUQ",
    quantidadeExecutada: 0,
  }];
  draft.equipamentos = [{
    ...createEmptyEquipamento(),
    localId: "equipamento-stable-1",
    descricao: "Vibroacabadora",
  }];
  draft.materiais = [{
    ...createEmptyMaterial(),
    localId: "material-stable-1",
    materialNome: "CBUQ",
  }];
  draft.controlesGeometricos = [{
    ...createEmptyControleGeometrico(),
    localId: "controle-stable-1",
    subtrecho: "km 10 ao 11",
  }];
  draft.alocacoesColaboradores = [{
    ...createEmptyAlocacaoColaborador(),
    localId: "alocacao-stable-1",
    colaboradorId: "colaborador-1",
  }];

  return draft;
}

describe("validateRdoDraftForSync", () => {
  it("bloqueia quantidade executada negativa antes de criar mutação offline", () => {
    const draft = validDraft();
    draft.servicosExecutados[0].quantidadeExecutada = -1;

    expect(() => validateRdoDraftForSync(draft)).toThrow(
      "A quantidade executada do serviço 1 deve ser maior ou igual a zero.",
    );
  });

  it("aceita quantidade executada zero", () => {
    expect(() =>
      validateRdoDraftForSync(validDraft()),
    ).not.toThrow();
  });
});

describe("buildRdoSyncPayload V48 boundary", () => {
  it("preserva proveniencia e identidade estavel de todos os filhos no payload", () => {
    const payload = buildRdoSyncPayload(validDraft());

    expect(payload).toMatchObject({
      previousRdoId: "rdo-anterior-1",
      creationContextVersion: 48,
      apontadorColaboradorId: "colaborador-1",
      maoObra: [
        {
          id: "mao-obra-stable-1",
          colaboradorId: "colaborador-1",
          origemItemId: "mao-obra-anterior-1",
        },
      ],
      equipamentos: [{ id: "equipamento-stable-1" }],
      materiais: [{ id: "material-stable-1" }],
      controlesGeometricos: [{ id: "controle-stable-1" }],
      servicosExecutados: [{
        id: "servico-stable-1",
        serviceId: "service-catalog-1",
        priceVersionId: "price-version-7",
      }],
      alocacoesColaboradores: [{ id: "alocacao-stable-1" }],
    });
    expect(payload.maoObra).not.toEqual(
      expect.arrayContaining([
        expect.objectContaining({ localId: expect.anything() }),
      ]),
    );
  });

  it("reconstrói IDs estáveis tanto de payload local quanto de resposta canônica", () => {
    const draft = validDraft();
    const rdo: LocalRdoRecord = {
      id: draft.id,
      obraId: draft.obraId,
      programacaoId: null,
      numeroRdo: draft.numeroRdo,
      dataRdo: draft.dataRdo,
      statusRdo: "RASCUNHO",
      syncStatus: "PENDING_SYNC",
      versaoEntidade: null,
      createdAt: "2026-07-03T12:00:00.000Z",
      updatedAt: "2026-07-03T12:00:00.000Z",
      payload: {
        ...draft,
        maoObra: [{
          ...draft.maoObra[0],
          localId: undefined,
          id: "mao-obra-server-1",
        }],
      },
    };

    expect(buildRdoSyncPayloadFromLocalRecord(rdo)).toMatchObject({
      previousRdoId: "rdo-anterior-1",
      creationContextVersion: 48,
      apontadorColaboradorId: "colaborador-1",
      maoObra: [{
        id: "mao-obra-server-1",
        origemItemId: "mao-obra-anterior-1",
      }],
    });
  });

  it("preserva o serviço nomeado sem catálogo para que a fila o bloqueie", () => {
    const draft = validDraft();
    draft.servicosExecutados = [{
      ...createEmptyServicoExecutado(),
      localId: "servico-sem-catalogo",
      servicoNome: "Fresagem",
      serviceId: "",
    }];

    expect(
      servicoExecutadoNeedsCatalogSelection(draft.servicosExecutados[0]),
    ).toBe(true);
    expect(buildRdoSyncPayload(draft)).toMatchObject({
      servicosExecutados: [{
        id: "servico-sem-catalogo",
        servicoNome: "Fresagem",
        serviceId: null,
      }],
    });
  });

  it("preserva o serviço catalogado sem unidade para que a fila o bloqueie", () => {
    const draft = validDraft();
    draft.servicosExecutados = [{
      ...createEmptyServicoExecutado(),
      localId: "servico-sem-unidade",
      servicoNome: "Fresagem",
      serviceId: "service-catalog-fresagem",
      unidade: "",
    }];

    expect(
      servicoExecutadoNeedsCatalogSelection(draft.servicosExecutados[0]),
    ).toBe(true);
    expect(buildRdoSyncPayload(draft)).toMatchObject({
      servicosExecutados: [{
        id: "servico-sem-unidade",
        serviceId: "service-catalog-fresagem",
        unidade: null,
      }],
    });
  });

  /*
   * A linha que a duplicação cria: traz o trecho e as medidas, e o serviço fica
   * em branco de propósito porque trocá-lo é o motivo de duplicar.
   *
   * Sem contá-la como linha começada, ela se passava por vazia — não pedia o
   * catálogo, não segurava o envio, subia, e o servidor a descartava em
   * silêncio, porque ele também a lê como vazia. Na releitura seguinte ela
   * sumia do aparelho levando o trecho que alguém tinha acabado de copiar.
   */
  it.each([
    ["trechoInicial", { trechoInicial: "12+300" }],
    ["trechoFinal", { trechoFinal: "12+400" }],
    ["pista", { pista: "Sul" }],
    ["faixa", { faixa: "2" }],
    ["larguraM", { larguraM: 7 as const }],
    ["espessuraM", { espessuraM: 0.09 as const }],
  ])(
    "segura a linha que já disse %s e ainda não tem serviço",
    (_campo, lugar) => {
      expect(
        servicoExecutadoNeedsCatalogSelection({
          ...createEmptyServicoExecutado(),
          ...lugar,
        }),
      ).toBe(true);
    },
  );

  /*
   * E a linha em que ninguém escreveu nada continua passando: recusá-la
   * custaria o RDO inteiro, e não há nada a perder nela.
   */
  it("deixa passar a linha em que ninguém escreveu nada", () => {
    expect(
      servicoExecutadoNeedsCatalogSelection(createEmptyServicoExecutado()),
    ).toBe(false);
  });

  /*
   * Duas frentes sobre o mesmo trecho é o caso normal de uma caixa de rodovia,
   * e é o que a duplicação produz. O `id` que sobe é o localId da linha, o
   * servidor exige que ele seja UUID e recusa o lote inteiro se dois itens
   * repetirem o mesmo — uma recusa terminal, que a fila não reenvia. É a
   * garantia de que a cópia precisa de identidade própria, e não só de uma
   * posição diferente na lista.
   */
  it("sobe as duas frentes do mesmo trecho com identidade própria", () => {
    const draft = validDraft();
    const base = {
      ...createEmptyServicoExecutado(),
      trechoInicial: "12+300",
      trechoFinal: "12+400",
      unidade: "m3",
      quantidadeExecutada: 63,
    };
    draft.servicosExecutados = [
      {
        ...base,
        localId: "6f1a0d4e-2b3c-4d5e-8f90-a1b2c3d4e5f6",
        serviceId: "service-catalog-fresagem",
        servicoNome: "Fresagem",
      },
      {
        ...base,
        localId: "7a2b1e5f-3c4d-4e6f-9a01-b2c3d4e5f607",
        serviceId: "service-catalog-cbuq",
        servicoNome: "CBUQ",
      },
    ];

    const payload = buildRdoSyncPayload(draft) as {
      servicosExecutados: { id: string; trechoInicial: string }[];
    };
    const ids = payload.servicosExecutados.map((item) => item.id);

    expect(new Set(ids).size).toBe(2);
    for (const id of ids) {
      expect(id).toMatch(
        /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/,
      );
    }
    expect(
      payload.servicosExecutados.map((item) => item.trechoInicial),
    ).toEqual(["12+300", "12+300"]);
  });
});

describe("RDO creation-context sync gate", () => {
  it("aceita contexto resolvido sem apontador marcado", async () => {
    const { rdoCreationContextBlockReason } = await import("./localRdoService");
    const draft = validDraft();
    draft.apontadorColaboradorId = "";

    expect(rdoCreationContextBlockReason(draft)).toBeNull();
  });

  /*
   * A criação continua exigindo o recibo — é dela que ele fala. O que saiu foi
   * a exigência na atualização, que guardava um dado descartado: em
   * `RdoSyncOperationHandler.updateDraft` o servidor sobrescreve
   * `creationContextVersion` com o valor já persistido antes de montar a
   * requisição, então o que o envelope carrega ali nunca chega a ser lido.
   */
  it("cobra o recibo na criação, e só na criação", async () => {
    const localRdoService = await import("./localRdoService");
    const draft = validDraft();
    draft.creationContextVersion = null;

    expect(localRdoService.rdoCreationContextBlockReason(draft)).toBe(
      "RDO_CREATION_CONTEXT_REQUIRED",
    );
    /*
     * A trava contra o retorno da regra: sobrando uma única porta que cobre o
     * recibo, ela é a da criação. Uma segunda voltaria a prender edição.
     */
    expect(
      Object.keys(localRdoService).filter((nome) =>
        nome.toLowerCase().includes("blockreason")
      ),
    ).toEqual(["rdoCreationContextBlockReason"]);
  });
});

describe("legacy RDO mutation coalescing boundary", () => {
  it("coalesces only retryable legacy rows and never rewrites in-flight or canonical envelopes", () => {
    expect(
      canCoalesceLegacyRdoMutation(legacyCreateMutation, "CRIAR_RDO"),
    ).toBe(true);
    expect(
      canCoalesceLegacyRdoMutation(
        { ...legacyCreateMutation, status: "ERROR" },
        "CRIAR_RDO",
      ),
    ).toBe(false);
    expect(
      canCoalesceLegacyRdoMutation(
        {
          ...legacyCreateMutation,
          tentativas: 1,
          ultimaTentativaEm: "2026-07-27T14:28:20.000Z",
        },
        "CRIAR_RDO",
      ),
    ).toBe(false);
    expect(
      canCoalesceLegacyRdoMutation(
        {
          ...legacyCreateMutation,
          status: "ERROR",
          blockedReason:
            "NON_APPLIED_SUPERSEDED_BY:00000000-0000-4000-8000-000000000099",
        },
        "CRIAR_RDO",
      ),
    ).toBe(false);
    expect(
      canCoalesceLegacyRdoMutation(
        { ...legacyCreateMutation, status: "SYNCING" },
        "CRIAR_RDO",
      ),
    ).toBe(false);
    expect(
      canCoalesceLegacyRdoMutation(
        { ...legacyCreateMutation, status: "CONFLICT" },
        "CRIAR_RDO",
      ),
    ).toBe(false);
    expect(
      canCoalesceLegacyRdoMutation(
        { ...legacyCreateMutation, schemaVersion: 13 } as OutboxMutationRecord,
        "CRIAR_RDO",
      ),
    ).toBe(false);
  });
});

describe("rdoDraftFromLocalRecord", () => {
  it("normaliza payload local historico com campos nulos antes de reconstruir a mutacao", () => {
    const rdo: LocalRdoRecord = {
      id: "rdo-local-legacy",
      obraId: "obra-atual",
      programacaoId: null,
      numeroRdo: "RDO-LEG-001",
      dataRdo: "2026-07-08",
      statusRdo: "RASCUNHO",
      syncStatus: "ERROR",
      versaoEntidade: null,
      createdAt: "2026-07-08T12:00:00.000Z",
      updatedAt: "2026-07-08T12:05:00.000Z",
      payload: {
        id: "id-antigo-no-payload",
        obraId: "obra-antiga-no-payload",
        programacaoId: null,
        numeroRdo: "payload-velho",
        dataRdo: "2026-01-01",
        cliente: "Intervias",
        contrato: "INTERVIAS-2-PCT",
        rodovia: null,
        cidade: null,
        uf: null,
        kmInicialProgramado: null,
        kmFinalProgramado: null,
        kmInicialInterditado: null,
        kmFinalInterditado: null,
        preenchidoPor: null,
        apontadorRdo: null,
        encarregadoObra: null,
        fiscalizacaoCampo: null,
        servicosExecutados: [
          {
            localId: "servico-1",
            servicoNome: "Aplicacao de CBUQ",
            quantidadeExecutada: 0,
            itemContratualId: null,
            unidade: null,
            trechoInicial: null,
            trechoFinal: null,
            localizacao: null,
            turno: null,
            observacoes: null,
          },
        ],
        alocacoesColaboradores: [
          {
            localId: "alocacao-1",
            colaboradorId: null,
            equipe: null,
            servicoNome: null,
            funcao: null,
            centroCusto: null,
            fonte: null,
            observacoes: null,
          },
        ],
        maoObra: [
          {
            localId: "mao-obra-1",
            colaboradorId: null,
            nomeColaborador: "Operador",
            cargo: null,
            horaInicio: null,
            horaFim: null,
            observacoes: null,
          },
        ],
        equipamentos: [
          {
            localId: "equipamento-1",
            assetId: null,
            prefixo: null,
            descricao: "Rolo compactador",
            tipoEquipamento: null,
            horaInicio: null,
            horaFim: null,
            observacoes: null,
          },
        ],
        materiais: [
          {
            localId: "material-1",
            materialNome: null,
            unidade: null,
            quantidadePrevista: null,
            quantidadeUsinada: null,
            quantidadeAplicada: null,
            quantidadeSobra: null,
            notaFiscal: null,
            fornecedor: null,
            observacoes: null,
          },
        ],
        controlesGeometricos: [
          {
            localId: "controle-1",
            subtrecho: null,
            numero: null,
            kmInicial: null,
            kmFinal: null,
            observacoes: null,
          },
        ],
        attachments: [],
      },
    };

    const draft = rdoDraftFromLocalRecord(rdo);

    expect(draft).toMatchObject({
      id: "rdo-local-legacy",
      obraId: "obra-atual",
      programacaoId: "",
      numeroRdo: "RDO-LEG-001",
      dataRdo: "2026-07-08",
      rodovia: "",
      cidade: "",
      uf: "",
      syncStatus: "ERROR",
    });
    expect(draft.maoObra[0]).toMatchObject({
      localId: "mao-obra-1",
      colaboradorId: "",
      nomeColaborador: "Operador",
      cargo: "",
    });
    expect(draft.equipamentos[0]).toMatchObject({
      localId: "equipamento-1",
      assetId: "",
      descricao: "Rolo compactador",
    });

    expect(() =>
      buildRdoSyncPayloadFromLocalRecord(rdo),
    ).not.toThrow();
  });
});
