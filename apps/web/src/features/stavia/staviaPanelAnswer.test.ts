import { describe, expect, it } from "vitest";

import {
  answerStaviaPanelQuestion,
  buildStaviaPanelLocalContext,
} from "./staviaPanelAnswer";
import {
  loadRdoOntology,
  type RdoOntologyEntityJson,
  type RdoOntologyJson,
} from "./staviaRdoOntology";
import type {
  StaviaConsultaRequest,
  StaviaConsultaResponse,
  StaviaSnapshot,
  StaviaSnapshotOperationalEvent,
  StaviaSnapshotRdo,
} from "./stavia.types";

function makeRdo(
  partial: Partial<StaviaSnapshotRdo> & { id: string },
): StaviaSnapshotRdo {
  return {
    id: partial.id,
    obraId: "obra-1",
    programacaoId: null,
    numeroRdo: null,
    dataRdo: null,
    diaSemana: null,
    cliente: null,
    cidade: null,
    contrato: null,
    rodovia: null,
    uf: null,
    kmInicialProgramado: null,
    kmFinalProgramado: null,
    kmInicialInterditado: null,
    kmFinalInterditado: null,
    turno: null,
    horaInicio: null,
    horaFim: null,
    condicaoManha: null,
    condicaoTarde: null,
    condicaoNoite: null,
    pluviometriaMm: null,
    status: "APROVADO",
    fonteCriacao: null,
    estadoReceita: null,
    fonteArquivo: null,
    abaOrigem: null,
    linhaOrigem: null,
    dataOriginal: null,
    dataImportacao: null,
    usuarioImportacao: null,
    criadoEm: null,
    enviadoEm: null,
    aprovadoEm: null,
    versaoLinha: null,
    syncStatus: "SYNCED",
    observacoes: null,
    preenchidoPor: null,
    apontadorRdo: null,
    encarregadoObra: null,
    fiscalizacaoCampo: null,
    updatedAt: null,
    servicosExecutados: [],
    maoObra: [],
    equipamentos: [],
    materiais: [],
    controlesGeometricos: [],
    alocacoesColaboradores: [],
    attachments: [],
    ...partial,
  };
}

function snapshotWithRdoConflict(): StaviaSnapshot {
  const activeRdo = makeRdo({
    id: "rdo-ativo",
    numeroRdo: "123",
    dataRdo: "2026-07-01",
    controlesGeometricos: [
      {
        subtrecho: "SP-215",
        numero: "1",
        estacaInicial: null,
        estacaFinal: null,
        kmInicial: null,
        kmFinal: null,
        pista: null,
        faixa: null,
        ordemServico: null,
        comprimentoM: "120",
        larguraM: null,
        espessura1Cm: null,
        espessura2Cm: null,
        espessura3Cm: null,
        espessuraMediaCm: null,
        areaM2: null,
        volumeM3: null,
        densidade: null,
        massaTonelada: null,
        atividadeObservacoes: null,
        observacoes: null,
      },
      {
        subtrecho: "SP-215",
        numero: "2",
        estacaInicial: null,
        estacaFinal: null,
        kmInicial: null,
        kmFinal: null,
        pista: "Leste",
        faixa: null,
        ordemServico: null,
        comprimentoM: "282",
        larguraM: null,
        espessura1Cm: null,
        espessura2Cm: null,
        espessura3Cm: null,
        espessuraMediaCm: null,
        areaM2: null,
        volumeM3: null,
        densidade: null,
        massaTonelada: null,
        atividadeObservacoes: null,
        observacoes: null,
      },
    ],
  });
  const newerWrongRdo = makeRdo({
    id: "rdo-mais-recente",
    numeroRdo: "124",
    dataRdo: "2026-07-02",
    controlesGeometricos: [
      {
        subtrecho: "SP-215",
        numero: "2",
        estacaInicial: null,
        estacaFinal: null,
        kmInicial: null,
        kmFinal: null,
        pista: "Oeste",
        faixa: null,
        ordemServico: null,
        comprimentoM: "999",
        larguraM: null,
        espessura1Cm: null,
        espessura2Cm: null,
        espessura3Cm: null,
        espessuraMediaCm: null,
        areaM2: null,
        volumeM3: null,
        densidade: null,
        massaTonelada: null,
        atividadeObservacoes: null,
        observacoes: null,
      },
    ],
  });

  return {
    metadata: {
      snapshotKey: "default",
      generatedAt: "2026-07-02T12:00:00",
      databaseUpdatedAt: null,
      localSyncedAt: null,
      source: "TEST",
      status: "LOCAL",
      dictionaryVersion: "test",
    },
    obras: [],
    rdos: [newerWrongRdo, activeRdo],
    programacoes: [],
    pdors: [],
    operationalEvents: [],
  };
}

function snapshotWithActiveLength(length: string): StaviaSnapshot {
  const snapshot = snapshotWithRdoConflict();
  const activeRdo = snapshot.rdos.find((rdo) => rdo.id === "rdo-ativo");
  const trecho2 = activeRdo?.controlesGeometricos[1];

  if (!trecho2) {
    throw new Error("Fixture sem trecho 2 ativo.");
  }

  trecho2.comprimentoM = length;
  return snapshot;
}

function fakeApiResponse(answer: string): StaviaConsultaResponse {
  return {
    answer: {
      answer,
      confidence: "ALTA",
      answerType: "FATO",
      sources: [],
      insufficientData: false,
      warnings: [],
      metadata: null,
    },
    intent: "TESTE",
    consultedKnowledgeSources: {},
    knowledgeWarnings: [],
  };
}

describe("staviaPanelAnswer", () => {
  it("usa o RDO ativo do painel no snapshot local antes de chamar API", async () => {
    let apiCalls = 0;

    const response = await answerStaviaPanelQuestion({
      snapshot: snapshotWithRdoConflict(),
      questionText: "Qual o comprimento do trecho 2?",
      contextHint: "",
      activeObraId: "obra-1",
      activeRdoId: "rdo-ativo",
      lastContext: { obraId: null, rdoId: null },
      isOnline: true,
      consultar: async () => {
        apiCalls += 1;
        return fakeApiResponse("Resposta da API não deveria ser usada.");
      },
    });

    expect(apiCalls).toBe(0);
    expect(response.answer.metadata?.caminho).toBe("ONTOLOGIA_RDO");
    expect(response.answer.answer).toBe(
      "Comprimento de Trecho 2 (SP-215): 282 m (RDO 123 de 01/07/2026).",
    );
    expect(response.answer.answer).not.toContain("999");
    expect(response.answer.answer).not.toContain("Oeste");
  });

  it("prefere o snapshot local recomposto mais recente ao snapshot em memória", async () => {
    let apiCalls = 0;

    const response = await answerStaviaPanelQuestion({
      snapshot: snapshotWithActiveLength("111"),
      questionText: "Qual o comprimento do trecho 2?",
      contextHint: "",
      activeObraId: "obra-1",
      activeRdoId: "rdo-ativo",
      lastContext: { obraId: null, rdoId: null },
      isOnline: true,
      loadSnapshot: async () => snapshotWithActiveLength("282"),
      consultar: async () => {
        apiCalls += 1;
        return fakeApiResponse("Resposta da API não deveria ser usada.");
      },
    });

    expect(apiCalls).toBe(0);
    expect(response.answer.answer).toBe(
      "Comprimento de Trecho 2 (SP-215): 282 m (RDO 123 de 01/07/2026).",
    );
    expect(response.answer.answer).not.toContain("111");
  });

  it("mantém pergunta curta de data no RDO ativo do painel", async () => {
    let apiCalls = 0;

    const response = await answerStaviaPanelQuestion({
      snapshot: snapshotWithRdoConflict(),
      questionText: "Qual a data?",
      contextHint: "",
      activeObraId: "obra-1",
      activeRdoId: "rdo-ativo",
      lastContext: {
        obraId: "obra-antiga",
        rdoId: "rdo-antigo",
      },
      isOnline: true,
      consultar: async () => {
        apiCalls += 1;
        return fakeApiResponse("Resposta da API não deveria ser usada.");
      },
    });

    expect(apiCalls).toBe(0);
    expect(response.answer.answer).toContain("01/07/2026");
    expect(response.answer.answer).not.toContain("02/07/2026");
  });

  it("repassa contexto ativo e último contexto para a API quando o snapshot não responde", async () => {
    let request: StaviaConsultaRequest | null = null;

    await answerStaviaPanelQuestion({
      snapshot: null,
      questionText: "Pergunta externa ao snapshot",
      contextHint: "Obra X · RDO 123",
      activeObraId: " obra-ativa ",
      activeRdoId: " rdo-ativo ",
      lastContext: {
        obraId: "obra-anterior",
        rdoId: "rdo-anterior",
      },
      isOnline: true,
      loadSnapshot: async () => null,
      consultar: async (apiRequest) => {
        request = apiRequest;
        return fakeApiResponse("Resposta da API.");
      },
    });

    expect(request).toMatchObject({
      pergunta: "Pergunta externa ao snapshot",
      usuarioId: "frontend-local",
      obraId: "obra-ativa",
      rdoId: "rdo-ativo",
      contextoSelecionado: "Obra X · RDO 123",
      ultimoObraId: "obra-ativa",
      ultimoRdoId: "rdo-ativo",
    });
  });

  it("preserva último contexto persistido no fallback quando não há seleção ativa", async () => {
    let request: StaviaConsultaRequest | null = null;

    await answerStaviaPanelQuestion({
      snapshot: null,
      questionText: "Qual a data?",
      contextHint: "",
      activeObraId: " ",
      activeRdoId: " ",
      lastContext: {
        obraId: "obra-anterior",
        rdoId: "rdo-anterior",
      },
      isOnline: true,
      loadSnapshot: async () => null,
      consultar: async (apiRequest) => {
        request = apiRequest;
        return fakeApiResponse("Resposta da API.");
      },
    });

    expect(request).toMatchObject({
      obraId: null,
      rdoId: null,
      ultimoObraId: "obra-anterior",
      ultimoRdoId: "rdo-anterior",
    });
  });

  it("normaliza contexto local do painel para IDs vazios virarem nulos", () => {
    expect(
      buildStaviaPanelLocalContext({
        activeObraId: " ",
        activeRdoId: " rdo-1 ",
        lastContext: {
          obraId: "obra-anterior",
          rdoId: null,
        },
      }),
    ).toEqual({
      activeObraId: null,
      activeRdoId: "rdo-1",
      lastObraId: "obra-anterior",
      lastRdoId: "rdo-1",
    });
  });

  it("responde todas as células declaradas pelo caminho usado no painel", async () => {
    const snapshot = makePanelCoverageSnapshot();
    const ontology = loadRdoOntology(snapshot);
    let checkedCells = 0;
    let apiCalls = 0;

    for (const entity of ontology.entities) {
      for (const attribute of entity.attributes) {
        checkedCells += 1;
        const pergunta = panelCoverageQuestion(entity, attribute.label);
        const response = await answerStaviaPanelQuestion({
          snapshot,
          questionText: pergunta,
          contextHint: "Obra Cobertura · RDO 123",
          activeObraId: "obra-cobertura",
          activeRdoId: "rdo-cobertura",
          lastContext: { obraId: null, rdoId: null },
          isOnline: false,
          loadSnapshot: async () => snapshot,
          consultar: async () => {
            apiCalls += 1;
            return fakeApiResponse("Resposta da API não deveria ser usada.");
          },
        });
        const expectedValue = panelCoverageValue(
          snapshot,
          entity.name,
          attribute.name,
        );

        expect(
          response.answer.insufficientData,
          `dado insuficiente para ${entity.name}.${attribute.name}: `
            + response.answer.answer,
        ).toBe(false);
        expect(
          response.answer.metadata?.caminho,
          `não usou ontologia para ${entity.name}.${attribute.name}: `
            + response.answer.answer,
        ).toBe("ONTOLOGIA_RDO");
        expect(
          response.answer.answer,
          `fallback inesperado para ${entity.name}.${attribute.name}`,
        ).not.toContain("Não encontrei");
        expect(
          response.answer.answer,
          `valor ausente para ${entity.name}.${attribute.name}`,
        ).toContain(expectedValue);
      }
    }

    expect(apiCalls).toBe(0);
    expect(checkedCells).toBeGreaterThan(150);
  });
});

function makePanelCoverageSnapshot(): StaviaSnapshot {
  const snapshot: StaviaSnapshot = {
    metadata: {
      snapshotKey: "default",
      generatedAt: "2026-07-03T12:00:00",
      databaseUpdatedAt: null,
      localSyncedAt: null,
      source: "TEST",
      status: "LOCAL",
      dictionaryVersion: "test",
    },
    obras: [
      {
        id: "obra-cobertura",
        codigoContrato: "Contrato Cobertura",
        codigoCw: "CW Cobertura",
        codigoInterno: "Obra Cobertura",
        nome: "Obra Cobertura",
        cliente: "Cliente Cobertura",
        cidade: "Cidade Cobertura",
        uf: "UF",
        rodovia: "Rodovia Cobertura",
        status: "ATIVA",
        updatedAt: "2026-07-03T19:05:00",
      },
    ],
    rdos: [],
    programacoes: [],
    pdors: [],
    operationalEvents: [],
  };
  const ontology = loadRdoOntology(snapshot);
  const rdo = panelCoverageRdo(ontology);

  snapshot.rdos = [rdo];
  snapshot.operationalEvents = [
    panelCoverageRow(ontology, "operationalEvent", {
      id: "evento-cobertura",
      type: "FOTO_ADICIONADA",
      principalEntityType: "RDO_ATTACHMENT",
      principalEntityId: "foto-cobertura",
      obraId: "obra-cobertura",
      rdoId: "rdo-cobertura",
      colaboradorId: "colaborador-cobertura",
      occurredAt: "2026-07-03T10:00:00",
      syncedAt: "2026-07-03T10:05:00",
      origin: "OFFLINE",
      syncStatus: "PENDING_SYNC",
      responsibleUserId: "usuario-cobertura",
      responsibleUserName: "Responsável Cobertura",
      schemaVersion: "1",
      relatedEntities: [{ tipo: "RDO", id: "rdo-cobertura" }],
      payload: { campo: "valor-operationalEvent-payload" },
    }) as unknown as StaviaSnapshotOperationalEvent,
  ];

  return snapshot;
}

function panelCoverageRdo(ontology: RdoOntologyJson): StaviaSnapshotRdo {
  const rdo = makeRdo({
    id: "rdo-cobertura",
    obraId: "obra-cobertura",
    programacaoId: "programacao-cobertura",
    numeroRdo: "123",
    dataRdo: "2026-07-03",
    diaSemana: "SEXTA-FEIRA",
    cliente: "Cliente Cobertura",
    cidade: "Cidade Cobertura",
    contrato: "Contrato Cobertura",
    rodovia: "Rodovia Cobertura",
    uf: "UF",
    kmInicialProgramado: "10+000",
    kmFinalProgramado: "10+500",
    kmInicialInterditado: "10+050",
    kmFinalInterditado: "10+450",
    turno: "DIURNO",
    horaInicio: "07:00",
    horaFim: "17:00",
    condicaoManha: "BOM",
    condicaoTarde: "NUBLADO",
    condicaoNoite: "SECO",
    pluviometriaMm: "4",
    status: "APROVADO",
    fonteCriacao: "OFFLINE",
    estadoReceita: "PRODUCAO_VALIDADA",
    fonteArquivo: "rdo-cobertura.xlsx",
    abaOrigem: "RDO",
    linhaOrigem: "42",
    dataOriginal: "2026-07-03",
    dataImportacao: "2026-07-03T08:30:00",
    usuarioImportacao: "usuario.importacao",
    criadoEm: "2026-07-03T07:00:00",
    enviadoEm: "2026-07-03T18:00:00",
    aprovadoEm: "2026-07-03T19:00:00",
    versaoLinha: "7",
    syncStatus: "SYNCED",
    observacoes: "Observação Cobertura RDO",
    preenchidoPor: "Preenchedor Cobertura",
    apontadorRdo: "Apontador Cobertura",
    encarregadoObra: "Encarregado Cobertura",
    fiscalizacaoCampo: "Fiscal Cobertura",
    updatedAt: "2026-07-03T19:05:00",
  });

  rdo.materiais = [
    panelCoverageRow(ontology, "material", {
      materialNome: "Material Cobertura",
      unidade: "un",
    }) as StaviaSnapshotRdo["materiais"][number],
  ];
  rdo.maoObra = [
    panelCoverageRow(ontology, "maoObra", {
      colaboradorId: "colaborador-cobertura",
      nomeColaborador: "Colaborador Cobertura",
    }) as StaviaSnapshotRdo["maoObra"][number],
  ];
  rdo.equipamentos = [
    panelCoverageRow(ontology, "equipamento", {
      assetId: "ativo-cobertura",
      prefixo: "EQ-01",
      descricao: "Equipamento Cobertura",
    }) as StaviaSnapshotRdo["equipamentos"][number],
  ];
  rdo.controlesGeometricos = [
    panelCoverageRow(ontology, "controleGeometrico", {
      subtrecho: "Subtrecho Cobertura",
      numero: "1",
    }) as StaviaSnapshotRdo["controlesGeometricos"][number],
  ];
  rdo.servicosExecutados = [
    panelCoverageRow(ontology, "execucaoServico", {
      servicoNome: "Atividade Cobertura",
      unidade: "m",
      statusValidacao: "VALIDADA",
    }) as StaviaSnapshotRdo["servicosExecutados"][number],
  ];
  rdo.alocacoesColaboradores = [
    panelCoverageRow(ontology, "alocacaoColaborador", {
      colaboradorId: "colaborador-cobertura",
      nomeColaborador: "Alocação Cobertura",
    }) as StaviaSnapshotRdo["alocacoesColaboradores"][number],
  ];
  rdo.attachments = [
    panelCoverageRow(ontology, "attachment", {
      id: "foto-cobertura",
      rdoId: "rdo-cobertura",
      obraId: "obra-cobertura",
      nome: "foto-cobertura.webp",
    }) as StaviaSnapshotRdo["attachments"][number],
  ];

  return rdo;
}

function panelCoverageRow(
  ontology: RdoOntologyJson,
  entityName: string,
  overrides: Record<string, unknown>,
): Record<string, unknown> {
  const entity = panelEntityByName(ontology, entityName);
  const row: Record<string, unknown> = {};

  for (const attribute of entity.attributes) {
    row[attribute.name] = `valor-${entityName}-${attribute.name}`;
  }

  return { ...row, ...overrides };
}

function panelEntityByName(
  ontology: RdoOntologyJson,
  entityName: string,
): RdoOntologyEntityJson {
  const entity = ontology.entities.find(
    (candidate) => candidate.name === entityName,
  );

  if (!entity) {
    throw new Error(`Entidade sem ontologia: ${entityName}`);
  }

  return entity;
}

function panelCoverageQuestion(
  entity: RdoOntologyEntityJson,
  label: string,
): string {
  switch (entity.name) {
    case "rdo":
      return `Qual ${label} do RDO 123?`;
    case "material":
      return `Qual ${label} do material 1?`;
    case "maoObra":
      return `Qual ${label} do colaborador 1?`;
    case "equipamento":
      return `Qual ${label} do equipamento 1?`;
    case "controleGeometrico":
      return `Qual ${label} do trecho 1?`;
    case "execucaoServico":
      return `Qual ${label} da atividade 1?`;
    case "alocacaoColaborador":
      return `Qual ${label} da alocacao 1?`;
    case "attachment":
      return `Qual ${label} da foto 1?`;
    case "operationalEvent":
      return `Qual ${label} do evento 1?`;
    default:
      throw new Error(`Entidade sem pergunta de cobertura: ${entity.name}`);
  }
}

function panelCoverageValue(
  snapshot: StaviaSnapshot,
  entityName: string,
  attributeName: string,
): string {
  const row = panelCoverageRowForSnapshot(snapshot, entityName);
  const value = row[attributeName];

  expect(
    value,
    `fixture sem valor para ${entityName}.${attributeName}`,
  ).not.toBeNull();
  expect(
    value,
    `fixture sem valor para ${entityName}.${attributeName}`,
  ).not.toBeUndefined();

  if (typeof value === "object") {
    return JSON.stringify(value);
  }

  return String(value);
}

function panelCoverageRowForSnapshot(
  snapshot: StaviaSnapshot,
  entityName: string,
): Record<string, unknown> {
  const rdo = snapshot.rdos[0];

  switch (entityName) {
    case "rdo":
      return rdo as unknown as Record<string, unknown>;
    case "material":
      return rdo.materiais[0] as unknown as Record<string, unknown>;
    case "maoObra":
      return rdo.maoObra[0] as unknown as Record<string, unknown>;
    case "equipamento":
      return rdo.equipamentos[0] as unknown as Record<string, unknown>;
    case "controleGeometrico":
      return rdo.controlesGeometricos[0] as unknown as Record<string, unknown>;
    case "execucaoServico":
      return rdo.servicosExecutados[0] as unknown as Record<string, unknown>;
    case "alocacaoColaborador":
      return rdo.alocacoesColaboradores[0] as unknown as Record<string, unknown>;
    case "attachment":
      return rdo.attachments[0] as unknown as Record<string, unknown>;
    case "operationalEvent":
      return snapshot.operationalEvents[0] as unknown as Record<string, unknown>;
    default:
      throw new Error(`Entidade sem fixture de cobertura: ${entityName}`);
  }
}
