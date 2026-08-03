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
  providers: [] as { label: string; embutido?: boolean }[],
  falhasDeBasemap: [] as (() => void)[],
}));

/**
 * A sonda decide se o estilo remoto entra; aqui ela é controlada pelo teste.
 * Sem isto, cada caso dependeria de a máquina de teste alcançar a internet —
 * exatamente a fragilidade que este painel existe para deixar de ter.
 */
const sonda = vi.hoisted(() => ({
  motivo: null as string | null,
  segurar: false,
  liberar: null as null | (() => void),
  consultas: [] as (string | null)[],
}));

vi.mock("./sondaDeEstilo", () => ({
  sondarEstilo: (styleUrl: string | null) => {
    sonda.consultas.push(styleUrl);
    const resultado = () =>
      sonda.motivo === null
        ? { usavel: true, motivo: null }
        : { usavel: false, motivo: sonda.motivo };
    if (!sonda.segurar) {
      return Promise.resolve(resultado());
    }
    return new Promise((resolver) => {
      sonda.liberar = () => resolver(resultado());
    });
  },
}));

vi.mock("./mapAdapter", () => ({
  mountOperationalMap: (opcoes: {
    features: OperationalFeatureCollection;
    mode: string;
    provider: { label: string; embutido?: boolean };
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
  sonda.motivo = null;
  sonda.segurar = false;
  sonda.liberar = null;
  sonda.consultas = [];
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

  it("não monta nada antes de a sonda responder", async () => {
    // A montagem às cegas é o que produzia o retângulo mudo: o mapa subia
    // sobre um estilo que a rede não entrega e se declarava pronto.
    sonda.segurar = true;

    render(
      <OperationalMap
        obra={obra}
        leitura={leitura(1, 1)}
        carregando={false}
        erroLeitura={null}
      />,
    );

    expect(screen.getByText(/verificando o servidor de mapas/i)).toBeTruthy();
    expect(adaptador.montagens).toBe(0);

    sonda.liberar?.();
    await waitFor(() => expect(adaptador.montagens).toBe(1));
  });

  it("nem tenta o estilo remoto que a sonda reprovou", async () => {
    // O caso de campo: o host do estilo não é entregue por esta rede. O painel
    // abre direto no basemap embutido — o mesmo host que a metade Leaflet já
    // prova funcionar — em vez de gastar a montagem num estilo mudo.
    sonda.motivo = "o estilo do mapa não respondeu a tempo";

    render(
      <OperationalMap
        obra={obra}
        leitura={leitura(1, 1)}
        carregando={false}
        erroLeitura={null}
      />,
    );

    await waitFor(() => expect(adaptador.montagens).toBe(1));
    expect(adaptador.providers[0].embutido).toBe(true);
    expect(adaptador.providers[0].label).toContain("base");
    await waitFor(() =>
      expect(
        screen.getByText(/não respondeu a tempo.*mapa base/i),
      ).toBeInTheDocument(),
    );
  });

  it("cai para o basemap embutido quando o mapa sobe e não pinta", async () => {
    // O estilo respondeu à sonda e mesmo assim nada foi desenhado — tiles
    // bloqueados depois do estilo. O vigia do adaptador acusa e o painel troca.
    render(
      <OperationalMap
        obra={obra}
        leitura={leitura(1, 1)}
        carregando={false}
        erroLeitura={null}
      />,
    );

    await waitFor(() => expect(adaptador.montagens).toBe(1));
    expect(adaptador.providers[0].embutido).toBeUndefined();

    adaptador.falhasDeBasemap[0]();

    await waitFor(() => expect(adaptador.montagens).toBe(2));
    expect(adaptador.providers[1].embutido).toBe(true);
    expect(adaptador.providers[1].label).toContain("base");
    await waitFor(() =>
      expect(screen.getByText(/exibindo o mapa base/i)).toBeInTheDocument(),
    );
  });

  it("o próprio embutido falhando não entra em laço de remontagem", async () => {
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

    // Segunda falha, agora do embutido: nada de terceira montagem.
    adaptador.falhasDeBasemap[1]();
    await new Promise((resolver) => setTimeout(resolver, 50));
    expect(adaptador.montagens).toBe(2);
  });
});
