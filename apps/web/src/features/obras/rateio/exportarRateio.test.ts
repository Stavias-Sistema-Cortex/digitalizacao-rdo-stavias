import { describe, expect, it } from "vitest";

import {
  montarCsvDoRateio,
  nomeDoArquivoDoRateio,
} from "./exportarRateio";
import {
  apurarRateio,
  type ApontamentoDeMaoDeObra,
} from "./rateioDeMaoDeObra";

function apontamento(
  parcial: Partial<ApontamentoDeMaoDeObra> & { data: string; obraId: string },
): ApontamentoDeMaoDeObra {
  return {
    colaboradorId: "col-1",
    nome: "PESSOA UM",
    funcao: "AJUDANTE",
    encarregado: "FRENTE A",
    rdoId: `rdo-${parcial.data}`,
    ...parcial,
  };
}

const DIAS = ["2026-07-01", "2026-07-02", "2026-07-03"];

describe("a planilha do rateio", () => {
  it("escreve cabeçalho, dias e colunas de obra", () => {
    const rateio = apurarRateio([
      apontamento({ data: "2026-07-01", obraId: "obra-a" }),
      apontamento({ data: "2026-07-02", obraId: "obra-a" }),
      apontamento({ data: "2026-07-03", obraId: "obra-b" }),
    ]);

    const csv = montarCsvDoRateio(rateio, DIAS, [
      { id: "obra-a", nome: "Obra Norte" },
      { id: "obra-b", nome: "Obra Sul" },
    ]);
    const [cabecalho, linha] = csv.trim().split("\r\n");

    expect(cabecalho).toContain("Mão de obra;Função;Encarregado;Dias apontados");
    expect(cabecalho).toContain("01/07;02/07;03/07");
    expect(cabecalho).toContain("Obra Norte");
    expect(cabecalho.endsWith("Total")).toBe(true);

    expect(linha).toContain("PESSOA UM;AJUDANTE;FRENTE A;3");
    expect(linha).toContain("Obra Norte;Obra Norte;Obra Sul");
    // Duas datas numa obra e uma na outra: 0,666667 e 0,333333.
    expect(linha).toContain("0,666667");
    expect(linha).toContain("0,333333");
    expect(linha.endsWith("1,000000")).toBe(true);
  });

  it("usa vírgula decimal e ponto e vírgula de separador, como o Excel daqui", () => {
    const rateio = apurarRateio([
      apontamento({ data: "2026-07-01", obraId: "obra-a" }),
    ]);
    const csv = montarCsvDoRateio(rateio, ["2026-07-01"], [
      { id: "obra-a", nome: "Obra" },
    ]);

    expect(csv).toContain("1,000000");
    expect(csv).not.toContain("1.000000");
  });

  it("começa com a marca que faz os acentos aparecerem", () => {
    const csv = montarCsvDoRateio(apurarRateio([]), [], []);
    expect(csv.charCodeAt(0)).toBe(0xfeff);
  });

  /*
   * Nome de obra com ponto e vírgula quebraria a linha em duas colunas e a
   * planilha chegaria torta do outro lado, sem erro nenhum.
   */
  it("protege o nome que traz ponto e vírgula ou aspas", () => {
    const rateio = apurarRateio([
      apontamento({ data: "2026-07-01", obraId: "obra-a" }),
    ]);

    const csv = montarCsvDoRateio(rateio, ["2026-07-01"], [
      { id: "obra-a", nome: 'Lote 3; trecho "novo"' },
    ]);

    expect(csv).toContain('"Lote 3; trecho ""novo"""');
  });

  it("escreve as duas obras na célula do dia dividido", () => {
    const rateio = apurarRateio([
      apontamento({ data: "2026-07-01", obraId: "obra-a" }),
      apontamento({ data: "2026-07-01", obraId: "obra-b" }),
    ]);

    const csv = montarCsvDoRateio(rateio, ["2026-07-01"], [
      { id: "obra-a", nome: "Norte" },
      { id: "obra-b", nome: "Sul" },
    ]);

    expect(csv).toContain("Norte / Sul");
  });

  it("deixa vazia a célula do dia sem apontamento", () => {
    const rateio = apurarRateio([
      apontamento({ data: "2026-07-02", obraId: "obra-a" }),
    ]);

    const csv = montarCsvDoRateio(rateio, DIAS, [
      { id: "obra-a", nome: "Norte" },
    ]);
    const linha = csv.trim().split("\r\n")[1];

    expect(linha).toContain("PESSOA UM;AJUDANTE;FRENTE A;1;;Norte;;");
  });

  it("cai no identificador quando o nome da obra não é conhecido", () => {
    const rateio = apurarRateio([
      apontamento({ data: "2026-07-01", obraId: "obra-sem-nome" }),
    ]);

    const csv = montarCsvDoRateio(rateio, ["2026-07-01"], []);
    expect(csv).toContain("obra-sem-nome");
  });

  it("nomeia o arquivo pelo período", () => {
    expect(nomeDoArquivoDoRateio("2026-07-01", "2026-07-31")).toBe(
      "rateio-mao-de-obra-2026-07-01-a-2026-07-31.csv",
    );
  });
});
