import { describe, expect, it } from "vitest";

import { lerRespostaDoRateio } from "./rateioApi";

describe("a leitura da resposta do servidor", () => {
  it("transforma os RDOs do servidor nos mesmos apontamentos da leitura local", () => {
    const leitura = lerRespostaDoRateio({
      inicio: "2026-07-01",
      fim: "2026-07-31",
      completo: true,
      rdos: [
        {
          id: "rdo-1",
          obraId: "obra-a",
          dataRdo: "2026-07-10",
          numeroRdo: "RDO-0001",
          obraNome: "Obra do Servidor",
          encarregadoObra: "FRENTE A",
          apontadorRdo: "QUEM ASSINA",
          maoObra: [
            {
              colaboradorId: "col-1",
              nomeColaborador: "PESSOA UM",
              cargo: "AJUDANTE",
            },
          ],
        },
      ],
    });

    expect(leitura.rdosLidos).toBe(1);
    expect(leitura.completo).toBe(true);
    expect(leitura.apontamentos).toEqual([
      {
        colaboradorId: "col-1",
        nome: "PESSOA UM",
        funcao: "AJUDANTE",
        obraId: "obra-a",
        obraNome: "Obra do Servidor",
        data: "2026-07-10",
        encarregado: "FRENTE A",
        rdoId: "rdo-1",
      },
    ]);
  });

  /*
   * A mesma cascata do caminho local, porque é literalmente a mesma função: o
   * servidor não decide quem é o encarregado, só entrega os campos.
   */
  it("acha o encarregado pelo cargo quando o campo não foi digitado", () => {
    const leitura = lerRespostaDoRateio({
      rdos: [
        {
          id: "rdo-1",
          obraId: "obra-a",
          dataRdo: "2026-07-10",
          encarregadoObra: null,
          apontadorRdo: "QUEM ASSINA",
          maoObra: [
            {
              colaboradorId: "col-9",
              nomeColaborador: "QUEM MANDA",
              cargo: "ENCARREGADO GERAL DE OBRAS",
            },
          ],
        },
      ],
    });

    expect(leitura.apontamentos[0].encarregado).toBe("QUEM MANDA");
  });

  it("o servidor nunca tem cabeçalho sem conteúdo", () => {
    const leitura = lerRespostaDoRateio({
      rdos: [
        {
          id: "rdo-1",
          obraId: "obra-a",
          dataRdo: "2026-07-10",
          maoObra: [],
        },
      ],
    });

    expect(leitura.rdosSemConteudo).toBe(0);
    expect(leitura.rdosLidos).toBe(1);
    expect(leitura.rdosSemMaoDeObra).toBe(1);
  });

  it("marca o retrato como parcial quando o servidor avisa", () => {
    expect(lerRespostaDoRateio({ rdos: [], completo: false }).completo).toBe(
      false,
    );
  });

  it("não quebra com corpo estranho, nem com RDO sem identificação", () => {
    expect(lerRespostaDoRateio(null).apontamentos).toHaveLength(0);
    expect(lerRespostaDoRateio("bom dia").apontamentos).toHaveLength(0);
    expect(lerRespostaDoRateio({}).apontamentos).toHaveLength(0);
    expect(
      lerRespostaDoRateio({
        rdos: [{ id: "", obraId: "obra", dataRdo: "2026-07-01" }, null],
      }).apontamentos,
    ).toHaveLength(0);
  });
});
