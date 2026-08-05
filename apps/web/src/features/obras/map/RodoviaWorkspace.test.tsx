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
const encerrarGeometria = vi.hoisted(() => vi.fn());
const resolverRdoDoTrecho = vi.hoisted(() => vi.fn());
const createAndPersistLocalPendingRdoDraft = vi.hoisted(() => vi.fn());
const getLocalRdo = vi.hoisted(() => vi.fn());
const rdoDraftFromLocalRecord = vi.hoisted(() => vi.fn());
const saveExistingRdoDraftAtomically = vi.hoisted(() => vi.fn());
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
  removerPonto: null as ((id: string) => void) | null,
}));

/** A lixeira do balão do ponto, como o mapa a acionaria ao clique. */
function clicarNaLixeira(id: string) {
  act(() => {
    leaflet.removerPonto?.(id);
  });
}

/*
 * A lixeira do ponto só existe para o Alfa — o servidor recusa o encerramento
 * de qualquer outro perfil. Os testes de remoção falam do Alfa; o do Beta
 * troca a sessão para provar que o botão nem aparece.
 */
const sessao = vi.hoisted(() => ({
  papelAcesso: "ALFA" as "ALFA" | "BETA",
}));

vi.mock("../../auth/authSession", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../auth/authSession")>()),
  getSession: () => ({
    colaboradorId: "10000000-0000-4000-8000-000000000001",
    papelAcesso: sessao.papelAcesso,
    escopoGlobal: sessao.papelAcesso === "ALFA",
    obraIds: [] as string[],
    expiraEm: "2099-01-01T00:00:00.000Z",
  }),
  isAlfa: (perfil: { papelAcesso?: string } | null) =>
    perfil?.papelAcesso === "ALFA",
}));

vi.mock("./obraMapApi", () => ({ carregarMapaObra }));
vi.mock("./obraGeometriaMutations", () => ({
  encerrarGeometria,
  registrarTrechoDesenhado,
}));
vi.mock("./rdoDoTrechoDesenhado", () => ({ resolverRdoDoTrecho }));
vi.mock("../../rdos/rdoDraftCreation", () => ({
  createAndPersistLocalPendingRdoDraft,
}));
vi.mock("../../../lib/db/rdoRepository", () => ({ getLocalRdo }));
vi.mock("../../../lib/db/localRdoService", () => ({
  rdoDraftFromLocalRecord,
  saveExistingRdoDraftAtomically,
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
      onRemoverPonto?: ((id: string) => void) | null;
    }) => {
      leaflet.marcando = props.marcando ?? null;
      leaflet.ultimoRascunho = props.rascunho ?? null;
      leaflet.marcar = props.onPontoMarcado ?? null;
      leaflet.ultimasFeatures = props.features;
      leaflet.espelho = props.onCamera ?? null;
      leaflet.removerPonto = props.onRemoverPonto ?? null;
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
  sessao.papelAcesso = "ALFA";
  carregarMapaObra.mockReset();
  registrarTrechoDesenhado.mockReset();
  encerrarGeometria.mockReset();
  encerrarGeometria.mockResolvedValue({ id: "ponto-1" });
  carregarMapaObra.mockResolvedValue(leitura());
  registrarTrechoDesenhado.mockResolvedValue({ id: "geo-1" });
  resolverRdoDoTrecho.mockReset();
  resolverRdoDoTrecho.mockResolvedValue({ rdoId: "rdo-1", criaRdo: false });
  createAndPersistLocalPendingRdoDraft.mockReset();
  createAndPersistLocalPendingRdoDraft.mockResolvedValue({
    draft: { id: "rdo-1", servicosExecutados: [] },
  });
  getLocalRdo.mockReset();
  getLocalRdo.mockResolvedValue({ id: "rdo-1" });
  rdoDraftFromLocalRecord.mockReset();
  rdoDraftFromLocalRecord.mockReturnValue({
    id: "rdo-1",
    servicosExecutados: [],
  });
  saveExistingRdoDraftAtomically.mockReset();
  saveExistingRdoDraftAtomically.mockResolvedValue({});
  leaflet.marcando = null;
  leaflet.ultimoRascunho = null;
  leaflet.marcar = null;
  leaflet.espelho = undefined;
  satelite.ultimaLeitura = undefined;
});

afterEach(cleanup);

function pontoOperacional(id: string) {
  return {
    id,
    categoria: "PONTO_OPERACIONAL",
    objetoTipo: "OBRA",
    objetoId: "obra-1",
    geometry: { type: "Point" as const, coordinates: [-47.4, -22.5] },
    properties: { observadoEm: "2026-08-04T12:00:00.000Z" },
    fonte: "CAPTURA_CAMPO",
    versao: 1,
    validoDesde: "2026-08-04T12:00:00.000Z",
    validoAte: null,
  };
}

describe("RodoviaWorkspace", () => {
  /**
   * encerrarGeometria já existia inteiro e não tinha por onde ser chamado.
   * Uma marcação feita no lugar errado ficava no mapa para sempre — e o mapa é
   * lido como evidência, então ponto que ninguém tira vira afirmação que
   * ninguém desmente.
   */
  it("encerra o ponto escolhido no mapa depois de confirmar", async () => {
    const user = userEvent.setup();
    carregarMapaObra.mockResolvedValue(
      leitura({
        dados: {
          obra,
          features: [pontoOperacional("ponto-1"), pontoOperacional("ponto-2")],
        },
      }),
    );
    render(<RodoviaWorkspace obra={obra} podeDesenhar />);
    await screen.findByTestId("mapa-leaflet");

    clicarNaLixeira("ponto-2");
    await user.click(
      await screen.findByRole("button", { name: "Remover" }),
    );

    await waitFor(() =>
      expect(encerrarGeometria).toHaveBeenCalledWith(
        "ponto-2",
        expect.any(String),
        expect.objectContaining({ id: "ponto-2", obraId: "obra-1" }),
      ),
    );
  });

  /**
   * O encerramento é gravado no dispositivo e sobe depois; até subir, o
   * servidor continua respondendo o ponto como ativo. Deixar a marcação
   * desenhada até a fila andar é o que fazia a remoção parecer que não tinha
   * funcionado — e os dois painéis leem a mesma leitura, então precisam
   * perdê-la juntos.
   */
  it("tira o ponto dos dois mapas na hora, sem esperar a fila subir", async () => {
    const user = userEvent.setup();
    carregarMapaObra.mockResolvedValue(
      leitura({
        dados: {
          obra,
          features: [pontoOperacional("ponto-1"), pontoOperacional("ponto-2")],
        },
      }),
    );
    render(<RodoviaWorkspace obra={obra} podeDesenhar />);
    await screen.findByTestId("mapa-leaflet");

    clicarNaLixeira("ponto-2");
    await user.click(await screen.findByRole("button", { name: "Remover" }));

    await waitFor(() =>
      expect(
        leaflet.ultimasFeatures.features.map((feature) => feature.id),
      ).not.toContain("ponto-2"),
    );
    const vetorial = satelite.ultimaLeitura as {
      dados: { features: { id: string }[] };
    };
    expect(vetorial.dados.features.map((feature) => feature.id)).toEqual([
      "ponto-1",
    ]);
  });

  /**
   * A recusa precisa aparecer onde a mão está. Ela ia para o aviso do topo da
   * página, que numa obra com conteúdo fica longe da vista — a remoção que
   * falhou era indistinguível de uma que simplesmente não fez nada, e é
   * exatamente assim que ela foi relatada.
   */
  it("mostra a recusa dentro da pergunta, sem fechá-la", async () => {
    encerrarGeometria.mockRejectedValue(
      new Error("Este desenho ainda não subiu para o servidor."),
    );
    const user = userEvent.setup();
    carregarMapaObra.mockResolvedValue(
      leitura({ dados: { obra, features: [pontoOperacional("ponto-1")] } }),
    );
    render(<RodoviaWorkspace obra={obra} podeDesenhar />);
    await screen.findByTestId("mapa-leaflet");

    clicarNaLixeira("ponto-1");
    await user.click(await screen.findByRole("button", { name: "Remover" }));

    expect(
      await screen.findByText(/ainda não subiu para o servidor/),
    ).toBeInTheDocument();
    // A pergunta continua aberta, oferecendo a segunda tentativa.
    expect(
      screen.getByRole("button", { name: "Tentar de novo" }),
    ).toBeInTheDocument();
    // E o ponto continua no mapa, porque nada foi removido de verdade.
    expect(
      leaflet.ultimasFeatures.features.map((feature) => feature.id),
    ).toContain("ponto-1");
  });

  /** Desistir tem que ser tão fácil quanto pedir. */
  it("não remove nada quando a confirmação é cancelada", async () => {
    const user = userEvent.setup();
    carregarMapaObra.mockResolvedValue(
      leitura({ dados: { obra, features: [pontoOperacional("ponto-1")] } }),
    );
    render(<RodoviaWorkspace obra={obra} podeDesenhar />);
    await screen.findByTestId("mapa-leaflet");

    clicarNaLixeira("ponto-1");
    await user.click(await screen.findByRole("button", { name: "Cancelar" }));

    expect(encerrarGeometria).not.toHaveBeenCalled();
    expect(
      screen.queryByRole("button", { name: "Remover" }),
    ).not.toBeInTheDocument();
  });

  /**
   * Todo ponto operacional se chama "Ponto operacional". Uma lista deles
   * repetia o mesmo rótulo dezenas de vezes e não dizia qual era qual — quem
   * escolhe a marcação errada precisa apontar para ela no mapa, e nada de
   * encerramento aparece antes disso.
   */
  it("não põe na tela uma lista de pontos para escolher", async () => {
    carregarMapaObra.mockResolvedValue(
      leitura({
        dados: {
          obra,
          features: [pontoOperacional("ponto-1"), pontoOperacional("ponto-2")],
        },
      }),
    );
    render(<RodoviaWorkspace obra={obra} podeDesenhar />);
    await screen.findByTestId("mapa-leaflet");

    expect(screen.queryByText("Pontos operacionais")).not.toBeInTheDocument();
    expect(
      screen.queryByLabelText("Motivo do encerramento"),
    ).not.toBeInTheDocument();
  });

  /**
   * Encerrar continua sendo registro, e registro sem porquê não explica nada
   * a quem ler depois. O que saiu foi a exigência de digitar o motivo: o
   * porquê é sempre o mesmo — alguém revisou o mapa e tirou dali uma marcação
   * — e cobrá-lo por escrito era atrito onde não ajudava ninguém.
   */
  it("grava um motivo mesmo sem pedir que alguém o digite", async () => {
    const user = userEvent.setup();
    carregarMapaObra.mockResolvedValue(
      leitura({ dados: { obra, features: [pontoOperacional("ponto-1")] } }),
    );
    render(<RodoviaWorkspace obra={obra} podeDesenhar />);
    await screen.findByTestId("mapa-leaflet");

    clicarNaLixeira("ponto-1");
    expect(
      screen.queryByLabelText("Motivo do encerramento"),
    ).not.toBeInTheDocument();
    await user.click(await screen.findByRole("button", { name: "Remover" }));

    await waitFor(() => expect(encerrarGeometria).toHaveBeenCalled());
    const motivo = encerrarGeometria.mock.calls.at(-1)?.[1] as string;
    expect(motivo.trim().length).toBeGreaterThan(0);
  });

  /**
   * `ObraMapaService.encerrar` começa com `requireAlfa()`, então o pedido de
   * quem não é Alfa volta 403 — recusa terminal, que a fila não reenvia. O
   * ponto sumia da tela pela máscara otimista e voltava na primeira releitura,
   * para sempre, sem nada explicando por quê. Sem lixeira não há promessa.
   */
  it("não oferece a lixeira a quem não é Alfa", async () => {
    sessao.papelAcesso = "BETA";
    carregarMapaObra.mockResolvedValue(
      leitura({ dados: { obra, features: [pontoOperacional("ponto-1")] } }),
    );
    render(<RodoviaWorkspace obra={obra} podeDesenhar />);
    await screen.findByTestId("mapa-leaflet");

    expect(leaflet.removerPonto).toBeFalsy();
  });

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

    // O quilômetro passou a morar na linha de execução do RDO, e só lá. Antes
    // ele existia também nas propriedades da geometria, sem nada que
    // reconciliasse os dois: corrigir num lado deixava o outro mentindo.
    await waitFor(() =>
      expect(saveExistingRdoDraftAtomically).toHaveBeenCalledWith(
        expect.objectContaining({
          id: "rdo-1",
          servicosExecutados: [
            expect.objectContaining({
              trechoInicial: "172",
              trechoFinal: "171",
              faixa: "DIREITA",
              localizacao: "SP-310",
              statusValidacao: "REGISTRADA",
            }),
          ],
        }),
      ),
    );
    expect(registrarTrechoDesenhado).toHaveBeenCalledWith(
      expect.objectContaining({
        obraId: "obra-1",
        rdoId: "rdo-1",
        pontos,
        propriedades: expect.objectContaining({
          rodovia: "SP-310",
          faixa: "DIREITA",
          status: "PENDENTE",
        }),
      }),
    );
    const desenhada = registrarTrechoDesenhado.mock.calls.at(-1)?.[0];
    expect(desenhada.propriedades).not.toHaveProperty("kmInicial");
    expect(desenhada.propriedades).not.toHaveProperty("kmFinal");
    expect(
      await screen.findByText(/sobe sozinho na próxima sincronização/),
    ).toBeInTheDocument();
  });

  /**
   * Gravar a linha no RDO e falhar ao gravar a geometria deixa o apontamento
   * já lançado. Se salvar de novo acrescentasse outra linha, o mesmo trecho
   * seria declarado duas vezes — e duas declarações do mesmo trabalho descem
   * para o Financeiro como se fossem dois.
   */
  it("não duplica a linha do RDO quando o salvamento é repetido após falhar", async () => {
    registrarTrechoDesenhado.mockRejectedValueOnce(
      new Error("Não foi possível gravar a mutação."),
    );
    const user = userEvent.setup();
    render(<RodoviaWorkspace obra={obra} podeDesenhar />);
    await screen.findByTestId("mapa-leaflet");

    await user.click(screen.getByRole("button", { name: /Desenhar trecho/ }));
    marcar("INICIO", INICIO);
    marcar("FIM", FIM);
    await screen.findByRole("form", { name: /Cadastro do trecho/i });
    await user.type(screen.getByLabelText("Rodovia"), "SP-310");
    await user.type(screen.getByLabelText("Km inicial"), "172");
    await user.type(screen.getByLabelText("Km final"), "171");

    await user.click(screen.getByRole("button", { name: "Registrar trecho" }));
    await screen.findByText(/Não foi possível gravar a mutação/);

    const primeira = saveExistingRdoDraftAtomically.mock.calls.at(-1)?.[0];
    expect(primeira.servicosExecutados).toHaveLength(1);
    // A segunda tentativa reencontra o RDO já com a linha lançada.
    rdoDraftFromLocalRecord.mockReturnValue({
      id: "rdo-1",
      servicosExecutados: primeira.servicosExecutados,
    });

    await user.click(screen.getByRole("button", { name: "Registrar trecho" }));
    await screen.findByText(/sobe sozinho na próxima sincronização/);

    const segunda = saveExistingRdoDraftAtomically.mock.calls.at(-1)?.[0];
    expect(segunda.servicosExecutados).toHaveLength(1);
    expect(segunda.servicosExecutados[0].localId).toBe(
      primeira.servicosExecutados[0].localId,
    );
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
