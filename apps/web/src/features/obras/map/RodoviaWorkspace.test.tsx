// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { LeituraMapaObra } from "./obraMapApi";

const carregarMapaObra = vi.hoisted(() => vi.fn());
const registrarTrechoDesenhado = vi.hoisted(() => vi.fn());
const leaflet = vi.hoisted(() => ({
  ultimoModo: "INATIVO" as string,
  desenhar: null as ((pontos: unknown[]) => void) | null,
}));

vi.mock("./obraMapApi", () => ({ carregarMapaObra }));
vi.mock("./obraGeometriaMutations", () => ({ registrarTrechoDesenhado }));
const satelite = vi.hoisted(() => ({
  ultimaLeitura: undefined as unknown,
}));

vi.mock("./OperationalMap", () => ({
  OperationalMap: (props: { leitura: unknown }) => {
    satelite.ultimaLeitura = props.leitura;
    return <div data-testid="mapa-satelite" />;
  },
}));
vi.mock("./LeafletTrechoMap", () => ({
  LeafletTrechoMap: (props: {
    modo: string;
    onTrechoDesenhado?: (pontos: unknown[]) => void;
  }) => {
    leaflet.ultimoModo = props.modo;
    leaflet.desenhar = props.onTrechoDesenhado ?? null;
    return <div data-testid="mapa-leaflet" data-modo={props.modo} />;
  },
}));

const { RodoviaWorkspace } = await import("./RodoviaWorkspace");

const obra = {
  id: "obra-1",
  nome: "Recapeamento SP-310",
  latitude: -22.4394,
  longitude: -47.5672,
};

function leitura(overrides: Partial<LeituraMapaObra> = {}): LeituraMapaObra {
  return {
    dados: { obra, features: [] },
    origem: "REDE",
    obtidoEm: "2026-03-03T18:30:00.000Z",
    ...overrides,
  };
}

beforeEach(() => {
  carregarMapaObra.mockReset();
  registrarTrechoDesenhado.mockReset();
  carregarMapaObra.mockResolvedValue(leitura());
  registrarTrechoDesenhado.mockResolvedValue({ id: "geo-1" });
  leaflet.ultimoModo = "INATIVO";
  leaflet.desenhar = null;
  satelite.ultimaLeitura = undefined;
});

afterEach(cleanup);

describe("RodoviaWorkspace", () => {
  it("mostra os dois painéis lado a lado quando há coordenada", async () => {
    render(<RodoviaWorkspace obra={obra} podeDesenhar={false} />);

    await screen.findByTestId("mapa-leaflet");
    expect(screen.getByTestId("mapa-satelite")).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "Recapeamento SP-310" }),
    ).toBeInTheDocument();
  });

  it("declara a data da última atualização confirmada", async () => {
    render(<RodoviaWorkspace obra={obra} podeDesenhar={false} />);

    await screen.findByTestId("mapa-leaflet");
    expect(
      screen.getByText(/última atualização em 03\/03\/2026/),
    ).toBeInTheDocument();
  });

  it("avisa quando está exibindo o que já tinha no dispositivo", async () => {
    carregarMapaObra.mockResolvedValue(
      leitura({ origem: "CACHE_LOCAL" }),
    );
    render(<RodoviaWorkspace obra={obra} podeDesenhar={false} />);

    await screen.findByTestId("mapa-leaflet");
    expect(
      screen.getByText(/dados do dispositivo, sem rede/),
    ).toBeInTheDocument();
  });

  it("não oferece o desenho do trecho a quem não é Alfa", async () => {
    render(<RodoviaWorkspace obra={obra} podeDesenhar={false} />);

    await screen.findByTestId("mapa-leaflet");
    expect(
      screen.queryByRole("button", { name: /Desenhar trecho/ }),
    ).not.toBeInTheDocument();
  });

  it("alterna o modo de desenho e enfileira o trecho marcado", async () => {
    const user = userEvent.setup();
    render(<RodoviaWorkspace obra={obra} podeDesenhar />);
    await screen.findByTestId("mapa-leaflet");

    await user.click(screen.getByRole("button", { name: /Desenhar trecho/ }));
    expect(leaflet.ultimoModo).toBe("TRECHO");

    const pontos = [
      { lat: -22.43, lng: -47.56 },
      { lat: -22.44, lng: -47.55 },
    ];
    await waitFor(() => expect(leaflet.desenhar).not.toBeNull());
    leaflet.desenhar?.(pontos);

    await waitFor(() =>
      expect(registrarTrechoDesenhado).toHaveBeenCalledWith(
        expect.objectContaining({ obraId: "obra-1", pontos }),
      ),
    );
    expect(
      await screen.findByText(/sobe sozinho na próxima sincronização/),
    ).toBeInTheDocument();
  });

  it("declara a obra sem georreferência em vez de centrar num ponto inventado", async () => {
    const semCoordenada = { ...obra, latitude: null, longitude: null };
    carregarMapaObra.mockResolvedValue(
      leitura({ dados: { obra: semCoordenada, features: [] } }),
    );

    render(<RodoviaWorkspace obra={semCoordenada} podeDesenhar={false} />);

    expect(
      await screen.findByText("Obra ainda não georreferenciada"),
    ).toBeInTheDocument();
    expect(screen.queryByTestId("mapa-leaflet")).not.toBeInTheDocument();
  });

  it("informa quando não há camada alguma para a obra", async () => {
    carregarMapaObra.mockRejectedValue(new Error("Rede indisponível."));

    render(<RodoviaWorkspace obra={obra} podeDesenhar={false} />);

    expect(
      await screen.findByText(/Nenhuma camada foi encontrada neste dispositivo/),
    ).toBeInTheDocument();
  });

  it("relê as camadas a cada rodada de sincronização", async () => {
    render(<RodoviaWorkspace obra={obra} podeDesenhar={false} />);
    await screen.findByTestId("mapa-leaflet");
    expect(carregarMapaObra).toHaveBeenCalledTimes(1);

    window.dispatchEvent(new Event("cortex:sync-completed"));

    await waitFor(() => expect(carregarMapaObra).toHaveBeenCalledTimes(2));
  });

  it("alimenta o painel satélite com a mesma leitura do painel Leaflet", async () => {
    const compartilhada = leitura({ origem: "CACHE_LOCAL" });
    carregarMapaObra.mockResolvedValue(compartilhada);

    render(<RodoviaWorkspace obra={obra} podeDesenhar={false} />);
    await screen.findByTestId("mapa-leaflet");

    // Uma única busca serve os dois painéis: offline ou após um desenho
    // local, as duas metades continuam contando a mesma história.
    await waitFor(() =>
      expect(satelite.ultimaLeitura).toBe(compartilhada),
    );
    expect(carregarMapaObra).toHaveBeenCalledTimes(1);
  });

  it("desliga o desenho e zera o rascunho quando a persistência falha", async () => {
    registrarTrechoDesenhado.mockRejectedValue(
      new Error("Não foi possível gravar a mutação."),
    );
    const user = userEvent.setup();

    render(<RodoviaWorkspace obra={obra} podeDesenhar />);
    await screen.findByTestId("mapa-leaflet");

    await user.click(screen.getByRole("button", { name: "Desenhar trecho" }));
    expect(leaflet.ultimoModo).toBe("TRECHO");

    leaflet.desenhar?.([
      { lat: -22.4394, lng: -47.5672 },
      { lat: -22.4501, lng: -47.5588 },
    ]);

    // A falha aparece, o modo sai de desenho — o que apaga os marcadores de
    // rascunho no mapa — e o botão volta ao estado inicial para nova tentativa.
    await screen.findByText(/Não foi possível gravar a mutação\./);
    await waitFor(() => expect(leaflet.ultimoModo).toBe("INATIVO"));
    expect(
      screen.getByRole("button", { name: "Desenhar trecho" }),
    ).toBeInTheDocument();
  });
});
