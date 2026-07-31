// @vitest-environment jsdom
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

import { TrechoEsquematico } from "./TrechoEsquematico";
import { TrechoResumo } from "./TrechoResumo";
import type { ProjecaoTrecho, SegmentoTrecho } from "./trechoGeometry";

function segmento(overrides: Partial<SegmentoTrecho> = {}): SegmentoTrecho {
  return {
    id: "ctrl-1",
    origem: "RDO_CONTROLE",
    rdoId: "rdo-1",
    numeroRdo: "RDO-1",
    data: "2026-03-02",
    servicoNome: "Recapeamento",
    subtrecho: null,
    sentido: "SUL",
    pista: "SUL",
    faixa: "1",
    kmInicial: 172,
    kmFinal: 171,
    estacaInicial: null,
    estacaFinal: null,
    extensaoM: 1500,
    larguraM: null,
    areaM2: null,
    massaTonelada: 380,
    status: "VALIDADA",
    rdoStatus: "ENVIADO",
    procedencia: "SERVIDOR",
    ...overrides,
  };
}

function projecao(overrides: Partial<ProjecaoTrecho> = {}): ProjecaoTrecho {
  const segmentos = overrides.segmentos ?? [segmento()];
  return {
    obraId: "obra-1",
    obraNome: "Recapeamento SP-310",
    rodovia: "SP-310",
    operavel: true,
    sentidos: ["SUL"],
    faixas: ["1"],
    kmMin: 171,
    kmMax: 172,
    atualizadoEm: "2026-03-03T18:30:00",
    periodoDisponivel: { de: "2026-03-02", ate: "2026-03-02" },
    periodoAplicado: { de: null, ate: null },
    diasExecutados: [
      {
        data: "2026-03-02",
        totalSegmentos: 1,
        totalRdos: 1,
        extensaoM: 1500,
        massaTonelada: 380,
        kmMin: 171,
        kmMax: 172,
      },
    ],
    resumo: {
      totalSegmentos: segmentos.length,
      totalRdos: 1,
      totalRascunhos: 0,
      totalPendentes: 0,
      extensaoTotalM: 1500,
      areaTotalM2: 0,
      massaTotalTonelada: 380,
      primeiraExecucao: "2026-03-02",
      ultimaExecucao: "2026-03-02",
    },
    ...overrides,
    segmentos,
  };
}

afterEach(cleanup);

describe("TrechoEsquematico", () => {
  it("desenha a rodovia real e o bloco do serviço executado", () => {
    render(<TrechoEsquematico projecao={projecao()} />);

    expect(
      screen.getByRole("heading", { name: "SP-310" }),
    ).toBeInTheDocument();
    expect(screen.getByText("Recapeamento (1.500 m)")).toBeInTheDocument();
    expect(screen.getByText("SUL · 1")).toBeInTheDocument();
  });

  it("posiciona o bloco pela marcação quilométrica persistida", () => {
    const { container } = render(<TrechoEsquematico projecao={projecao()} />);

    const bloco = container.querySelector<HTMLElement>(".trecho-bloco");
    expect(bloco).not.toBeNull();
    // Escala 170–173 decrescente: km 172 fica a 1/3 e o trecho ocupa 1/3.
    expect(bloco?.style.left).toBe("33.33333333333333%");
    expect(bloco?.style.width).toBe("33.33333333333333%");
    expect(bloco?.className).toContain("trecho-bloco--validado");
  });

  it("desenha o canteiro central apenas quando há dois sentidos", () => {
    const umSentido = render(<TrechoEsquematico projecao={projecao()} />);
    expect(
      umSentido.queryByText("Canteiro central divisor"),
    ).not.toBeInTheDocument();
    umSentido.unmount();

    render(
      <TrechoEsquematico
        projecao={projecao({
          segmentos: [
            segmento({ id: "a", sentido: "SUL", pista: "SUL" }),
            segmento({ id: "b", sentido: "NORTE", pista: "NORTE" }),
          ],
        })}
      />,
    );

    expect(screen.getByText("Canteiro central divisor")).toBeInTheDocument();
  });

  it("mostra estado vazio instruído quando não há km lançado", () => {
    render(
      <TrechoEsquematico
        projecao={projecao({
          segmentos: [segmento({ kmInicial: null, kmFinal: null })],
        })}
      />,
    );

    expect(
      screen.getByText("Nenhum trecho posicionável ainda"),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/1 lançamento\(s\) sem km inicial e final/),
    ).toBeInTheDocument();
  });

  it("não inventa rodovia quando a obra não declara uma", () => {
    render(<TrechoEsquematico projecao={projecao({ rodovia: null })} />);

    expect(
      screen.getByRole("heading", { name: "Rodovia não informada" }),
    ).toBeInTheDocument();
  });

  it("desenha o lançamento que só existe neste aparelho", () => {
    const { container } = render(
      <TrechoEsquematico
        projecao={projecao({
          segmentos: [
            segmento(),
            segmento({
              id: "local:rdo-9:controle:c1",
              rdoId: "rdo-9",
              numeroRdo: "RDO-9",
              data: "2026-03-09",
              kmInicial: 173,
              kmFinal: 172,
              procedencia: "DISPOSITIVO",
            }),
          ],
          resumo: {
            totalSegmentos: 2,
            totalRdos: 1,
            totalRascunhos: 0,
            totalPendentes: 1,
            extensaoTotalM: 1500,
            areaTotalM2: 0,
            massaTotalTonelada: 380,
            primeiraExecucao: "2026-03-02",
            ultimaExecucao: "2026-03-02",
          },
        })}
      />,
    );

    expect(
      container.querySelector(".trecho-bloco--pendente"),
    ).not.toBeNull();
    expect(screen.getByText("Lançado neste aparelho")).toBeInTheDocument();
    expect(
      screen.getByText(/1 lançamento\(s\) ainda só neste aparelho/),
    ).toBeInTheDocument();
  });

  it("exibe a procedência do dado quando informada", () => {
    render(
      <TrechoEsquematico
        projecao={projecao()}
        procedencia="Cache local de 02/03/2026"
      />,
    );

    expect(screen.getByText("Cache local de 02/03/2026")).toBeInTheDocument();
  });
});

describe("TrechoResumo", () => {
  it("apresenta o consolidado da obra desativada sem camada viva", () => {
    const { container } = render(
      <TrechoResumo projecao={projecao({ operavel: false })} />,
    );

    expect(screen.getByText("Obra desativada")).toBeInTheDocument();
    expect(
      screen.getByText(/Acompanhamento em tempo real desligado/),
    ).toBeInTheDocument();
    expect(screen.getByText("1.500 m")).toBeInTheDocument();
    expect(screen.getByText("380 t")).toBeInTheDocument();
    expect(container.querySelector(".trecho-bloco")).toBeNull();
    expect(container.querySelector(".trecho-pista-quadro")).toBeNull();
  });

  it("diz claramente quando a obra encerrou sem produção lançada", () => {
    render(
      <TrechoResumo
        projecao={projecao({
          operavel: false,
          segmentos: [],
          resumo: {
            totalSegmentos: 0,
            totalRdos: 0,
            totalRascunhos: 0,
            totalPendentes: 0,
            extensaoTotalM: 0,
            areaTotalM2: 0,
            massaTotalTonelada: 0,
            primeiraExecucao: null,
            ultimaExecucao: null,
          },
        })}
      />,
    );

    expect(
      screen.getByText("Nenhuma produção foi registrada nesta obra"),
    ).toBeInTheDocument();
  });

  it("não exibe medida ausente como zero", () => {
    render(
      <TrechoResumo
        projecao={projecao({
          operavel: false,
          resumo: {
            totalSegmentos: 1,
            totalRdos: 1,
            totalRascunhos: 0,
            totalPendentes: 0,
            extensaoTotalM: 1500,
            areaTotalM2: 0,
            massaTotalTonelada: 0,
            primeiraExecucao: "2026-03-02",
            ultimaExecucao: "2026-03-02",
          },
        })}
      />,
    );

    const area = screen.getByText("Área executada").parentElement;
    expect(area?.textContent).toContain("—");
  });
});
