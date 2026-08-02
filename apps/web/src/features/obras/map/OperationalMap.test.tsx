// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { LeituraMapaObra } from "./obraMapApi";
import type { OperationalFeatureCollection } from "./mapGeometry";

const adaptador = vi.hoisted(() => ({
  montagens: 0,
  colecoes: [] as OperationalFeatureCollection[],
  destruicoes: 0,
  modos: [] as string[],
  providers: [] as { label: string; reserva?: boolean }[],
  falhasDeBasemap: [] as (() => void)[],
}));

vi.mock("./mapAdapter", () => ({
  mountOperationalMap: (opcoes: {
    features: OperationalFeatureCollection;
    mode: string;
    provider: { label: string; reserva?: boolean };
    onFalhaDeBasemap?: () => void;
  }) => {
    adaptador.montagens += 1;
    adaptador.colecoes.push(opcoes.features);
    adaptador.modos.push(opcoes.mode);
    adaptador.providers.push(opcoes.provider);
    if (opcoes.onFalhaDeBasemap) {
      adaptador.falhasDeBasemap.push(opcoes.onFalhaDeBasemap);
    }
    return Promise.resolve({
      centerOn: () => undefined,
      setViewMode: (mode: string) => adaptador.modos.push(mode),
      setFeatures: (features: OperationalFeatureCollection) =>
        adaptador.colecoes.push(features),
      destroy: () => {
        adaptador.destruicoes += 1;
      },
    });
  },
}));

const { OperationalMap } = await import("./OperationalMap");

const obra = {
  id: "obra-1",
  nome: "Recapeamento SP-310",
  latitude: -22.4394,
  longitude: -47.5672,
};

function leitura(versao: number, quantidade: number): LeituraMapaObra {
  return {
    dados: {
      obra,
      features: Array.from({ length: quantidade }, (_, indice) => ({
        id: `geometria-${indice}`,
        categoria: "TRECHO",
        objetoTipo: "TRECHO",
        objetoId: `geometria-${indice}`,
        geometry: {
          type: "LineString" as const,
          coordinates: [
            [-47.567, -22.439],
            [-47.563, -22.444],
          ],
        },
        properties: {},
        fonte: "GESTAO_MAPA",
        versao,
        validoDesde: "2026-03-01T00:00:00",
        validoAte: null,
      })),
    },
    origem: "REDE",
    obtidoEm: "2026-03-01T12:00:00",
  };
}

beforeEach(() => {
  adaptador.montagens = 0;
  adaptador.colecoes = [];
  adaptador.destruicoes = 0;
  adaptador.modos = [];
  adaptador.providers = [];
  adaptador.falhasDeBasemap = [];
});

afterEach(cleanup);

describe("OperationalMap", () => {
  it("atualiza as geometrias sem reconstruir o renderizador", async () => {
    // Cada rodada de sincronização traz uma leitura nova. Remontar o mapa a
    // cada uma o fazia recomeçar antes de terminar de abrir, e o painel ficava
    // permanentemente em branco em campo.
    const { rerender } = render(
      <OperationalMap
        obra={obra}
        leitura={leitura(1, 1)}
        carregando={false}
        erroLeitura={null}
      />,
    );

    await waitFor(() => expect(adaptador.montagens).toBe(1));

    rerender(
      <OperationalMap
        obra={obra}
        leitura={leitura(2, 3)}
        carregando={false}
        erroLeitura={null}
      />,
    );

    await waitFor(() =>
      expect(adaptador.colecoes.at(-1)?.features).toHaveLength(4),
    );
    expect(adaptador.montagens).toBe(1);
    expect(adaptador.destruicoes).toBe(0);
  });

  it("não reconstrói o renderizador quando a leitura ainda está carregando", async () => {
    const { rerender } = render(
      <OperationalMap
        obra={obra}
        leitura={null}
        carregando
        erroLeitura={null}
      />,
    );

    await waitFor(() => expect(adaptador.montagens).toBe(1));

    rerender(
      <OperationalMap
        obra={obra}
        leitura={leitura(1, 2)}
        carregando={false}
        erroLeitura={null}
      />,
    );

    await waitFor(() =>
      expect(adaptador.colecoes.at(-1)?.features).toHaveLength(3),
    );
    expect(adaptador.montagens).toBe(1);
  });

  it("monta um mapa novo ao trocar de obra", async () => {
    const outra = { ...obra, id: "obra-2", nome: "Duplicação SP-330" };
    const { rerender } = render(
      <OperationalMap
        obra={obra}
        leitura={leitura(1, 1)}
        carregando={false}
        erroLeitura={null}
      />,
    );
    await waitFor(() => expect(adaptador.montagens).toBe(1));

    rerender(
      <OperationalMap
        obra={outra}
        leitura={null}
        carregando={false}
        erroLeitura={null}
      />,
    );

    // Outra localidade pede outro enquadramento: aqui o mapa novo é o correto.
    await waitFor(() => expect(adaptador.montagens).toBe(2));
    expect(adaptador.destruicoes).toBe(1);
  });

  it("descreve a obra sem coordenada nem geometria em vez de abrir o mapa", () => {
    render(
      <OperationalMap
        obra={{ ...obra, latitude: null, longitude: null }}
        leitura={null}
        carregando={false}
        erroLeitura={null}
      />,
    );

    expect(
      screen.getByText("Localização ainda não registrada"),
    ).toBeTruthy();
    expect(adaptador.montagens).toBe(0);
  });

  it("cai sozinho para o basemap reserva quando a fonte do principal não vem", async () => {
    // O retângulo mudo de campo: a fonte de tiles falha, o renderizador
    // declara tudo carregado e nada pinta. O painel deve trocar para o OSM
    // reserva — o mesmo host que a metade Leaflet já prova funcionar — e
    // dizer o que aconteceu, em vez de ficar vazio.
    render(
      <OperationalMap
        obra={obra}
        leitura={leitura(1, 1)}
        carregando={false}
        erroLeitura={null}
      />,
    );

    await waitFor(() => expect(adaptador.montagens).toBe(1));
    expect(adaptador.providers[0].reserva).toBeUndefined();

    adaptador.falhasDeBasemap[0]();

    await waitFor(() => expect(adaptador.montagens).toBe(2));
    expect(adaptador.providers[1].reserva).toBe(true);
    expect(adaptador.providers[1].label).toContain("reserva");
    await waitFor(() =>
      expect(
        screen.getByText(/exibindo o mapa reserva/i),
      ).toBeInTheDocument(),
    );
  });

  it("a própria reserva falhando não entra em laço de remontagem", async () => {
    render(
      <OperationalMap
        obra={obra}
        leitura={leitura(1, 1)}
        carregando={false}
        erroLeitura={null}
      />,
    );

    await waitFor(() => expect(adaptador.montagens).toBe(1));
    adaptador.falhasDeBasemap[0]();
    await waitFor(() => expect(adaptador.montagens).toBe(2));

    // Segunda falha, agora da reserva: nada de terceira montagem.
    adaptador.falhasDeBasemap[1]();
    await new Promise((resolver) => setTimeout(resolver, 50));
    expect(adaptador.montagens).toBe(2);
  });
});
