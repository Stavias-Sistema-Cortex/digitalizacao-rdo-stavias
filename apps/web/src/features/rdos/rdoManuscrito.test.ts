import { describe, expect, it } from "vitest";

import { medidasDoServico } from "./rdoCalculations";
import {
  interpretarRdoManuscrito,
  type PaginaReconhecida,
  type PalavraReconhecida,
} from "./rdoManuscrito";

/*
 * O formulário de papel remontado em coordenadas, com as colunas onde elas
 * estão na folha impressa. Os valores são os do RDO de 20/07/2026 da
 * Intervias, o mesmo que chegou digitalizado.
 */
function palavra(
  texto: string,
  x: number,
  y: number,
  largura = texto.length * 8,
): PalavraReconhecida {
  return { texto, x, y, largura, altura: 14 };
}

const CABECALHO_CLIMA = [
  palavra("Bom", 250, 150),
  palavra("Chuva", 345, 150),
  palavra("Improdutivo", 440, 150),
  palavra("Pluviometria", 560, 150),
];

const CABECALHO_MAO_OBRA = [
  palavra("Cargos", 100, 280),
  palavra("Contratado", 300, 280),
  palavra("Terceiros", 430, 280),
  palavra("Cargos", 580, 280),
  palavra("Contratado", 790, 280),
  palavra("Terceiros", 920, 280),
];

const CABECALHO_EQUIPAMENTOS = [
  palavra("Equipamentos", 100, 560),
  palavra("Próprio", 300, 560),
  palavra("Locado", 420, 560),
  palavra("Prefixo", 520, 560),
  palavra("Equipamentos", 580, 560),
  palavra("Próprio", 760, 560),
  palavra("Locado", 860, 560),
  palavra("Prefixo", 960, 560),
];

function frente(): PaginaReconhecida {
  return {
    larguraPagina: 1050,
    alturaPagina: 1480,
    palavras: [
      palavra("Rodovia", 560, 80),
      palavra("SP-330", 640, 80),
      palavra("Data", 740, 80),
      palavra("20/07/2026", 800, 80),
      palavra("Hora", 860, 110),
      palavra("Inicial", 900, 110),
      palavra("18:00", 960, 110),
      palavra("Hora", 860, 130),
      palavra("Final", 900, 130),
      palavra("05:00", 960, 130),
      ...CABECALHO_CLIMA,
      palavra("Manhã", 100, 180),
      palavra("Tarde", 100, 200),
      palavra("Noite", 100, 220),
      palavra("X", 250, 220, 12),
      ...CABECALHO_MAO_OBRA,
      palavra("Engenheiro", 100, 300),
      palavra("01", 305, 300, 16),
      palavra("Motorista", 100, 320),
      palavra("07", 305, 320, 16),
      palavra("Vigia", 580, 340),
      palavra("01", 925, 340, 16),
      ...CABECALHO_EQUIPAMENTOS,
      palavra("Fresadora", 100, 600),
      palavra("01", 305, 600, 16),
      palavra("FRE004", 525, 600),
      palavra("Compressor", 580, 620),
      palavra("01", 865, 620, 16),
      palavra("LCAP008", 965, 620),
      palavra("206+822", 100, 900),
      palavra("206+685", 190, 900),
      palavra("137", 280, 900),
      palavra("3,90", 340, 900),
      palavra("0,070", 410, 900),
      palavra("SUL", 500, 900),
      palavra("-", 540, 900, 8),
      palavra("ALÇA", 560, 900),
      palavra("DE", 610, 900),
      palavra("ACESSO", 640, 900),
      palavra("/FRESAGEM", 720, 900),
      palavra("FUNCIONAL", 810, 900),
    ],
  };
}

function verso(): PaginaReconhecida {
  return {
    larguraPagina: 1050,
    alturaPagina: 1480,
    palavras: [
      palavra("Data", 740, 60),
      palavra("20/07/2026", 800, 60),
      palavra("Material", 100, 140),
      palavra("Quant.", 200, 140),
      palavra("Nota", 350, 140),
      palavra("Fiscal", 400, 140),
      palavra("SPVJG", 100, 180),
      palavra("24.040", 200, 180),
      palavra("MRM9B73", 350, 180),
      palavra("Obs.", 430, 470),
      palavra("ATIVIDADE", 480, 470),
      palavra("EXECUTADA", 570, 470),
      palavra("NA", 670, 470),
      palavra("ALÇA", 700, 470),
      palavra("Controle", 400, 580),
      palavra("Geométrico", 470, 580),
      palavra("SubTrecho", 100, 610),
      palavra("Comprimento", 230, 610),
      palavra("Largura", 350, 610),
    ],
  };
}

describe("leitura do RDO preenchido à mão", () => {
  it("lê a identificação impressa ao lado de cada rótulo", () => {
    const { draft } = interpretarRdoManuscrito([frente(), verso()]);

    expect(draft.dataRdo).toBe("2026-07-20");
    expect(draft.rodovia).toBe("SP-330");
    expect(draft.horaInicio).toBe("18:00");
    expect(draft.horaFim).toBe("05:00");
  });

  it("lê o X do clima pela coluna em que ele caiu", () => {
    const { draft } = interpretarRdoManuscrito([frente(), verso()]);

    expect(draft.condicaoNoite).toBe("BOM");
    expect(draft.condicaoManha).toBe("");
    expect(draft.condicaoTarde).toBe("");
  });

  /*
   * A coluna é o que separa o contratado do terceiro: o número é o mesmo "01"
   * dos dois lados, e só a posição na folha diz de quem ele é.
   */
  it("separa contratado de terceiro pela coluna do número", () => {
    const { draft } = interpretarRdoManuscrito([frente(), verso()]);

    expect(draft.maoObra).toEqual([
      expect.objectContaining({
        cargo: "Engenheiro",
        tipoVinculo: "PROPRIO",
        quantidade: 1,
      }),
      expect.objectContaining({
        cargo: "Motorista",
        tipoVinculo: "PROPRIO",
        quantidade: 7,
      }),
      expect.objectContaining({
        cargo: "Vigia",
        tipoVinculo: "TERCEIRIZADO",
        quantidade: 1,
      }),
    ]);
  });

  it("lê a máquina com o prefixo e o vínculo da coluna", () => {
    const { draft } = interpretarRdoManuscrito([frente(), verso()]);

    expect(draft.equipamentos).toEqual([
      expect.objectContaining({
        descricao: "Fresadora",
        prefixo: "FRE004",
        tipoVinculo: "PROPRIO",
        quantidade: 1,
      }),
      expect.objectContaining({
        descricao: "Compressor",
        prefixo: "LCAP008",
        tipoVinculo: "LOCADO",
        quantidade: 1,
      }),
    ]);
  });

  it("lê o trecho de produção e guarda a espessura em metros", () => {
    const { draft } = interpretarRdoManuscrito([frente(), verso()]);

    expect(draft.servicosExecutados).toEqual([
      expect.objectContaining({
        trechoInicial: "206,822",
        trechoFinal: "206,685",
        larguraM: 3.9,
        espessuraM: 0.07,
        pista: "SUL - ALÇA DE ACESSO",
        servicoNome: "FRESAGEM FUNCIONAL",
      }),
    ]);
  });

  /*
   * O comprimento impresso no papel não é copiado, e não precisa ser: o
   * Córtex o calcula do próprio trecho e chega nos mesmos 137 metros que a
   * pessoa escreveu à mão. Guardar os dois criaria duas versões da mesma
   * medida, livres para divergir.
   */
  it("chega ao comprimento do papel calculando pelo trecho", () => {
    const { draft } = interpretarRdoManuscrito([frente(), verso()]);

    expect(medidasDoServico(draft.servicosExecutados[0]).comprimentoM).toBe(
      137,
    );
  });

  it("lê o material com a nota fiscal do verso", () => {
    const { draft } = interpretarRdoManuscrito([frente(), verso()]);

    expect(draft.materiais).toEqual([
      expect.objectContaining({
        materialNome: "SPVJG",
        notaFiscal: "MRM9B73",
      }),
    ]);
  });

  /*
   * O controle geométrico do papel vem em branco da obra. Nenhuma linha é
   * criada por ele — o encaixe é só com o que o RDO do Córtex já tem.
   */
  it("não cria nenhuma linha de controle geométrico", () => {
    const { draft } = interpretarRdoManuscrito([frente(), verso()]);

    expect(draft.controlesGeometricos).toEqual([]);
  });

  it("avisa o que o papel não responde, em vez de preencher sozinho", () => {
    const { pendencias } = interpretarRdoManuscrito([frente(), verso()]);

    expect(pendencias).toEqual(
      expect.arrayContaining([
        expect.stringContaining("quantidade executada"),
        expect.stringContaining("milhar ou decimal"),
      ]),
    );
  });

  it("aponta a folha ilegível em vez de devolver um RDO vazio calado", () => {
    const vazia: PaginaReconhecida = {
      larguraPagina: 1050,
      alturaPagina: 1480,
      palavras: [],
    };

    const { pendencias } = interpretarRdoManuscrito([vazia]);

    expect(pendencias).toEqual(
      expect.arrayContaining([
        expect.stringContaining("data"),
        expect.stringContaining("mão de obra"),
        expect.stringContaining("produção"),
      ]),
    );
  });
});
