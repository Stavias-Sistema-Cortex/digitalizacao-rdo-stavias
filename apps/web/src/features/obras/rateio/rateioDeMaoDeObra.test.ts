import { describe, expect, it } from "vitest";

import {
  apurarRateio,
  diasDoPeriodo,
  mesDe,
  normalizarNome,
  type ApontamentoDeMaoDeObra,
} from "./rateioDeMaoDeObra";

function apontamento(
  parcial: Partial<ApontamentoDeMaoDeObra> & { data: string; obraId: string },
): ApontamentoDeMaoDeObra {
  return {
    colaboradorId: "col-1",
    nome: "PESSOA UM",
    funcao: "MOTORISTA",
    encarregado: "FRENTE A",
    rdoId: `rdo-${parcial.obraId}-${parcial.data}`,
    ...parcial,
  };
}

function diasDoMes(inicio: number, fim: number): string[] {
  const dias: string[] = [];
  for (let dia = inicio; dia <= fim; dia += 1) {
    dias.push(`2026-07-${String(dia).padStart(2, "0")}`);
  }
  return dias;
}

describe("rateio de mão de obra", () => {
  it("dá a obra inteira a quem só passou por ela", () => {
    const apontamentos = diasDoMes(1, 30).map((data) =>
      apontamento({ data, obraId: "obra-norte" }),
    );

    const rateio = apurarRateio(apontamentos);

    expect(rateio.colaboradores).toHaveLength(1);
    const [pessoa] = rateio.colaboradores;
    expect(pessoa.diasApontados).toBe(30);
    expect(pessoa.fracaoPorObra.get("obra-norte")).toBe(1);
  });

  /*
   * O caso que a planilha antiga trazia linha após linha: vinte e nove dias
   * numa obra e um dia em outra viram 96,67% e 3,33%. Os números aqui são os
   * mesmos que estavam lá — se o cálculo mudar, é este teste que avisa.
   */
  it("reproduz a fração da planilha: 29 dias numa obra e 1 em outra", () => {
    const apontamentos = [
      ...diasDoMes(1, 29).map((data) =>
        apontamento({ data, obraId: "obra-sul" }),
      ),
      apontamento({ data: "2026-07-30", obraId: "obra-oeste" }),
    ];

    const rateio = apurarRateio(apontamentos);
    const [pessoa] = rateio.colaboradores;

    expect(pessoa.diasApontados).toBe(30);
    expect(pessoa.fracaoPorObra.get("obra-sul")).toBeCloseTo(
      0.9666666666666667,
      12,
    );
    expect(pessoa.fracaoPorObra.get("obra-oeste")).toBeCloseTo(
      0.03333333333333333,
      12,
    );
  });

  it("as frações de uma pessoa somam o período inteiro", () => {
    const apontamentos = [
      ...diasDoMes(1, 15).map((data) =>
        apontamento({ data, obraId: "obra-sul" }),
      ),
      ...diasDoMes(16, 31).map((data) =>
        apontamento({ data, obraId: "obra-leste" }),
      ),
    ];

    const [pessoa] = apurarRateio(apontamentos).colaboradores;
    const soma = [...pessoa.fracaoPorObra.values()].reduce(
      (total, fracao) => total + fracao,
      0,
    );

    expect(soma).toBeCloseTo(1, 12);
  });

  /*
   * A planilha escrevia uma obra por célula e perdia o dia dividido. Aqui o dia
   * vale um e se reparte: metade para cada obra em que a pessoa foi apontada.
   */
  it("reparte o dia entre as obras quando a pessoa foi apontada nas duas", () => {
    const rateio = apurarRateio([
      apontamento({ data: "2026-07-01", obraId: "obra-norte" }),
      apontamento({ data: "2026-07-01", obraId: "obra-sul" }),
      apontamento({ data: "2026-07-02", obraId: "obra-norte" }),
    ]);

    const [pessoa] = rateio.colaboradores;
    expect(pessoa.diasApontados).toBe(2);
    expect(pessoa.dias.get("2026-07-01")?.obraIds).toEqual([
      "obra-norte",
      "obra-sul",
    ]);
    expect(pessoa.dias.get("2026-07-01")?.fracaoPorObra).toBe(0.5);
    expect(pessoa.diasPorObra.get("obra-norte")).toBe(1.5);
    expect(pessoa.fracaoPorObra.get("obra-norte")).toBe(0.75);
    expect(pessoa.fracaoPorObra.get("obra-sul")).toBe(0.25);
  });

  it("não conta duas vezes quem aparece em dois RDOs da mesma obra no dia", () => {
    const rateio = apurarRateio([
      apontamento({ data: "2026-07-01", obraId: "obra-norte", rdoId: "rdo-a" }),
      apontamento({ data: "2026-07-01", obraId: "obra-norte", rdoId: "rdo-b" }),
    ]);

    const [pessoa] = rateio.colaboradores;
    expect(pessoa.diasApontados).toBe(1);
    expect(pessoa.fracaoPorObra.get("obra-norte")).toBe(1);
  });

  /*
   * Gente somada à mão em campo não tem cadastro, e o nome é tudo que existe
   * para dizer que é a mesma pessoa de ontem. Acento, caixa e espaço sobrando
   * não podem partir uma pessoa em duas linhas.
   */
  it("junta pela grafia do nome quem não tem cadastro", () => {
    const rateio = apurarRateio([
      apontamento({
        data: "2026-07-01",
        obraId: "obra-norte",
        colaboradorId: null,
        nome: " José  Antônio ",
      }),
      apontamento({
        data: "2026-07-02",
        obraId: "obra-norte",
        colaboradorId: null,
        nome: "JOSE ANTONIO",
      }),
    ]);

    expect(rateio.colaboradores).toHaveLength(1);
    expect(rateio.colaboradores[0].diasApontados).toBe(2);
    expect(rateio.colaboradores[0].colaboradorId).toBeNull();
  });

  it("mantém separadas duas pessoas com cadastro diferente", () => {
    const rateio = apurarRateio([
      apontamento({ data: "2026-07-01", obraId: "obra-norte" }),
      apontamento({
        data: "2026-07-01",
        obraId: "obra-norte",
        colaboradorId: "col-2",
        nome: "PESSOA DOIS",
      }),
    ]);

    expect(rateio.colaboradores).toHaveLength(2);
    expect(rateio.totaisPorObra.get("obra-norte")?.pessoas).toBe(2);
    expect(rateio.totaisPorObra.get("obra-norte")?.diasApontados).toBe(2);
  });

  it("escolhe a função e a frente que mais aparecem no período", () => {
    const rateio = apurarRateio([
      apontamento({
        data: "2026-07-01",
        obraId: "obra-norte",
        funcao: "AJUDANTE DE OBRA",
        encarregado: "FRENTE A",
      }),
      apontamento({
        data: "2026-07-02",
        obraId: "obra-norte",
        funcao: "AJUDANTE DE OBRA",
        encarregado: "FRENTE A",
      }),
      apontamento({
        data: "2026-07-03",
        obraId: "obra-norte",
        funcao: "OFICIAL DE OBRAS",
        encarregado: "FRENTE B",
      }),
    ]);

    const [pessoa] = rateio.colaboradores;
    expect(pessoa.funcao).toBe("AJUDANTE DE OBRA");
    expect(pessoa.encarregado).toBe("FRENTE A");
  });

  it("no empate, vale a frente mais recente — quem mudou de equipe está nela", () => {
    const rateio = apurarRateio([
      apontamento({
        data: "2026-07-01",
        obraId: "obra-norte",
        encarregado: "FRENTE A",
      }),
      apontamento({
        data: "2026-07-20",
        obraId: "obra-norte",
        encarregado: "FRENTE C",
      }),
    ]);

    expect(rateio.colaboradores[0].encarregado).toBe("FRENTE C");
  });

  it("ordena por frente e nome, e deixa sem-frente no fim", () => {
    const rateio = apurarRateio([
      apontamento({
        data: "2026-07-01",
        obraId: "obra-norte",
        colaboradorId: "c3",
        nome: "ZILDA",
        encarregado: "",
      }),
      apontamento({
        data: "2026-07-01",
        obraId: "obra-norte",
        colaboradorId: "c2",
        nome: "BRUNO",
        encarregado: "FRENTE C",
      }),
      apontamento({
        data: "2026-07-01",
        obraId: "obra-norte",
        colaboradorId: "c1",
        nome: "ANA",
        encarregado: "FRENTE C",
      }),
    ]);

    expect(rateio.colaboradores.map((pessoa) => pessoa.nome)).toEqual([
      "ANA",
      "BRUNO",
      "ZILDA",
    ]);
  });

  it("lista as obras da mais movimentada para a menos", () => {
    const rateio = apurarRateio([
      apontamento({ data: "2026-07-01", obraId: "pequena" }),
      apontamento({
        data: "2026-07-02",
        obraId: "grande",
        colaboradorId: "c2",
        nome: "BRUNO",
      }),
      apontamento({
        data: "2026-07-03",
        obraId: "grande",
        colaboradorId: "c3",
        nome: "CARLA",
      }),
    ]);

    expect(rateio.obraIds).toEqual(["grande", "pequena"]);
    expect(rateio.diasApontadosNoTotal).toBe(3);
  });

  it("agrupa os totais por frente", () => {
    const rateio = apurarRateio([
      apontamento({ data: "2026-07-01", obraId: "obra-norte" }),
      apontamento({ data: "2026-07-02", obraId: "obra-norte" }),
      apontamento({
        data: "2026-07-01",
        obraId: "obra-norte",
        colaboradorId: "c2",
        nome: "BRUNO",
        encarregado: "FRENTE C",
      }),
    ]);

    expect(rateio.totaisPorEncarregado).toEqual([
      { encarregado: "FRENTE A", pessoas: 1, diasApontados: 2 },
      { encarregado: "FRENTE C", pessoas: 1, diasApontados: 1 },
    ]);
  });

  it("ignora apontamento sem obra, sem data ou sem quem seja", () => {
    const rateio = apurarRateio([
      apontamento({ data: "", obraId: "obra-norte" }),
      apontamento({ data: "2026-07-01", obraId: "  " }),
      apontamento({
        data: "2026-07-01",
        obraId: "obra-norte",
        colaboradorId: null,
        nome: "   ",
      }),
    ]);

    expect(rateio.colaboradores).toHaveLength(0);
    expect(rateio.diasComApontamento).toHaveLength(0);
  });

  it("registra os dias em que houve apontamento, em ordem", () => {
    const rateio = apurarRateio([
      apontamento({ data: "2026-07-10", obraId: "obra-norte" }),
      apontamento({ data: "2026-07-02", obraId: "obra-norte" }),
    ]);

    expect(rateio.diasComApontamento).toEqual(["2026-07-02", "2026-07-10"]);
  });
});

describe("o calendário do período", () => {
  it("abre todos os dias do mês, inclusive os parados", () => {
    const dias = diasDoPeriodo("2026-07-01", "2026-07-31");
    expect(dias).toHaveLength(31);
    expect(dias[0]).toBe("2026-07-01");
    expect(dias.at(-1)).toBe("2026-07-31");
  });

  it("atravessa a virada do mês sem tropeçar", () => {
    expect(diasDoPeriodo("2026-01-30", "2026-02-02")).toEqual([
      "2026-01-30",
      "2026-01-31",
      "2026-02-01",
      "2026-02-02",
    ]);
  });

  it("devolve vazio quando o fim vem antes do começo", () => {
    expect(diasDoPeriodo("2026-07-31", "2026-07-01")).toEqual([]);
  });

  it("não deixa um período absurdo virar laço infinito", () => {
    expect(diasDoPeriodo("2020-01-01", "2030-01-01")).toHaveLength(400);
  });

  it("acha o primeiro e o último dia do mês, inclusive fevereiro bissexto", () => {
    expect(mesDe("2026-07-14")).toEqual({
      inicio: "2026-07-01",
      fim: "2026-07-31",
    });
    expect(mesDe("2024-02")).toEqual({
      inicio: "2024-02-01",
      fim: "2024-02-29",
    });
    expect(mesDe("2026-02-10")).toEqual({
      inicio: "2026-02-01",
      fim: "2026-02-28",
    });
  });
});

describe("a grafia do nome", () => {
  it("compara sem acento, sem caixa e sem espaço sobrando", () => {
    expect(normalizarNome(" José  Antônio ")).toBe("JOSE ANTONIO");
    expect(normalizarNome("JOÃO DA PONTE")).toBe("JOAO DA PONTE");
  });
});
