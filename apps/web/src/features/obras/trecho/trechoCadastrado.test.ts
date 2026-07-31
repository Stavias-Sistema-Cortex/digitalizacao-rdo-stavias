import { describe, expect, it } from "vitest";

import {
  CADASTRO_VAZIO,
  divergenciasComORdo,
  nomeDoTrecho,
  propriedadesDoCadastro,
  segmentosDoTrechoCadastrado,
  validarCadastro,
  type CadastroTrecho,
} from "./trechoCadastrado";
import { estadoDoSegmento, type SegmentoTrecho } from "./trechoGeometry";

function cadastro(overrides: Partial<CadastroTrecho> = {}): CadastroTrecho {
  return {
    ...CADASTRO_VAZIO,
    rodovia: "SP-310",
    sentido: "SUL",
    faixa: "DIREITA",
    kmInicial: "172",
    kmFinal: "171",
    ...overrides,
  };
}

describe("validarCadastro", () => {
  it("aceita um trecho com os dois extremos declarados", () => {
    expect(validarCadastro(cadastro())).toBeNull();
  });

  it("exige a rodovia", () => {
    expect(validarCadastro(cadastro({ rodovia: "  " })))
      .toMatch(/rodovia/i);
  });

  it("exige os dois extremos, porque um só é ponto e não trecho", () => {
    expect(validarCadastro(cadastro({ kmFinal: "" })))
      .toMatch(/km inicial e o km final/i);
  });

  it("recusa extremos iguais", () => {
    expect(validarCadastro(cadastro({ kmFinal: "172" })))
      .toMatch(/mesmo ponto/i);
  });

  it("recusa extensão medida que não é número positivo", () => {
    expect(validarCadastro(cadastro({ extensaoM: "-5" })))
      .toMatch(/maior que zero/i);
    expect(validarCadastro(cadastro({ extensaoM: "1500" }))).toBeNull();
  });

  it("aceita a notação rodoviária de km", () => {
    expect(validarCadastro(cadastro({
      kmInicial: "309+400",
      kmFinal: "310+100",
    }))).toBeNull();
  });
});

describe("propriedadesDoCadastro", () => {
  it("grava apenas as chaves que a ontologia autoriza para geometria", () => {
    const propriedades = propriedadesDoCadastro(cadastro(), 1480);

    expect(propriedades).toMatchObject({
      rodovia: "SP-310",
      sentido: "SUL",
      faixa: "DIREITA",
      kmInicial: 172,
      kmFinal: 171,
      status: "PENDENTE",
    });
    expect(Object.keys(propriedades).sort()).toEqual([
      "extensaoM",
      "faixa",
      "kmFinal",
      "kmInicial",
      "nome",
      "rodovia",
      "sentido",
      "status",
    ]);
  });

  it("deixa valer o comprimento da linha quando ninguém mediu em campo", () => {
    expect(propriedadesDoCadastro(cadastro(), 1480).extensaoM).toBe(1480);
  });

  it("prefere a medida de campo ao comprimento da linha", () => {
    expect(
      propriedadesDoCadastro(cadastro({ extensaoM: "1500" }), 1480).extensaoM,
    ).toBe(1500);
  });

  it("não inventa extensão quando não há linha nem medida", () => {
    expect(propriedadesDoCadastro(cadastro(), null))
      .not.toHaveProperty("extensaoM");
  });

  it("omite campo em branco em vez de gravar vazio", () => {
    const propriedades = propriedadesDoCadastro(
      cadastro({ sentido: "", faixa: "" }),
      null,
    );
    expect(propriedades).not.toHaveProperty("sentido");
    expect(propriedades).not.toHaveProperty("faixa");
  });
});

describe("nomeDoTrecho", () => {
  it("nomeia pela rodovia e pela quilometragem", () => {
    expect(nomeDoTrecho(cadastro())).toBe("SP-310 · km 172 a 171");
  });
});

describe("segmentosDoTrechoCadastrado", () => {
  const base = {
    id: "geo-1",
    validoDesde: "2026-07-28T10:00:00Z",
    procedencia: "DISPOSITIVO" as const,
  };

  it("projeta o trecho declarado como um segmento posicionável", () => {
    const [segmento] = segmentosDoTrechoCadastrado({
      ...base,
      propriedades: propriedadesDoCadastro(cadastro(), 1480),
    });

    expect(segmento).toMatchObject({
      origem: "CADASTRO_MAPA",
      sentido: "SUL",
      faixa: "DIREITA",
      kmInicial: 172,
      kmFinal: 171,
      extensaoM: 1480,
      data: "2026-07-28",
    });
  });

  it("abre 'ambas as faixas' em dois blocos, um por faixa", () => {
    const segmentos = segmentosDoTrechoCadastrado({
      ...base,
      propriedades: propriedadesDoCadastro(
        cadastro({ faixa: "AMBAS" }),
        1480,
      ),
    });

    expect(segmentos.map((item) => item.faixa)).toEqual([
      "DIREITA",
      "ESQUERDA",
    ]);
    // A extensão medida é do trecho, não de cada faixa: reparti-la inventaria
    // uma medição por faixa que ninguém fez.
    expect(segmentos.map((item) => item.extensaoM)).toEqual([1480, null]);
  });

  it("descarta o que não tem os dois extremos, que não seria desenhável", () => {
    expect(segmentosDoTrechoCadastrado({
      ...base,
      propriedades: { rodovia: "SP-310" },
    })).toEqual([]);
  });

  it("é sempre DECLARADO, nunca executado nem validado", () => {
    // Pintar declaração como produção apurada faria o esquemático afirmar
    // execução que ninguém apontou — e "validado" aqui significa receita.
    const [segmento] = segmentosDoTrechoCadastrado({
      ...base,
      procedencia: "SERVIDOR",
      propriedades: propriedadesDoCadastro(
        cadastro({ status: "CONCLUIDO" }),
        1480,
      ),
    });

    expect(estadoDoSegmento(segmento)).toBe("DECLARADO");
  });
});

function segmentoDoRdo(
  overrides: Partial<SegmentoTrecho> = {},
): SegmentoTrecho {
  return {
    id: "rdo-seg-1",
    origem: "EXECUCAO_SERVICO",
    rdoId: "rdo-1",
    numeroRdo: "RDO-0042",
    data: "2026-07-28",
    servicoNome: "Fresagem",
    subtrecho: null,
    sentido: null,
    pista: null,
    faixa: null,
    kmInicial: 171.5,
    kmFinal: 171.9,
    estacaInicial: null,
    estacaFinal: null,
    extensaoM: null,
    larguraM: null,
    areaM2: null,
    massaTonelada: null,
    status: "VALIDADA",
    rdoStatus: "ENVIADO",
    procedencia: "SERVIDOR",
    pistaInferida: false,
    ...overrides,
  };
}

describe("divergenciasComORdo", () => {
  it("não acusa nada quando o RDO não tocou aquele pedaço da pista", () => {
    expect(divergenciasComORdo(cadastro(), [
      segmentoDoRdo({ kmInicial: 200, kmFinal: 201 }),
    ])).toEqual([]);
  });

  it("avisa quando a faixa apurada difere da faixa interditada", () => {
    const divergencias = divergenciasComORdo(cadastro(), [
      segmentoDoRdo({ faixa: "ESQUERDA" }),
    ]);

    expect(divergencias).toEqual([{
      campo: "Faixa",
      cadastrado: "DIREITA",
      apurado: "ESQUERDA",
      numeroRdo: "RDO-0042",
    }]);
  });

  it("avisa quando o sentido apurado difere do declarado", () => {
    expect(divergenciasComORdo(cadastro(), [
      segmentoDoRdo({ faixa: "DIREITA", sentido: "NORTE" }),
    ])).toEqual([{
      campo: "Sentido",
      cadastrado: "SUL",
      apurado: "NORTE",
      numeroRdo: "RDO-0042",
    }]);
  });

  it("não acusa faixa quando a interdição declarou ambas", () => {
    expect(divergenciasComORdo(cadastro({ faixa: "AMBAS" }), [
      segmentoDoRdo({ faixa: "ESQUERDA" }),
    ])).toEqual([]);
  });

  it("não acusa o que o RDO não declarou", () => {
    expect(divergenciasComORdo(cadastro(), [segmentoDoRdo()])).toEqual([]);
  });

  it("ignora a programação, que é plano e não apuração", () => {
    expect(divergenciasComORdo(cadastro(), [
      segmentoDoRdo({ origem: "PROGRAMACAO", faixa: "ESQUERDA" }),
    ])).toEqual([]);
  });

  it("relata uma vez cada diferença, mesmo repetida em vários RDOs", () => {
    expect(divergenciasComORdo(cadastro(), [
      segmentoDoRdo({ id: "a", faixa: "ESQUERDA" }),
      segmentoDoRdo({ id: "b", faixa: "ESQUERDA", numeroRdo: "RDO-0043" }),
    ])).toHaveLength(1);
  });
});
