import { describe, expect, it } from "vitest";

import { responderComSnapshotStavia } from "./staviaLocalEngine";
import {
  loadRdoOntology,
  type RdoOntologyEntityJson,
  type RdoOntologyJson,
} from "./staviaRdoOntology";
import type {
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

describe("staviaLocalEngine", () => {
  it("lista observações de mão de obra e equipamentos do RDO selecionado", () => {
    const activeRdo = makeRdo({
      id: "rdo-observacoes",
      numeroRdo: "125",
      dataRdo: "2026-07-03",
      maoObra: [
        {
          colaboradorId: "col-ana",
          nomeColaborador: "Ana Lima",
          cargo: "Operadora",
          tipoVinculo: "CONTRATADO",
          quantidade: "1",
          horaInicio: "07:15",
          horaFim: "16:45",
          observacoes: "Atuou na compactação do trecho 2",
        },
      ],
      equipamentos: [
        {
          assetId: "asset-rc-08",
          prefixo: "RC-08",
          descricao: "Rolo compactador",
          tipoEquipamento: "Compactação",
          tipoVinculo: "PROPRIO",
          quantidade: "1",
          horaInicio: "08:00",
          horaFim: "15:30",
          observacoes: "Operou com vibração reduzida",
        },
      ],
    });
    const snapshot = {
      metadata: {
        snapshotKey: "default",
        generatedAt: "2026-07-03T12:00:00",
        databaseUpdatedAt: null,
        localSyncedAt: null,
        source: "TEST",
        status: "LOCAL",
        dictionaryVersion: "test",
      },
      obras: [],
      rdos: [activeRdo],
      programacoes: [],
      pdors: [],
      operationalEvents: [],
    } satisfies StaviaSnapshot;

    const response = responderComSnapshotStavia({
      snapshot,
      pergunta: "Quais observações existem neste RDO?",
      context: {
        activeObraId: "obra-1",
        activeRdoId: "rdo-observacoes",
        lastObraId: null,
        lastRdoId: null,
      },
      isOnline: false,
    });

    expect(response?.answer.answer).toContain(
      "Mão de obra · Ana Lima · horário 07:15-16:45 · Atuou na compactação do trecho 2",
    );
    expect(response?.answer.answer).toContain(
      "Equipamento · RC-08 · horário 08:00-15:30 · Operou com vibração reduzida",
    );

    const equipmentObservation = responderComSnapshotStavia({
      snapshot,
      pergunta: "Qual a observacao do equipamento RC-08?",
      context: {
        activeObraId: "obra-1",
        activeRdoId: "rdo-observacoes",
        lastObraId: null,
        lastRdoId: null,
      },
      isOnline: false,
    });

    expect(equipmentObservation?.answer.answer).toBe(
      "Observações do equipamento de RC-08: Operou com vibração reduzida "
        + "(RDO 125 de 03/07/2026).",
    );
  });

  it("responde células do RDO explicitamente selecionado", () => {
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
    const otherRdo = makeRdo({
      id: "rdo-mais-recente",
      numeroRdo: "124",
      dataRdo: "2026-07-02",
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
          comprimentoM: "111",
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
    const snapshot = {
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
      rdos: [otherRdo, activeRdo],
      programacoes: [],
      pdors: [],
      operationalEvents: [],
    } satisfies StaviaSnapshot;

    const response = responderComSnapshotStavia({
      snapshot,
      pergunta: "Qual o comprimento do trecho 2?",
      context: {
        activeObraId: "obra-1",
        activeRdoId: "rdo-ativo",
        lastObraId: null,
        lastRdoId: null,
      },
      isOnline: false,
    });

    expect(response?.answer.answer).toBe(
      "Comprimento de Trecho 2 (SP-215): 282 m (RDO 123 de 01/07/2026).",
    );

    const details = responderComSnapshotStavia({
      snapshot,
      pergunta: "Quais dados do trecho 2?",
      context: {
        activeObraId: "obra-1",
        activeRdoId: "rdo-ativo",
        lastObraId: null,
        lastRdoId: null,
      },
      isOnline: false,
    });

    expect(details?.answer.answer).toContain("Trecho 2 (SP-215)");
    expect(details?.answer.answer).toContain("Pista: Leste");
    expect(details?.answer.answer).toContain("Comprimento: 282 m");
    expect(details?.answer.answer).not.toContain("Oeste");
    expect(details?.answer.answer).not.toContain("999");
  });

  it("responde cabeçalho, fotos, eventos e alocações pela ontologia local", () => {
    const activeRdo = makeRdo({
      id: "rdo-ontologia",
      numeroRdo: "123",
      dataRdo: "2026-07-01",
      diaSemana: "QUARTA-FEIRA",
      cliente: "Cliente A",
      cidade: "São Paulo",
      syncStatus: "PENDING_SYNC",
      fiscalizacaoCampo: "Fiscal A",
      alocacoesColaboradores: [
        {
          colaboradorId: "col-ana",
          nomeColaborador: "Ana Lima",
          equipe: "Equipe A",
          servicoNome: "Fresagem",
          horaInicio: "07:00",
          horaFim: "16:00",
          minutos: 540,
          percentualDia: "1",
          turno: "DIURNO",
          funcao: "Apontadora",
          centroCusto: "CC-01",
          tipoAlocacao: "RDO",
          fonte: "LOCAL",
          status: "PENDENTE",
          custoHora: "25",
          custoTotal: "225",
          observacoes: "Registro offline",
        },
      ],
      attachments: [
        {
          id: "foto-1",
          rdoId: "rdo-ontologia",
          obraId: "obra-1",
          tipo: "FOTO",
          nome: "trecho-2.webp",
          nomeOriginal: "trecho-2.jpg",
          mimeType: "image/webp",
          tamanhoOriginalBytes: 3000000,
          tamanhoComprimidoBytes: 480000,
          tamanhoBytes: 480000,
          syncStatus: "PENDING_SYNC",
          createdAt: "2026-07-01T10:00:00",
          updatedAt: "2026-07-01T10:00:00",
          removedAt: null,
          metadata: { origem: "teste" },
        },
      ],
    });
    const snapshot = {
      metadata: {
        snapshotKey: "default",
        generatedAt: "2026-07-01T12:00:00",
        databaseUpdatedAt: null,
        localSyncedAt: null,
        source: "TEST",
        status: "LOCAL",
        dictionaryVersion: "test",
      },
      obras: [],
      rdos: [activeRdo],
      programacoes: [],
      pdors: [],
      operationalEvents: [
        {
          id: "evento-1",
          type: "FOTO_ADICIONADA",
          principalEntityType: "RDO_ATTACHMENT",
          principalEntityId: "foto-1",
          obraId: "obra-1",
          rdoId: "rdo-ontologia",
          colaboradorId: "col-ana",
          occurredAt: "2026-07-01T10:00:00",
          syncedAt: null,
          origin: "OFFLINE",
          syncStatus: "PENDING_SYNC",
          responsibleUserId: "user-ana",
          responsibleUserName: "Ana Lima",
          schemaVersion: 1,
          relatedEntities: [],
          payload: { nome: "trecho-2.webp" },
        },
      ],
    } satisfies StaviaSnapshot;
    const context = {
      activeObraId: "obra-1",
      activeRdoId: "rdo-ontologia",
      lastObraId: null,
      lastRdoId: null,
    };

    const header = responderComSnapshotStavia({
      snapshot,
      pergunta: "Mostre todos os dados do RDO 123",
      context,
      isOnline: false,
    });
    expect(header?.answer.answer).toContain("RDO 123");
    expect(header?.answer.answer).toContain(
      "Dia da semana: QUARTA-FEIRA",
    );
    expect(header?.answer.answer).toContain("Cliente: Cliente A");
    expect(header?.answer.answer).toContain(
      "Status de sincronização do RDO: PENDING_SYNC",
    );
    expect(header?.answer.answer).toContain(
      "Fiscalização de campo: Fiscal A",
    );

    const allocation = responderComSnapshotStavia({
      snapshot,
      pergunta: "Qual a funcao da alocacao 1?",
      context,
      isOnline: false,
    });
    expect(allocation?.answer.answer).toContain(
      "Função de Alocação 1 (Ana Lima): Apontadora",
    );

    const photo = responderComSnapshotStavia({
      snapshot,
      pergunta: "Qual o status da foto 1?",
      context,
      isOnline: false,
    });
    expect(photo?.answer.answer).toContain(
      "Status de sincronização de Foto 1 (trecho-2.webp): PENDING_SYNC",
    );

    const event = responderComSnapshotStavia({
      snapshot,
      pergunta: "Qual a origem do evento 1?",
      context,
      isOnline: false,
    });
    expect(event?.answer.answer).toContain(
      "Origem de Evento 1 (FOTO_ADICIONADA): OFFLINE",
    );

    const eventResponsible = responderComSnapshotStavia({
      snapshot,
      pergunta: "Quem registrou o evento 1?",
      context,
      isOnline: false,
    });
    expect(eventResponsible?.answer.answer).toContain(
      "Usuário responsável de Evento 1 (FOTO_ADICIONADA): Ana Lima",
    );
  });

  it("responde cada célula declarada pelo caminho real do motor local offline", () => {
    const snapshot = makeLocalCoverageSnapshot();
    const ontology = loadRdoOntology(snapshot);
    const context = {
      activeObraId: "obra-cobertura",
      activeRdoId: "rdo-cobertura",
      lastObraId: null,
      lastRdoId: null,
    };
    let checkedCells = 0;

    // O pdor (escopo "obra") é respondido pelo tópico dedicado do motor
    // local, não pelo caminho genérico da ontologia coberto aqui.
    const genericEntities = ontology.entities.filter(
      (entity) => (entity.scope ?? "rdo") === "rdo",
    );

    for (const entity of genericEntities) {
      for (const attribute of entity.attributes) {
        checkedCells += 1;
        const pergunta = localCoverageQuestion(entity, attribute.label);
        const response = responderComSnapshotStavia({
          snapshot,
          pergunta,
          context,
          isOnline: false,
        });
        const expectedValue = localCoverageValue(
          snapshot,
          entity.name,
          attribute.name,
        );

        expect(response, `sem resposta para ${pergunta}`).not.toBeNull();
        expect(
          response?.answer.insufficientData,
          `dado insuficiente para ${entity.name}.${attribute.name}: `
            + response?.answer.answer,
        ).toBe(false);
        expect(
          response?.answer.metadata?.caminho,
          `não usou ontologia para ${entity.name}.${attribute.name}: `
            + response?.answer.answer,
        ).toBe("ONTOLOGIA_RDO");
        expect(
          response?.answer.answer,
          `fallback inesperado para ${entity.name}.${attribute.name}`,
        ).not.toContain("Não encontrei");
        expect(
          response?.answer.answer,
          `valor ausente para ${entity.name}.${attribute.name}`,
        ).toContain(expectedValue);
      }
    }

    expect(checkedCells).toBeGreaterThan(150);
  });
});

function makeLocalCoverageSnapshot(): StaviaSnapshot {
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
    obras: [],
    rdos: [],
    programacoes: [],
    pdors: [],
    operationalEvents: [],
  };
  const ontology = loadRdoOntology(snapshot);
  const rdo = coverageRdo(ontology);

  snapshot.rdos = [rdo];
  snapshot.operationalEvents = [
    coverageRow(ontology, "operationalEvent", {
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

function coverageRdo(ontology: RdoOntologyJson): StaviaSnapshotRdo {
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
    coverageRow(ontology, "material", {
      materialNome: "Material Cobertura",
      unidade: "un",
    }) as StaviaSnapshotRdo["materiais"][number],
  ];
  rdo.maoObra = [
    coverageRow(ontology, "maoObra", {
      colaboradorId: "colaborador-cobertura",
      nomeColaborador: "Colaborador Cobertura",
    }) as StaviaSnapshotRdo["maoObra"][number],
  ];
  rdo.equipamentos = [
    coverageRow(ontology, "equipamento", {
      assetId: "ativo-cobertura",
      prefixo: "EQ-01",
      descricao: "Equipamento Cobertura",
    }) as StaviaSnapshotRdo["equipamentos"][number],
  ];
  rdo.controlesGeometricos = [
    coverageRow(ontology, "controleGeometrico", {
      subtrecho: "Subtrecho Cobertura",
      numero: "1",
    }) as StaviaSnapshotRdo["controlesGeometricos"][number],
  ];
  rdo.servicosExecutados = [
    coverageRow(ontology, "execucaoServico", {
      servicoNome: "Atividade Cobertura",
      unidade: "m",
      statusValidacao: "VALIDADA",
    }) as StaviaSnapshotRdo["servicosExecutados"][number],
  ];
  rdo.alocacoesColaboradores = [
    coverageRow(ontology, "alocacaoColaborador", {
      colaboradorId: "colaborador-cobertura",
      nomeColaborador: "Alocação Cobertura",
    }) as StaviaSnapshotRdo["alocacoesColaboradores"][number],
  ];
  rdo.attachments = [
    coverageRow(ontology, "attachment", {
      id: "foto-cobertura",
      rdoId: "rdo-cobertura",
      obraId: "obra-cobertura",
      nome: "foto-cobertura.webp",
    }) as StaviaSnapshotRdo["attachments"][number],
  ];

  return rdo;
}

function coverageRow(
  ontology: RdoOntologyJson,
  entityName: string,
  overrides: Record<string, unknown>,
): Record<string, unknown> {
  const entity = entityByName(ontology, entityName);
  const row: Record<string, unknown> = {};

  for (const attribute of entity.attributes) {
    row[attribute.name] = `valor-${entityName}-${attribute.name}`;
  }

  return { ...row, ...overrides };
}

function entityByName(
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

function localCoverageQuestion(
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

function localCoverageValue(
  snapshot: StaviaSnapshot,
  entityName: string,
  attributeName: string,
): string {
  const row = localCoverageRow(snapshot, entityName);
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

function localCoverageRow(
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
