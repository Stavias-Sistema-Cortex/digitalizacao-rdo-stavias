// @vitest-environment jsdom
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { LeituraMapaObra } from "./obraMapApi";

const carregarMapaObra = vi.hoisted(() => vi.fn());
const registrarTrechoDesenhado = vi.hoisted(() => vi.fn());
const resolverRdoDoTrecho = vi.hoisted(() => vi.fn());
const createAndPersistLocalPendingRdoDraft = vi.hoisted(() => vi.fn());
const leaflet = vi.hoisted(() => ({
  marcando: null as string | null,
  ultimoRascunho: null as {
    inicio: unknown;
    fim: unknown;
  } | null,
  marcar: null as
    | ((extremo: string, ponto: { lat: number; lng: number }) => void)
    | null,
  ultimasFeatures: { features: [] } as { features: { id: string }[] },
  espelho: undefined as unknown,
}));

vi.mock("./obraMapApi", () => ({ carregarMapaObra }));
vi.mock("./obraGeometriaMutations", () => ({ registrarTrechoDesenhado }));
vi.mock("./rdoDoTrechoDesenhado", () => ({ resolverRdoDoTrecho }));
vi.mock("../../rdos/rdoDraftCreation", () => ({
  createAndPersistLocalPendingRdoDraft,
}));
const satelite = vi.hoisted(() => ({
  ultimaLeitura: undefined as unknown,
  ultimoFiltro: undefined as unknown,
}));

vi.mock("./OperationalMap", () => ({
  OperationalMap: (props: { leitura: unknown; filtro?: unknown }) => {
    satelite.ultimaLeitura = props.leitura;
    satelite.ultimoFiltro = props.filtro;
    return <div data-testid="mapa-satelite" />;
  },
}));
vi.mock("./LeafletTrechoMap", () => ({
    LeafletTrechoMap: (props: {
      marcando?: string | null;
      rascunho?: { inicio: unknown; fim: unknown };
      features: { features: { id: string }[] };
      onPontoMarcado?: (
        extremo: string,
        ponto: { lat: number; lng: number },
      ) => void;
      onCamera?: unknown;
    }) => {
      leaflet.marcando = props.marcando ?? null;
      leaflet.ultimoRascunho = props.rascunho ?? null;
      leaflet.marcar = props.onPontoMarcado ?? null;
      leaflet.ultimasFeatures = props.features;
      leaflet.espelho = props.onCamera ?? null;
      return (
        <div
          data-testid="mapa-leaflet"
          data-marcando={props.marcando ?? "nenhum"}
        />
      );
    },
}));

const { RodoviaWorkspace } = await import("./RodoviaWorkspace");

const obra = {
  id: "obra-1",
  nome: "Recapeamento SP-310",
  latitude: -22.4394,
  longitude: -47.5672,
};

const INICIO = { lat: -22.43, lng: -47.56 };
const FIM = { lat: -22.44, lng: -47.55 };

/** Marca um extremo como o painel Leaflet marcaria, ao clique no mapa. */
function marcar(extremo: "INICIO" | "FIM", ponto: { lat: number; lng: number }) {
  act(() => {
    leaflet.marcar?.(extremo, ponto);
  });
}

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
  resolverRdoDoTrecho.mockReset();
  resolverRdoDoTrecho.mockResolvedValue({ rdoId: "rdo-1", criaRdo: false });
  createAndPersistLocalPendingRdoDraft.mockReset();
  createAndPersistLocalPendingRdoDraft.mockResolvedValue({});
  leaflet.marcando = null;
  leaflet.ultimoRascunho = null;
  leaflet.marcar = null;
  leaflet.espelho = undefined;
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

  /**
   * O rótulo do travamento não acompanha o estado: quem o lê deve saber o que o
   * clique faz, e o que já está valendo é dito por aria-pressed. Um botão que
   * troca de nome ao ser apertado anuncia as duas coisas ao mesmo tempo e elas
   * se contradizem.
   */
  it("mantém o rótulo do travamento e diz o estado por aria-pressed", async () => {
    const user = userEvent.setup();
    render(<RodoviaWorkspace obra={obra} podeDesenhar={false} />);
    await screen.findByTestId("mapa-leaflet");

    const botao = screen.getByRole("button", { name: "Travar mapas" });
    expect(botao).toHaveAttribute("aria-pressed", "false");
    // Destravado, nenhum mapa escuta o outro: sem isso o par se moveria junto
    // mesmo com o travamento desligado.
    expect(leaflet.espelho).toBeNull();

    await user.click(botao);

    expect(
      screen.getByRole("button", { name: "Travar mapas" }),
    ).toHaveAttribute("aria-pressed", "true");
    await waitFor(() => expect(leaflet.espelho).toBeTypeOf("function"));

    await user.click(screen.getByRole("button", { name: "Travar mapas" }));

    expect(
      screen.getByRole("button", { name: "Travar mapas" }),
    ).toHaveAttribute("aria-pressed", "false");
    await waitFor(() => expect(leaflet.espelho).toBeNull());
  });

  it("não oferece o desenho do trecho a quem não é Alfa", async () => {
    render(<RodoviaWorkspace obra={obra} podeDesenhar={false} />);

    await screen.findByTestId("mapa-leaflet");
    expect(
      screen.queryByRole("button", { name: /Desenhar trecho/ }),
    ).not.toBeInTheDocument();
  });

  it("mantém o início na tela enquanto o fim é marcado", async () => {
    const user = userEvent.setup();
    render(<RodoviaWorkspace obra={obra} podeDesenhar />);
    await screen.findByTestId("mapa-leaflet");

    await user.click(screen.getByRole("button", { name: /Desenhar trecho/ }));
    expect(leaflet.marcando).toBe("INICIO");

    marcar("INICIO", INICIO);

    // O marco de início sumia da tela no instante em que o fim era marcado,
    // porque o rascunho era zerado junto com o modo de desenho.
    await waitFor(() => expect(leaflet.marcando).toBe("FIM"));
    expect(leaflet.ultimoRascunho).toEqual({ inicio: INICIO, fim: null });

    marcar("FIM", FIM);

    await waitFor(() =>
      expect(leaflet.ultimoRascunho).toEqual({ inicio: INICIO, fim: FIM }),
    );
    expect(leaflet.marcando).toBeNull();
  });

  it("remarca um extremo sem desfazer o outro", async () => {
    const user = userEvent.setup();
    render(<RodoviaWorkspace obra={obra} podeDesenhar />);
    await screen.findByTestId("mapa-leaflet");

    await user.click(screen.getByRole("button", { name: /Desenhar trecho/ }));
    marcar("INICIO", INICIO);
    marcar("FIM", FIM);
    await screen.findByRole("form", { name: /Cadastro do trecho/i });

    await user.click(
      screen.getByRole("button", { name: /Remarcar o fim no mapa/i }),
    );
    const outroFim = { lat: -22.45, lng: -47.54 };
    marcar("FIM", outroFim);

    await waitFor(() =>
      expect(leaflet.ultimoRascunho).toEqual({ inicio: INICIO, fim: outroFim }),
    );
  });

  it("corrige a coordenada digitada sem apagar o extremo no meio do caminho", async () => {
    const user = userEvent.setup();
    render(<RodoviaWorkspace obra={obra} podeDesenhar />);
    await screen.findByTestId("mapa-leaflet");

    await user.click(screen.getByRole("button", { name: /Desenhar trecho/ }));
    marcar("INICIO", INICIO);
    await screen.findByRole("form", { name: /Cadastro do trecho/i });

    const latitude = screen.getByLabelText("Latitude do início");
    await user.clear(latitude);

    // Latitude vazia é meia coordenada: o ponto anterior permanece até que os
    // dois valores fechem, senão o marcador pisca fora da tela a cada tecla.
    expect(leaflet.ultimoRascunho?.inicio).toEqual(INICIO);

    await user.type(latitude, "-22.4321");
    await waitFor(() =>
      expect(leaflet.ultimoRascunho?.inicio).toEqual({
        lat: -22.4321,
        lng: -47.56,
      }),
    );
  });

  it("abre o cadastro em vez de gravar a linha crua", async () => {
    const user = userEvent.setup();
    render(<RodoviaWorkspace obra={obra} podeDesenhar />);
    await screen.findByTestId("mapa-leaflet");

    await user.click(screen.getByRole("button", { name: /Desenhar trecho/ }));
    const pontos = [INICIO, FIM];
    marcar("INICIO", INICIO);
    marcar("FIM", FIM);

    // Uma linha diz onde, não o quê: sem km ela não posiciona no esquemático
    // nem descreve nada para a ontologia, então nada é gravado ainda.
    await screen.findByRole("form", { name: /Cadastro do trecho/i });
    expect(registrarTrechoDesenhado).not.toHaveBeenCalled();

    await user.type(screen.getByLabelText("Rodovia"), "SP-310");
    await user.type(screen.getByLabelText("Km inicial"), "172");
    await user.type(screen.getByLabelText("Km final"), "171");
    await user.selectOptions(
      screen.getByLabelText("Faixa interditada"),
      "DIREITA",
    );
    await user.click(screen.getByRole("button", { name: "Registrar trecho" }));

    await waitFor(() =>
      expect(registrarTrechoDesenhado).toHaveBeenCalledWith(
        expect.objectContaining({
          obraId: "obra-1",
          pontos,
          propriedades: expect.objectContaining({
            rodovia: "SP-310",
            faixa: "DIREITA",
            kmInicial: 172,
            kmFinal: 171,
            status: "PENDENTE",
          }),
        }),
      ),
    );
    expect(
      await screen.findByText(/sobe sozinho na próxima sincronização/),
    ).toBeInTheDocument();
  });

  it("recusa o cadastro sem os dois extremos e não grava nada", async () => {
    const user = userEvent.setup();
    render(<RodoviaWorkspace obra={obra} podeDesenhar />);
    await screen.findByTestId("mapa-leaflet");

    await user.click(screen.getByRole("button", { name: /Desenhar trecho/ }));
    marcar("INICIO", INICIO);
    marcar("FIM", FIM);

    await screen.findByRole("form", { name: /Cadastro do trecho/i });
    await user.type(screen.getByLabelText("Rodovia"), "SP-310");
    await user.type(screen.getByLabelText("Km inicial"), "172");
    await user.click(screen.getByRole("button", { name: "Registrar trecho" }));

    expect(
      await screen.findByText(/km inicial e o km final/i),
    ).toBeInTheDocument();
    expect(registrarTrechoDesenhado).not.toHaveBeenCalled();
  });

  it("avisa quando o RDO apurou outra faixa no mesmo pedaço da pista", async () => {
    const user = userEvent.setup();
    render(
      <RodoviaWorkspace
        obra={obra}
        podeDesenhar
        segmentosDoRdo={[{
          id: "seg-1",
          origem: "EXECUCAO_SERVICO",
          rdoId: "rdo-1",
          numeroRdo: "RDO-0042",
          data: "2026-07-28",
          servicoNome: "Fresagem",
          subtrecho: null,
          sentido: null,
          pista: null,
          faixa: "ESQUERDA",
          kmInicial: 171.4,
          kmFinal: 171.8,
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
        }]}
      />,
    );
    await screen.findByTestId("mapa-leaflet");

    await user.click(screen.getByRole("button", { name: /Desenhar trecho/ }));
    marcar("INICIO", INICIO);
    marcar("FIM", FIM);

    await screen.findByRole("form", { name: /Cadastro do trecho/i });
    await user.type(screen.getByLabelText("Km inicial"), "172");
    await user.type(screen.getByLabelText("Km final"), "171");
    await user.selectOptions(
      screen.getByLabelText("Faixa interditada"),
      "DIREITA",
    );

    // O aviso é informativo: nem o cadastro nem o RDO são corrigidos.
    const alerta = await screen.findByRole("alert");
    expect(alerta).toHaveTextContent(/RDO-0042/);
    expect(alerta).toHaveTextContent(/ESQUERDA/);
    expect(
      screen.getByRole("button", { name: "Registrar trecho" }),
    ).toBeEnabled();
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


  it("recorta as marcações e o dia nas duas metades ao mesmo tempo", async () => {
    const geo = (
      id: string,
      categoria: string,
      validoDesde: string,
      validoAte: string | null,
    ) => ({
      id,
      categoria,
      objetoTipo: "OBRA",
      objetoId: obra.id,
      geometry: { type: "Point" as const, coordinates: [-47.56, -22.43] },
      properties: {},
      fonte: "GESTAO_MAPA",
      versao: 1,
      validoDesde,
      validoAte,
    });
    carregarMapaObra.mockResolvedValue(leitura({
      dados: {
        obra,
        features: [
          geo("t1", "TRECHO", "2026-07-20T10:00:00Z", null),
          geo("p1", "PONTO_OPERACIONAL", "2026-07-28T08:00:00Z", null),
        ],
      },
    }));

    render(<RodoviaWorkspace obra={obra} podeDesenhar={false} />);
    await screen.findByTestId("mapa-leaflet");
    await waitFor(() =>
      expect(leaflet.ultimasFeatures.features).toHaveLength(3),
    );

    fireEvent.click(screen.getByRole("button", { name: "Ponto operacional" }));

    await waitFor(() =>
      expect(
        leaflet.ultimasFeatures.features.map((item) => item.id),
      ).toEqual(["obra-1:localizacao", "t1"]),
    );
    // O painel satélite recorta com o mesmo filtro, senão as duas metades
    // mostrariam obras diferentes lado a lado.
    expect(satelite.ultimoFiltro).toMatchObject({
      categoriasOcultas: new Set(["PONTO_OPERACIONAL"]),
    });

    fireEvent.click(screen.getByRole("button", { name: /mostrar tudo/i }));
    await waitFor(() =>
      expect(leaflet.ultimasFeatures.features).toHaveLength(3),
    );

    // Só o dia, sem recorte de camada: o ponto de 28/07 ainda não existia em
    // 21/07 e precisa sumir por vigência, não por categoria.
    fireEvent.change(screen.getByLabelText("Ver o dia"), {
      target: { value: "2026-07-21" },
    });

    await waitFor(() =>
      expect(
        leaflet.ultimasFeatures.features.map((item) => item.id),
      ).toEqual(["obra-1:localizacao", "t1"]),
    );
  });

  it("preserva o rascunho e o preenchimento quando a persistência falha", async () => {
    registrarTrechoDesenhado.mockRejectedValue(
      new Error("Não foi possível gravar a mutação."),
    );
    const user = userEvent.setup();

    render(<RodoviaWorkspace obra={obra} podeDesenhar />);
    await screen.findByTestId("mapa-leaflet");

    await user.click(screen.getByRole("button", { name: "Desenhar trecho" }));
    expect(leaflet.marcando).toBe("INICIO");

    marcar("INICIO", { lat: -22.4394, lng: -47.5672 });
    marcar("FIM", { lat: -22.4501, lng: -47.5588 });

    await screen.findByRole("form", { name: /Cadastro do trecho/i });
    await user.type(screen.getByLabelText("Rodovia"), "SP-310");
    await user.type(screen.getByLabelText("Km inicial"), "172");
    await user.type(screen.getByLabelText("Km final"), "171");
    await user.click(screen.getByRole("button", { name: "Registrar trecho" }));

    // A falha aparece e o rascunho continua inteiro: perder o que foi marcado
    // e digitado obrigaria a refazer a linha por causa da gravação.
    await screen.findByText(/Não foi possível gravar a mutação\./);
    expect(leaflet.ultimoRascunho).toEqual({
      inicio: { lat: -22.4394, lng: -47.5672 },
      fim: { lat: -22.4501, lng: -47.5588 },
    });
    expect(screen.getByLabelText("Rodovia")).toHaveValue("SP-310");
    expect(
      screen.getByRole("button", { name: "Registrar trecho" }),
    ).toBeEnabled();
  });
});
