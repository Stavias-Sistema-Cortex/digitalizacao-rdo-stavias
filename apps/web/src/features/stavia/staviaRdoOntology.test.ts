import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  answerWithRdoOntology,
  loadRdoOntology,
  matchesOntologyAttribute,
} from "./staviaRdoOntology";
import type {
  StaviaSnapshot,
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
    ...partial,
  };
}

const rdos: StaviaSnapshotRdo[] = [
  makeRdo({
    id: "r1",
    numeroRdo: "123",
    dataRdo: "2026-07-01",
    status: "APROVADO",
    turno: "DIURNO",
    pluviometriaMm: "4",
    materiais: [
      {
        materialNome: "CAP 30/45",
        unidade: "t",
        quantidadePrevista: "12.5",
        quantidadeUsinada: null,
        quantidadeAplicada: "11.9",
        quantidadeSobra: "0.6",
      },
      {
        materialNome: "Massa asfáltica prevista",
        unidade: "t",
        quantidadePrevista: "35",
        quantidadeUsinada: null,
        quantidadeAplicada: "33.5",
        quantidadeSobra: "1.5",
      },
    ],
    maoObra: [
      {
        nomeColaborador: "João Silva",
        cargo: "Apontador",
        tipoVinculo: "CONTRATADO",
        quantidade: "1",
      },
      {
        nomeColaborador: "Maria Souza",
        cargo: "Encarregada",
        tipoVinculo: "CONTRATADO",
        quantidade: "1",
      },
    ],
    equipamentos: [
      {
        prefixo: "VA-01",
        descricao: "Vibroacabadora",
        tipoEquipamento: "Pavimentação",
        tipoVinculo: "PROPRIO",
        quantidade: "1",
      },
      {
        prefixo: "RC-02",
        descricao: "Rolo compactador",
        tipoEquipamento: "Compactação",
        tipoVinculo: "PROPRIO",
        quantidade: "2",
      },
    ],
    servicosExecutados: [
      {
        servicoNome: "Fresagem contínua",
        quantidadeExecutada: "850",
        unidade: "m²",
        trechoInicial: null,
        trechoFinal: null,
        localizacao: null,
        turno: "DIURNO",
        statusValidacao: "VALIDADA",
        observacoes: null,
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
      },
    ],
    equipamentos: [
      {
        prefixo: "CB-07",
        descricao: "Caminhão basculante",
        tipoEquipamento: "Transporte",
        tipoVinculo: "ALUGADO",
        quantidade: "3",
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

const snapshot = { rdos } as unknown as StaviaSnapshot;
const ontology = loadRdoOntology(snapshot);

function answer(pergunta: string): string {
  const response = answerWithRdoOntology({ ontology, pergunta, rdos });

  expect(response, `resposta para: ${pergunta}`).not.toBeNull();

  return response!.answer.answer;
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

  it("só ativa o caminho genérico com alias de atributo", () => {
    expect(
      matchesOntologyAttribute(ontology, "Qual a quantidade prevista?"),
    ).toBe(true);
    expect(
      matchesOntologyAttribute(ontology, "Qual a cidade da obra?"),
    ).toBe(false);
  });
});
