import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  answerWithRdoOntology,
  loadRdoOntology,
  matchesOntologyAttribute,
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
	    status: null,
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
	    syncStatus: null,
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

const rdos: StaviaSnapshotRdo[] = [
  makeRdo({
    id: "r1",
    programacaoId: "prog-1",
    numeroRdo: "123",
    dataRdo: "2026-07-01",
    diaSemana: "QUARTA-FEIRA",
    status: "APROVADO",
    syncStatus: "SYNCED",
    turno: "DIURNO",
    cliente: "Cliente A",
    cidade: "São Paulo",
    contrato: "CTR-001",
    rodovia: "SP-215",
    uf: "SP",
    kmInicialProgramado: "100.000",
    kmFinalProgramado: "100.500",
    kmInicialInterditado: "100.120",
    kmFinalInterditado: "100.402",
    horaInicio: "07:00",
    horaFim: "17:00",
    condicaoManha: "BOM",
    condicaoTarde: "NUBLADO",
    condicaoNoite: "NAO_APLICAVEL",
    observacoes: "RDO preenchido em campo",
    fonteCriacao: "IMPORTACAO_HISTORICA",
    estadoReceita: "PRODUCAO_VALIDADA",
    fonteArquivo: "rdo-julho.xlsx",
    abaOrigem: "Frente 01",
    linhaOrigem: 42,
    dataOriginal: "2026-07-01",
    dataImportacao: "2026-07-02T08:30:00",
    usuarioImportacao: "importador.academy",
    criadoEm: "2026-07-01T06:55:00",
    enviadoEm: "2026-07-01T18:10:00",
    aprovadoEm: "2026-07-02T09:15:00",
    versaoLinha: 7,
    preenchidoPor: "João Silva",
    apontadorRdo: "João Silva",
    encarregadoObra: "Maria Souza",
    fiscalizacaoCampo: "Fiscal A",
    updatedAt: "2026-07-01T18:00:00",
    pluviometriaMm: "4",
    materiais: [
      {
        materialNome: "CAP 30/45",
        unidade: "t",
        quantidadePrevista: "12.5",
        quantidadeUsinada: "12.1",
        quantidadeAplicada: "11.9",
        quantidadeSobra: "0.6",
        notaFiscal: "NF-123",
        fornecedor: "Fornecedor A",
        observacoes: "Material liberado pela usina",
      },
      {
        materialNome: "Massa asfáltica prevista",
        unidade: "t",
        quantidadePrevista: "35",
        quantidadeUsinada: null,
        quantidadeAplicada: "33.5",
        quantidadeSobra: "1.5",
        notaFiscal: null,
        fornecedor: null,
        observacoes: null,
      },
    ],
    maoObra: [
      {
        colaboradorId: "col-mao-1",
        nomeColaborador: "João Silva",
        cargo: "Apontador",
        tipoVinculo: "CONTRATADO",
        quantidade: "1",
        horaInicio: "07:00",
        horaFim: "17:00",
        observacoes: "Equipe completa",
      },
      {
        colaboradorId: "col-mao-2",
        nomeColaborador: "Maria Souza",
        cargo: "Encarregada",
        tipoVinculo: "CONTRATADO",
        quantidade: "1",
        horaInicio: null,
        horaFim: null,
        observacoes: null,
      },
    ],
    equipamentos: [
      {
        assetId: "asset-va-01",
        prefixo: "VA-01",
        descricao: "Vibroacabadora",
        tipoEquipamento: "Pavimentação",
        tipoVinculo: "PROPRIO",
        quantidade: "1",
        horaInicio: "07:00",
        horaFim: "17:00",
        observacoes: "Equipamento revisado",
      },
      {
        assetId: "asset-rc-02",
        prefixo: "RC-02",
        descricao: "Rolo compactador",
        tipoEquipamento: "Compactação",
        tipoVinculo: "PROPRIO",
        quantidade: "2",
        horaInicio: null,
        horaFim: null,
        observacoes: null,
      },
    ],
    controlesGeometricos: [
      {
        subtrecho: "SP-215",
        numero: "1",
        estacaInicial: "10",
        estacaFinal: "12",
        kmInicial: "100.000",
        kmFinal: "100.120",
        pista: "Oeste",
        faixa: "1",
        ordemServico: "OS-1",
        comprimentoM: "120",
        larguraM: "3.5",
        espessura1Cm: "4",
        espessura2Cm: "4.2",
        espessura3Cm: null,
        espessuraMediaCm: "4.1",
        areaM2: "420",
        volumeM3: "17.22",
        densidade: "2.4",
        massaTonelada: "41.328",
        atividadeObservacoes: null,
        observacoes: null,
      },
      {
        subtrecho: "SP-215",
        numero: "2",
        estacaInicial: "13",
        estacaFinal: "18",
        kmInicial: "100.120",
        kmFinal: "100.402",
        pista: "Leste",
        faixa: "2",
        ordemServico: "OS-2",
        comprimentoM: "282",
        larguraM: "3.5",
        espessura1Cm: "5",
        espessura2Cm: "5.4",
        espessura3Cm: "5.2",
        espessuraMediaCm: "5.2",
        areaM2: "987",
        volumeM3: "51.324",
        densidade: "2.4",
        massaTonelada: "123.178",
        atividadeObservacoes: "Recomposição no trecho 2",
        observacoes: "Sem restrições",
      },
    ],
    servicosExecutados: [
      {
        servicoNome: "Fresagem contínua",
        itemContratualId: "item-fresagem",
        quantidadeExecutada: "850",
        unidade: "m²",
        trechoInicial: "100.120",
        trechoFinal: "100.402",
        localizacao: "Pista Leste",
        dataExecucao: "2026-07-01",
        turno: "DIURNO",
        statusValidacao: "VALIDADA",
        estadoReceita: "PRODUCAO_VALIDADA",
        receitaOperacionalEstimativa: "12000",
        custoRealizado: "7400",
        retrabalho: false,
        producaoRejeitada: false,
        observacoes: "Serviço acompanhado pela fiscalização",
      },
      {
        servicoNome: "Recomposição manual",
        itemContratualId: "item-recomposicao",
        quantidadeExecutada: "40",
        unidade: "m",
        trechoInicial: "100.402",
        trechoFinal: "100.442",
        localizacao: "Acostamento",
        dataExecucao: "2026-07-01",
        turno: "DIURNO",
        statusValidacao: "PENDENTE",
        estadoReceita: "EM_MEDICAO",
        receitaOperacionalEstimativa: "1800",
        custoRealizado: "900",
        retrabalho: false,
        producaoRejeitada: false,
        observacoes: "Ajuste manual no bordo",
      },
    ],
    alocacoesColaboradores: [
      {
        colaboradorId: "col-1",
        nomeColaborador: "João Silva",
        equipe: "Equipe A",
        servicoNome: "Fresagem contínua",
        horaInicio: "07:00",
        horaFim: "17:00",
        minutos: "600",
        percentualDia: "1",
        turno: "DIURNO",
        funcao: "Apontador",
        centroCusto: "CC-01",
        tipoAlocacao: "TRABALHO",
        fonte: "RDO",
        status: "VALIDADA",
        custoHora: "35",
        custoTotal: "350",
        observacoes: "Apontamento conferido",
      },
      {
        colaboradorId: "col-2",
        nomeColaborador: "Maria Souza",
        equipe: "Equipe A",
        servicoNome: "Recomposição manual",
        horaInicio: "08:00",
        horaFim: "12:00",
        minutos: "240",
        percentualDia: "0.5",
        turno: "DIURNO",
        funcao: "Encarregada",
        centroCusto: "CC-01",
        tipoAlocacao: "TRABALHO",
        fonte: "RDO",
        status: "VALIDADA",
        custoHora: "50",
        custoTotal: "200",
        observacoes: "Coordenou ajuste manual",
      },
    ],
    attachments: [
      {
        id: "foto-1",
        rdoId: "r1",
        obraId: "obra-1",
        tipo: "FOTO",
        nome: "trecho-2.webp",
        nomeOriginal: "IMG_0001.JPG",
        mimeType: "image/webp",
        tamanhoOriginalBytes: "5000000",
        tamanhoComprimidoBytes: "900000",
        tamanhoBytes: "900000",
        syncStatus: "PENDING_SYNC",
        createdAt: "2026-07-01T12:00:00",
        updatedAt: "2026-07-01T12:01:00",
        removedAt: "2026-07-01T13:00:00",
        metadata: { origem: "camera" },
      },
      {
        id: "foto-2",
        rdoId: "r1",
        obraId: "obra-1",
        tipo: "FOTO",
        nome: "acabamento.webp",
        nomeOriginal: "IMG_0002.JPG",
        mimeType: "image/webp",
        tamanhoOriginalBytes: "3000000",
        tamanhoComprimidoBytes: "700000",
        tamanhoBytes: "700000",
        syncStatus: "SYNCED",
        createdAt: "2026-07-01T11:00:00",
        updatedAt: "2026-07-01T11:01:00",
        removedAt: null,
        metadata: { origem: "camera" },
      },
    ],
  }),
  makeRdo({
    id: "r2",
    numeroRdo: "124",
    dataRdo: "2026-07-02",
    status: "ENVIADO",
    pluviometriaMm: "18.2",
    materiais: [
      {
        materialNome: "Massa asfáltica prevista",
        unidade: "t",
        quantidadePrevista: null,
        quantidadeUsinada: null,
        quantidadeAplicada: "20",
        quantidadeSobra: null,
        notaFiscal: null,
        fornecedor: null,
        observacoes: null,
      },
    ],
    equipamentos: [
      {
        assetId: "asset-cb-07",
        prefixo: "CB-07",
        descricao: "Caminhão basculante",
        tipoEquipamento: "Transporte",
        tipoVinculo: "ALUGADO",
        quantidade: "3",
        horaInicio: null,
        horaFim: null,
        observacoes: null,
      },
    ],
  }),
  makeRdo({
    id: "r3",
    numeroRdo: "122",
    dataRdo: "2026-06-30",
    status: "APROVADO",
    pluviometriaMm: "0",
  }),
];

const operationalEvents: StaviaSnapshotOperationalEvent[] = [
  {
    id: "event-1",
    type: "FOTO_ADICIONADA",
    principalEntityType: "RDO_ATTACHMENT",
    principalEntityId: "foto-1",
    obraId: "obra-1",
    rdoId: "r1",
    colaboradorId: "col-1",
    occurredAt: "2026-07-01T12:00:00",
    syncedAt: "2026-07-01T12:05:00",
    origin: "OFFLINE",
    syncStatus: "PENDING_SYNC",
    responsibleUserId: "user-1",
    responsibleUserName: "João Silva",
    schemaVersion: 1,
    relatedEntities: [
      { tipo: "RDO", id: "r1", nome: "RDO 123" },
    ],
    payload: {
      nome: "trecho-2.webp",
      tamanhoComprimidoBytes: 900000,
    },
  },
  {
    id: "event-2",
    type: "RDO_EDITADO",
    principalEntityType: "RDO",
    principalEntityId: "r1",
    obraId: "obra-1",
    rdoId: "r1",
    colaboradorId: "col-2",
    occurredAt: "2026-07-01T10:00:00",
    syncedAt: "2026-07-01T10:02:00",
    origin: "ONLINE",
    syncStatus: "SYNCED",
    responsibleUserId: "user-2",
    responsibleUserName: "Maria Souza",
    schemaVersion: 2,
    relatedEntities: [
      { tipo: "RDO", id: "r1", nome: "RDO 123" },
    ],
    payload: {
      campo: "observacoes",
      valorNovo: "RDO preenchido em campo",
    },
  },
];

const snapshot = { rdos } as unknown as StaviaSnapshot;
const ontology = loadRdoOntology(snapshot);

function responseFor(pergunta: string) {
  const response = answerWithRdoOntology({
    ontology,
    pergunta,
    rdos,
    operationalEvents,
  });

  expect(response, `resposta para: ${pergunta}`).not.toBeNull();

  return response!;
}

function answer(pergunta: string): string {
  return responseFor(pergunta).answer.answer;
}

describe("staviaRdoOntology", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 6, 2, 12, 0, 0));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("responde fato de material com unidade e contexto do RDO", () => {
    expect(answer("Qual a quantidade prevista de CAP 30/45?")).toBe(
      "Quantidade prevista de CAP 30/45: 12.5 t (RDO 123 de 01/07/2026).",
    );
  });

  it("responde células do cabeçalho do RDO por identidade", () => {
    expect(answer("Qual a cidade do RDO 123?")).toBe(
      "Cidade do RDO 123: São Paulo (RDO 123 de 01/07/2026).",
    );

    expect(answer("Quem preencheu o RDO 123?")).toBe(
      "Preenchido por no RDO 123: João Silva (RDO 123 de 01/07/2026).",
    );

    expect(answer("Qual o status de sync do RDO 123?")).toBe(
      "Status de sincronização do RDO 123: SYNCED "
        + "(RDO 123 de 01/07/2026).",
    );

    expect(answer("Qual a programacao vinculada do RDO 123?")).toBe(
      "ID da programação do RDO 123: prog-1 (RDO 123 de 01/07/2026).",
    );
  });

  it("responde nota fiscal e fornecedor ausente com fallback", () => {
    const missing = answer("Qual a nota fiscal do CAP 99/99?");

    expect(missing).toContain("Registrados:");
    expect(missing).toContain("CAP 30/45");
  });

  it("lista materiais registrados", () => {
    const text = answer("Quais materiais foram registrados?");

    expect(text).toContain("CAP 30/45");
    expect(text).toContain("Massa asfáltica prevista");
  });

  it("responde observações dos blocos de material, equipe e equipamento", () => {
    expect(answer("Qual a observacao do material CAP 30/45?")).toBe(
      "Observações do material de CAP 30/45: Material liberado pela usina "
        + "(RDO 123 de 01/07/2026).",
    );

    expect(answer("Qual a observacao da mao de obra Joao Silva?")).toBe(
      "Observações da mão de obra de João Silva: Equipe completa "
        + "(RDO 123 de 01/07/2026).",
    );

    expect(answer("Qual a observacao do equipamento VA-01?")).toBe(
      "Observações do equipamento de VA-01: Equipamento revisado "
        + "(RDO 123 de 01/07/2026).",
    );
  });

  it("agrega quantidade aplicada da semana", () => {
    expect(
      answer("Quanto de massa asfaltica foi aplicada essa semana?"),
    ).toBe(
      "Total de Quantidade aplicada: 53.5 "
        + "(2 registros, período 29/06/2026 a 02/07/2026).",
    );
  });

  it("soma sobras de massa asfáltica", () => {
    expect(
      answer("Quantas toneladas de massa asfaltica sobraram?"),
    ).toContain("Sobra");
  });

  it("conta equipamentos de ontem", () => {
    expect(answer("Quantos equipamentos ontem?")).toBe(
      "Total de Quantidade: 3 (2 registros, "
        + "período 01/07/2026 a 01/07/2026).",
    );
  });

  it("lista quem trabalhou em data explícita", () => {
    const text = answer("Quem trabalhou no RDO de 01/07/2026?");

    expect(text).toContain("João Silva");
    expect(text).toContain("Maria Souza");
  });

  it("responde cargo por identidade", () => {
    expect(answer("Qual o cargo de Joao Silva?")).toContain("Cargo");
  });

  it("compara previsto vs aplicado", () => {
    const text = answer("Comparar previsto vs aplicado de CAP 30/45");

    expect(text).toContain("Quantidade prevista: 12.5 t");
    expect(text).toContain("Quantidade aplicada: 11.9 t");
  });

  it("responde ranking de chuva por RDO", () => {
    const text = answer("Qual RDO teve mais chuva?");

    expect(text).toContain(
      "O RDO 124 de 02/07/2026 teve o maior valor de Pluviometria: 18.2 mm.",
    );
    expect(text).toContain("- 2º: RDO 123 de 01/07/2026 — 4 mm");
  });

  it("soma produção de serviço por identidade", () => {
    expect(
      answer("Quanto foi executado de fresagem essa semana?"),
    ).toContain("Quantidade executada");
  });

  it("responde célula de controle geométrico pelo ordinal visual do trecho", () => {
    expect(answer("Qual o comprimento do trecho 2?")).toBe(
      "Comprimento de Trecho 2 (SP-215): 282 m (RDO 123 de 01/07/2026).",
    );

    expect(answer("Qual a espessura 2 do trecho 2?")).toBe(
      "Espessura 2 de Trecho 2 (SP-215): 5.4 cm (RDO 123 de 01/07/2026).",
    );
  });

  it("lista todas as células não vazias de um bloco do RDO", () => {
    const text = answer("Quais dados do trecho 2?");

    expect(text).toContain("Trecho 2 (SP-215)");
    expect(text).toContain("Subtrecho: SP-215");
    expect(text).toContain("Número: 2");
    expect(text).toContain("Pista: Leste");
    expect(text).toContain("Comprimento: 282 m");
    expect(text).toContain("Espessura 2: 5.4 cm");
    expect(text).toContain("Observações do controle: Sem restrições");
  });

  it("lista células do cabeçalho quando a pergunta pede dados do RDO", () => {
    const text = answer("Mostre todos os dados do RDO 123");

    expect(text).toContain("RDO 123");
    expect(text).toContain("Data do RDO: 2026-07-01");
    expect(text).toContain("Dia da semana: QUARTA-FEIRA");
    expect(text).toContain("Cliente: Cliente A");
    expect(text).toContain("Cidade: São Paulo");
    expect(text).toContain("Status de sincronização do RDO: SYNCED");
    expect(text).toContain("Fiscalização de campo: Fiscal A");
  });

  it("responde dia da semana como célula do cabeçalho do RDO", () => {
    expect(answer("Qual o dia da semana do RDO 123?")).toBe(
      "Dia da semana do RDO 123: QUARTA-FEIRA "
        + "(RDO 123 de 01/07/2026).",
    );
  });

  it("lista células completas de equipamentos e serviços por identidade", () => {
    const equipamento = answer("Mostre os dados do equipamento VA-01");
    const servico = answer("Mostre os dados do servico Fresagem continua");

    expect(equipamento).toContain("ID do ativo: asset-va-01");
    expect(equipamento).toContain("Tipo de equipamento: Pavimentação");
    expect(equipamento).toContain("Hora de início: 07:00");
    expect(equipamento).toContain(
      "Observações do equipamento: Equipamento revisado",
    );

    expect(servico).toContain("ID do item contratual: item-fresagem");
    expect(servico).toContain("Localização: Pista Leste");
    expect(servico).toContain("Custo realizado: 7400 R$");
    expect(servico).toContain("Retrabalho: false");
  });

  it("responde células de alocação e foto pelo ordinal visual", () => {
    expect(answer("Qual a funcao da alocacao 1?")).toBe(
      "Função de Alocação 1 (João Silva): Apontador "
        + "(RDO 123 de 01/07/2026).",
    );

    expect(answer("Qual o status da foto 1?")).toBe(
      "Status de sincronização de Foto 1 (trecho-2.webp): PENDING_SYNC "
        + "(RDO 123 de 01/07/2026).",
    );
  });

  it("responde ordinais visuais naturais em todos os blocos repetíveis", () => {
    expect(answer("Qual a quantidade aplicada do material 2?")).toBe(
      "Quantidade aplicada de Massa asfáltica prevista: 33.5 t "
        + "(RDO 123 de 01/07/2026).",
    );

    expect(answer("Qual o cargo do colaborador 2?")).toBe(
      "Cargo de Maria Souza: Encarregada (RDO 123 de 01/07/2026).",
    );

    expect(answer("Qual a hora de início do colaborador 1?")).toBe(
      "Hora de início de João Silva: 07:00 (RDO 123 de 01/07/2026).",
    );

    expect(answer("Qual o equipamento da maquina 2?")).toBe(
      "Equipamento de RC-02: Rolo compactador (RDO 123 de 01/07/2026).",
    );

    expect(answer("Qual a quantidade executada da atividade 2?")).toBe(
      "Quantidade executada de Recomposição manual: 40 m "
        + "(RDO 123 de 01/07/2026).",
    );

    expect(answer("Qual a funcao da alocacao 2?")).toBe(
      "Função de Alocação 2 (Maria Souza): Encarregada "
        + "(RDO 123 de 01/07/2026).",
    );

    expect(answer("Qual a hora de início da alocacao 1?")).toBe(
      "Hora de início de Alocação 1 (João Silva): 07:00 "
        + "(RDO 123 de 01/07/2026).",
    );

    expect(answer("Qual o status da foto 2?")).toBe(
      "Status de sincronização de Foto 2 (acabamento.webp): SYNCED "
        + "(RDO 123 de 01/07/2026).",
    );

    expect(answer("Qual a origem do evento 2?")).toBe(
      "Origem de Evento 2 (RDO_EDITADO): ONLINE "
        + "(RDO 123 de 01/07/2026).",
    );
  });

  it("responde células de evento operacional pelo ordinal visual", () => {
    expect(answer("Qual a origem do evento 1?")).toBe(
      "Origem de Evento 1 (FOTO_ADICIONADA): OFFLINE "
        + "(RDO 123 de 01/07/2026).",
    );

    expect(answer("Qual o status do evento 1?")).toBe(
      "Status de sincronização de Evento 1 (FOTO_ADICIONADA): PENDING_SYNC "
        + "(RDO 123 de 01/07/2026).",
    );

    expect(answer("Quem registrou o evento 1?")).toBe(
      "Usuário responsável de Evento 1 (FOTO_ADICIONADA): João Silva "
        + "(RDO 123 de 01/07/2026).",
    );

    expect(answer("Qual a versao do schema do evento 1?")).toBe(
      "Versão do schema de Evento 1 (FOTO_ADICIONADA): 1 "
        + "(RDO 123 de 01/07/2026).",
    );
  });

  it("mantém cobertura declarativa dos campos do snapshot RDO", () => {
    const entitiesByName = new Map(
      ontology.entities.map((entity) => [entity.name, entity]),
    );
    const expectedFieldsByEntity: Record<string, string[]> = {
      rdo: [
        "id",
        "obraId",
        "programacaoId",
        "numeroRdo",
        "dataRdo",
        "diaSemana",
        "cliente",
        "contrato",
        "rodovia",
        "cidade",
        "uf",
        "kmInicialProgramado",
        "kmFinalProgramado",
        "kmInicialInterditado",
        "kmFinalInterditado",
        "turno",
        "horaInicio",
        "horaFim",
        "pluviometriaMm",
        "condicaoManha",
        "condicaoTarde",
        "condicaoNoite",
	        "status",
	        "fonteCriacao",
	        "estadoReceita",
	        "fonteArquivo",
	        "abaOrigem",
	        "linhaOrigem",
	        "dataOriginal",
	        "dataImportacao",
	        "usuarioImportacao",
	        "criadoEm",
	        "enviadoEm",
	        "aprovadoEm",
	        "versaoLinha",
	        "syncStatus",
        "observacoes",
        "preenchidoPor",
        "apontadorRdo",
        "encarregadoObra",
        "fiscalizacaoCampo",
        "updatedAt",
      ],
      material: [
        "materialNome",
        "unidade",
        "quantidadePrevista",
        "quantidadeUsinada",
        "quantidadeAplicada",
        "quantidadeSobra",
        "notaFiscal",
        "fornecedor",
        "observacoes",
      ],
      maoObra: [
        "colaboradorId",
        "nomeColaborador",
        "cargo",
        "tipoVinculo",
        "quantidade",
        "horaInicio",
        "horaFim",
        "observacoes",
      ],
      equipamento: [
        "assetId",
        "prefixo",
        "descricao",
        "tipoEquipamento",
        "tipoVinculo",
        "quantidade",
        "horaInicio",
        "horaFim",
        "observacoes",
      ],
      controleGeometrico: [
        "subtrecho",
        "numero",
        "estacaInicial",
        "estacaFinal",
        "kmInicial",
        "kmFinal",
        "pista",
        "faixa",
        "ordemServico",
        "comprimentoM",
        "larguraM",
        "espessura1Cm",
        "espessura2Cm",
        "espessura3Cm",
        "espessuraMediaCm",
        "areaM2",
        "volumeM3",
        "densidade",
        "massaTonelada",
        "atividadeObservacoes",
        "observacoes",
      ],
      execucaoServico: [
        "servicoNome",
        "itemContratualId",
        "quantidadeExecutada",
        "unidade",
        "trechoInicial",
        "trechoFinal",
        "localizacao",
        "dataExecucao",
        "turno",
        "statusValidacao",
        "estadoReceita",
        "receitaOperacionalEstimativa",
        "custoRealizado",
        "retrabalho",
        "producaoRejeitada",
        "observacoes",
      ],
      alocacaoColaborador: [
        "colaboradorId",
        "nomeColaborador",
        "equipe",
        "servicoNome",
        "horaInicio",
        "horaFim",
        "minutos",
        "percentualDia",
        "turno",
        "funcao",
        "centroCusto",
        "tipoAlocacao",
        "fonte",
        "status",
        "custoHora",
        "custoTotal",
        "observacoes",
      ],
      attachment: [
        "id",
        "rdoId",
        "obraId",
        "tipo",
        "nome",
        "nomeOriginal",
        "mimeType",
        "tamanhoOriginalBytes",
        "tamanhoComprimidoBytes",
        "tamanhoBytes",
        "syncStatus",
        "createdAt",
        "updatedAt",
        "removedAt",
        "metadata",
      ],
      operationalEvent: [
        "id",
        "type",
        "principalEntityType",
        "principalEntityId",
        "obraId",
        "rdoId",
        "colaboradorId",
        "occurredAt",
        "syncedAt",
        "origin",
        "syncStatus",
        "responsibleUserId",
        "responsibleUserName",
        "schemaVersion",
        "relatedEntities",
        "payload",
      ],
    };

    for (const [entityName, expectedFields] of Object.entries(
      expectedFieldsByEntity,
    )) {
      const entity = entitiesByName.get(entityName);
      expect(entity, `entidade ${entityName}`).toBeDefined();

      const actualFields = new Set(
        entity?.attributes.map((attribute) => attribute.name),
      );
      expect(
        expectedFields.filter((field) => !actualFields.has(field)),
        `campos sem ontologia em ${entityName}`,
      ).toEqual([]);
    }
  });

  it("responde cada atributo declarado usando o rótulo oficial da célula", () => {
    function questionFor(entityName: string, label: string): string {
      switch (entityName) {
        case "rdo":
          return `Qual ${label} do RDO 123?`;
        case "material":
          return `Qual ${label} do material CAP 30/45?`;
        case "maoObra":
          return `Qual ${label} da mao de obra Joao Silva?`;
        case "equipamento":
          return `Qual ${label} do equipamento VA-01?`;
        case "controleGeometrico":
          return `Qual ${label} do trecho 2?`;
        case "execucaoServico":
          return `Qual ${label} do servico Fresagem continua?`;
        case "alocacaoColaborador":
          return `Qual ${label} da alocacao 1?`;
        case "attachment":
          return `Qual ${label} da foto 1?`;
        case "operationalEvent":
          return `Qual ${label} do evento 1?`;
        default:
          throw new Error(`Entidade sem pergunta de cobertura: ${entityName}`);
      }
    }

    // Entidades fora do escopo "rdo" (ex.: pdor) são atendidas pelos
    // caminhos dedicados do motor, não pelo caminho genérico da ontologia.
    const genericEntities = ontology.entities.filter(
      (entity) => (entity.scope ?? "rdo") === "rdo",
    );

    for (const entity of genericEntities) {
      for (const attribute of entity.attributes) {
        const pergunta = questionFor(entity.name, attribute.label);

        expect(
          matchesOntologyAttribute(ontology, pergunta),
          `alias/label não reconhecido: ${entity.name}.${attribute.name}`,
        ).toBe(true);

        const response = responseFor(pergunta);

        expect(
          response.answer.insufficientData,
          `sem dado para ${entity.name}.${attribute.name}: ${response.answer.answer}`,
        ).toBe(false);
        expect(
          response.answer.answer,
          `fallback inesperado para ${entity.name}.${attribute.name}`,
        ).not.toContain("Não encontrei");
        expect(
          response.answer.answer,
          `valor ausente para ${entity.name}.${attribute.name}`,
        ).toContain(coverageValue(entity.name, attribute.name));
      }
    }
  });

  it("só ativa o caminho genérico com alias de atributo", () => {
    expect(
      matchesOntologyAttribute(ontology, "Qual a quantidade prevista?"),
    ).toBe(true);
    expect(
      matchesOntologyAttribute(ontology, "Qual a cidade da obra?"),
    ).toBe(true);
  });
});

function coverageValue(entityName: string, attributeName: string): string {
  const row = coverageRow(entityName);
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

function coverageRow(entityName: string): Record<string, unknown> {
  const rdo = rdos[0];

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
      return rdo.controlesGeometricos[1] as unknown as Record<string, unknown>;
    case "execucaoServico":
      return rdo.servicosExecutados[0] as unknown as Record<string, unknown>;
    case "alocacaoColaborador":
      return rdo.alocacoesColaboradores[0] as unknown as Record<string, unknown>;
    case "attachment":
      return rdo.attachments[0] as unknown as Record<string, unknown>;
    case "operationalEvent":
      return operationalEvents[0] as unknown as Record<string, unknown>;
    default:
      throw new Error(`Entidade sem fixture de cobertura: ${entityName}`);
  }
}
