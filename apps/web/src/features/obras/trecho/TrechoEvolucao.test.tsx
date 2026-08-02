// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { TrechoEvolucao } from "./TrechoEvolucao";
import { LARGURA_DO_DIA_PX } from "./trechoEvolucao";
import type { DiaExecutado } from "./trechoGeometry";

function dia(data: string, extensaoM: number): DiaExecutado {
  return {
    data,
    totalSegmentos: 1,
    totalRdos: 1,
    extensaoM,
    massaTonelada: 0,
    kmMin: 100,
    kmMax: 101,
  };
}

const DIAS = [
  dia("2026-07-28", 300),
  dia("2026-07-30", 500),
  dia("2026-08-01", 200),
];

afterEach(cleanup);

describe("TrechoEvolucao", () => {
  it("sem dias executados, não ocupa a tela", () => {
    const { container } = render(
      <TrechoEvolucao dias={[]} diaAtivo={null} onMudarDia={() => {}} />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it("em repouso mostra a rodovia completa, ancorada no último dia", () => {
    render(
      <TrechoEvolucao dias={DIAS} diaAtivo={null} onMudarDia={() => {}} />,
    );

    expect(screen.getByRole("status")).toHaveTextContent("01/08/2026");
    expect(screen.getByText(/Rodovia completa até aqui/)).toHaveTextContent(
      "1.000 m em 3 dias · 3 RDOs",
    );
    expect(screen.getByRole("button", { name: "Ver tudo" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Dia seguinte" })).toBeDisabled();
  });

  it("com um dia sob a agulha mostra o calendário e o acumulado daquele dia", () => {
    render(
      <TrechoEvolucao
        dias={DIAS}
        diaAtivo="2026-07-30"
        onMudarDia={() => {}}
      />,
    );

    expect(screen.getByRole("status")).toHaveTextContent("30/07/2026");
    expect(screen.getByText(/Executado até este dia/)).toHaveTextContent(
      "800 m em 2 dias · 2 RDOs",
    );
    expect(screen.getByRole("slider")).toHaveAttribute(
      "aria-valuenow",
      "1",
    );
  });

  it("os botões andam dia a dia e o último volta a ser a rodovia completa", () => {
    const onMudarDia = vi.fn();
    render(
      <TrechoEvolucao
        dias={DIAS}
        diaAtivo="2026-07-30"
        onMudarDia={onMudarDia}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Dia anterior" }));
    expect(onMudarDia).toHaveBeenLastCalledWith("2026-07-28");

    fireEvent.click(screen.getByRole("button", { name: "Dia seguinte" }));
    // Chegar ao último dia é sair do recorte: rodovia completa.
    expect(onMudarDia).toHaveBeenLastCalledWith(null);
  });

  it("a rolagem do trilho muda o dia sob a agulha", async () => {
    vi.stubGlobal(
      "requestAnimationFrame",
      (tarefa: FrameRequestCallback) => {
        tarefa(0);
        return 0;
      },
    );
    try {
      const onMudarDia = vi.fn();
      render(
        <TrechoEvolucao
          dias={DIAS}
          diaAtivo={null}
          onMudarDia={onMudarDia}
        />,
      );

      const trilho = screen.getByRole("slider");
      // O repouso ancora no último dia; rolar até a primeira célula recua o
      // calendário para o primeiro dia com produção.
      trilho.scrollLeft = 0;
      fireEvent.scroll(trilho);

      expect(onMudarDia).toHaveBeenCalledWith("2026-07-28");

      trilho.scrollLeft = LARGURA_DO_DIA_PX;
      fireEvent.scroll(trilho);
      expect(onMudarDia).toHaveBeenLastCalledWith("2026-07-30");
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("as setas do teclado percorrem os dias", () => {
    const onMudarDia = vi.fn();
    render(
      <TrechoEvolucao
        dias={DIAS}
        diaAtivo="2026-07-30"
        onMudarDia={onMudarDia}
      />,
    );

    fireEvent.keyDown(screen.getByRole("slider"), { key: "ArrowLeft" });
    expect(onMudarDia).toHaveBeenLastCalledWith("2026-07-28");
    fireEvent.keyDown(screen.getByRole("slider"), { key: "ArrowRight" });
    expect(onMudarDia).toHaveBeenLastCalledWith(null);
  });
});
