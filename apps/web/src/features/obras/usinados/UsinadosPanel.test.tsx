// @vitest-environment jsdom

import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { ObraUsinados } from "./usinadosApi";
import { UsinadosPanel } from "./UsinadosPanel";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

function usinados(partes: Partial<ObraUsinados> = {}): ObraUsinados {
  return {
    obraId: "obra-1",
    obraNome: "Obra Teste",
    precosVisiveis: true,
    materiais: [{
      material: "CBUQ Faixa C",
      unidade: "T",
      quantidadePrevista: 150,
      quantidadeUsinada: 150,
      quantidadeAplicada: 135,
      quantidadeSobra: 15,
      quantidadeNaoAplicada: 15,
      totalRdos: 2,
      primeiraData: "2026-08-18",
      ultimaData: "2026-08-19",
      precoUnitario: 850,
      precoMotivo: null,
      valorAplicado: 114750,
      valorDesperdicado: 12750,
    }],
    totais: {
      valorAplicado: 114750,
      valorDesperdicado: 12750,
      materiaisSemPreco: 0,
    },
    ...partes,
  };
}

describe("UsinadosPanel", () => {
  it("mostra a tabela com quantidades, dinheiro e o percentual de sobra", () => {
    render(
      <UsinadosPanel usinados={usinados()} loading={false} error={null} />,
    );

    expect(
      screen.getByRole("rowheader", { name: /CBUQ Faixa C/ }),
    ).toBeTruthy();
    expect(screen.getByText("Valor desperdiçado")).toBeTruthy();
    // Na linha do material e de novo no rodapé de totais.
    expect(screen.getAllByText(/R\$\s*12\.750,00/)).toHaveLength(2);
    // 15 de 150 usinado: 10% da usinagem virou sobra.
    expect(screen.getByText("10%")).toBeTruthy();
    expect(
      screen.getByRole("button", { name: "Exportar CSV" }),
    ).toBeTruthy();
  });

  it("sem financeiro visível esconde as colunas de dinheiro e explica", () => {
    render(
      <UsinadosPanel
        usinados={usinados({ precosVisiveis: false, totais: null })}
        loading={false}
        error={null}
      />,
    );

    expect(screen.queryByText("Valor aplicado")).toBeNull();
    expect(
      screen.getByText(/não tem o financeiro da obra liberado/),
    ).toBeTruthy();
  });

  it("linha sem preço diz o motivo em vez de fingir zero", () => {
    const dados = usinados();
    dados.materiais[0] = {
      ...dados.materiais[0],
      precoUnitario: null,
      precoMotivo: "PRECO_AMBIGUO",
      valorAplicado: null,
      valorDesperdicado: null,
    };
    render(<UsinadosPanel usinados={dados} loading={false} error={null} />);

    expect(screen.getByText("mais de um preço vigente")).toBeTruthy();
  });

  it("vazio, carregando e erro têm cada um a própria frase", () => {
    const { rerender } = render(
      <UsinadosPanel usinados={null} loading error={null} />,
    );
    expect(screen.getByText(/Consultando os RDOs/)).toBeTruthy();

    rerender(
      <UsinadosPanel usinados={null} loading={false} error="Sem rede." />,
    );
    expect(screen.getByText("Sem rede.")).toBeTruthy();

    rerender(
      <UsinadosPanel
        usinados={usinados({ materiais: [] })}
        loading={false}
        error={null}
      />,
    );
    expect(
      screen.getByText(/Nenhum material usinado declarado/),
    ).toBeTruthy();
    // Sem linha não há o que exportar.
    expect(screen.queryByRole("button", { name: "Exportar CSV" })).toBeNull();
  });

  it("o botão exporta o CSV como download", async () => {
    const createObjectURL = vi.fn(() => "blob:usinados");
    const revokeObjectURL = vi.fn();
    vi.stubGlobal("URL", {
      ...URL,
      createObjectURL,
      revokeObjectURL,
    });
    const click = vi
      .spyOn(HTMLAnchorElement.prototype, "click")
      .mockImplementation(() => undefined);

    render(
      <UsinadosPanel usinados={usinados()} loading={false} error={null} />,
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Exportar CSV" }),
    );

    expect(createObjectURL).toHaveBeenCalledTimes(1);
    expect(click).toHaveBeenCalledTimes(1);
    expect(revokeObjectURL).toHaveBeenCalledWith("blob:usinados");
    vi.unstubAllGlobals();
  });
});
