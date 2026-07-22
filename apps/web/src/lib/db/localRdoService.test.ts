import { describe, expect, it } from "vitest";

import { createEmptyRdo } from "../../features/rdos/createEmptyRdo";
import type { LocalRdoRecord, OutboxMutationRecord } from "./db.types";
import {
  buildRdoSyncPayload,
  buildRdoSyncPayloadFromLocalRecord,
  canCoalesceLegacyRdoMutation,
  rdoDraftFromLocalRecord,
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
  draft.maoObra[0] = {
    ...draft.maoObra[0],
    localId: "mao-obra-stable-1",
    colaboradorId: "colaborador-1",
    nomeColaborador: "Maria Operadora",
    cargo: "Operadora",
    origemItemId: "mao-obra-anterior-1",
  };
  draft.servicosExecutados[0] = {
    ...draft.servicosExecutados[0],
    servicoNome: "Aplicação de CBUQ",
    quantidadeExecutada: 0,
  };

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
  it("preserva proveniencia e identidade estavel da mao de obra no payload de producao", () => {
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
});

describe("RDO creation-context sync gate", () => {
  it("aceita contexto resolvido sem apontador marcado", async () => {
    const { rdoCreationContextBlockReason } = await import("./localRdoService");
    const draft = validDraft();
    draft.apontadorColaboradorId = "";

    expect(rdoCreationContextBlockReason(draft)).toBeNull();
  });
});

describe("legacy RDO mutation coalescing boundary", () => {
  it("coalesces legacy rows but never rewrites a canonical envelope", () => {
    expect(
      canCoalesceLegacyRdoMutation(legacyCreateMutation, "CRIAR_RDO"),
    ).toBe(true);
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
