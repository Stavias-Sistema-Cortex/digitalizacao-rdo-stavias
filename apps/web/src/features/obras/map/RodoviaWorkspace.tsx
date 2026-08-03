import { useCallback, useEffect, useMemo, useState } from "react";

import type { CameraDaObra } from "./cameraDaObra";

import { SYNC_COMPLETED_EVENT } from "../../../lib/sync/syncEvents";
import { CampoDeExtremo } from "./CampoDeExtremo";
import { LeafletTrechoMap } from "./LeafletTrechoMap";
import {
  RASCUNHO_VAZIO,
  type ExtremoDoTrecho,
  type PontoGeografico,
  type RascunhoDoTrecho,
} from "./rascunhoDoTrecho";
import { OperationalMap } from "./OperationalMap";
import {
  alternarCategoria,
  categoriasDaColecao,
  FILTRO_VAZIO,
  filtrarColecao,
  type FiltroDoMapa,
} from "./filtrosDoMapa";
import { rotuloDaCategoria } from "./mapCategories";
import {
  CADASTRO_VAZIO,
  divergenciasComORdo,
  FAIXAS_INTERDITAVEIS,
  propriedadesDoCadastro,
  STATUS_DO_TRECHO,
  validarCadastro,
  type CadastroTrecho,
  type StatusTrechoCadastrado,
} from "../trecho/trechoCadastrado";
import type { SegmentoTrecho } from "../trecho/trechoGeometry";
import {
  buildOperationalFeatureCollection,
  comprimentoAproximadoM,
  isValidWorksiteCoordinate,
  type OperationalFeatureCollection,
  type WorksiteMapPoint,
} from "./mapGeometry";
import { carregarMapaObra, type LeituraMapaObra } from "./obraMapApi";
import {
  registrarPontoDeCampo,
  registrarTrechoDesenhado,
} from "./obraGeometriaMutations";
import {
  CapturaDeCampoError,
  lerPosicaoDeCampo,
} from "./capturaDeCampo";
import {
  buscarEnquadramentoAproximado,
  type EnderecoDaObra,
  type EnquadramentoAproximado,
} from "./enquadramentoAproximado";
import "./RodoviaWorkspace.css";

interface RodoviaWorkspaceProps {
  obra: WorksiteMapPoint;
  /** Somente Alfa pode desenhar o trecho contratual. */
  podeDesenhar: boolean;
  /**
   * Endereço cadastral, usado apenas para abrir o mapa na região certa
   * enquanto a obra não tem coordenada nem geometria.
   */
  endereco?: EnderecoDaObra;
  /**
   * Segmentos que o RDO apurou. Servem só para confrontar o trecho declarado
   * com o que foi executado no mesmo pedaço da pista; nada é sobrescrito.
   */
  segmentosDoRdo?: readonly SegmentoTrecho[];
}

type EstadoLeitura =
  | { fase: "carregando" }
  | { fase: "pronto"; leitura: LeituraMapaObra }
  | { fase: "erro"; mensagem: string };

function primeiraCoordenada(
  colecao: OperationalFeatureCollection,
): [number, number] | null {
  function visitar(valor: unknown): [number, number] | null {
    if (
      Array.isArray(valor) &&
      valor.length >= 2 &&
      typeof valor[0] === "number" &&
      typeof valor[1] === "number"
    ) {
      return [valor[0], valor[1]];
    }
    if (Array.isArray(valor)) {
      for (const filho of valor) {
        const encontrado = visitar(filho);
        if (encontrado) return encontrado;
      }
    }
    return null;
  }

  for (const feature of colecao.features) {
    const encontrado = visitar(feature.geometry.coordinates);
    if (encontrado) return encontrado;
  }
  return null;
}

function formatarInstante(valor: string | null): string {
  if (!valor) {
    return "ainda não sincronizado";
  }
  const data = new Date(valor);
  if (Number.isNaN(data.getTime())) {
    return "ainda não sincronizado";
  }
  return `${data.toLocaleDateString("pt-BR")} às ${data.toLocaleTimeString(
    "pt-BR",
    { hour: "2-digit", minute: "2-digit" },
  )}`;
}

/**
 * Mapa da rodovia em duas metades.
 *
 * À esquerda o basemap de satélite do provider configurado; à direita o Leaflet
 * sobre a malha aberta, que continua utilizável mesmo sem chave de provider. Os
 * dois leem exatamente a mesma coleção autoritativa de geometrias, e a leitura
 * se atualiza a cada rodada de sincronização.
 */
export function RodoviaWorkspace({
  obra,
  podeDesenhar,
  endereco,
  segmentosDoRdo = [],
}: RodoviaWorkspaceProps) {
  const [estado, setEstado] = useState<EstadoLeitura>({ fase: "carregando" });
  const [aviso, setAviso] = useState<string | null>(null);
  const [filtro, setFiltro] = useState<FiltroDoMapa>(FILTRO_VAZIO);
  const [capturando, setCapturando] = useState(false);
  /*
   * Extremos marcados, ainda não gravados.
   *
   * Ficam aqui, e não dentro do mapa, porque são o mesmo dado que o formulário
   * edita e que a linha desenhada representa. Antes o rascunho vivia no mapa e
   * era zerado junto com o modo de desenho: marcar o fim apagava o início da
   * tela sem que ninguém tivesse desistido dele.
   */
  const [rascunho, setRascunho] = useState<RascunhoDoTrecho>(RASCUNHO_VAZIO);
  const [marcando, setMarcando] = useState<ExtremoDoTrecho | null>(null);
  // Cada marcação no mapa remonta os campos de coordenada, que voltam a exibir
  // o que foi clicado sem desfazer o que estivesse sendo digitado antes.
  const [marcacoesNoMapa, setMarcacoesNoMapa] = useState(0);
  const [cadastro, setCadastro] = useState<CadastroTrecho>(CADASTRO_VAZIO);
  const [salvandoCadastro, setSalvandoCadastro] = useState(false);
  const [aproximado, setAproximado] =
    useState<EnquadramentoAproximado | null>(null);
  const [ciclo, setCiclo] = useState(0);
  /*
   * Travamento dos dois mapas.
   *
   * Eles mostram a mesma obra por meios diferentes, e ler os dois lado a lado
   * exigia arrastar cada um até coincidirem — trabalho manual refeito a cada
   * mudança de trecho. Ligado, mover um leva o outro junto.
   *
   * A origem viaja com o enquadramento e serve a uma coisa só: um mapa nunca
   * recebe de volta a câmera que ele mesmo publicou. Sem isso, a devolução
   * chegaria como movimento novo e o par entraria em oscilação — que é o modo
   * clássico de dois mapas espelhados travarem a tela.
   */
  const [travado, setTravado] = useState(false);
  const [camera, setCamera] = useState<
    { valor: CameraDaObra; origem: "vetorial" | "leaflet" } | null
  >(null);

  const aoMoverVetorial = useCallback((valor: CameraDaObra) => {
    setCamera({ valor, origem: "vetorial" });
  }, []);
  const aoMoverLeaflet = useCallback((valor: CameraDaObra) => {
    setCamera({ valor, origem: "leaflet" });
  }, []);

  // A rodovia do cadastro da obra abre o formulário já preenchida: quem
  // desenha o trecho está na mesma rodovia que a obra declara, e redigitar o
  // nome é onde nascem duas grafias para a mesma pista.
  const rodoviaDaObra = endereco?.rodovia?.trim() || null;

  const recarregar = useCallback(() => {
    setCiclo((anterior) => anterior + 1);
  }, []);

  // Cada rodada de sincronização automática relê as camadas autoritativas, que
  // é como o mapa se mantém atual sem canal dedicado de tempo real.
  useEffect(() => {
    window.addEventListener(SYNC_COMPLETED_EVENT, recarregar);
    return () => {
      window.removeEventListener(SYNC_COMPLETED_EVENT, recarregar);
    };
  }, [recarregar]);

  // As dependências são os campos da obra, não o objeto: a página monta um
  // literal novo a cada render, e depender da identidade dele refaria a
  // consulta e a transação no IndexedDB a cada tecla digitada na busca.
  const { id: obraId, nome: obraNome } = obra;
  const { latitude, longitude } = obra;

  useEffect(() => {
    let cancelado = false;
    carregarMapaObra({ id: obraId, nome: obraNome, latitude, longitude })
      .then((leitura) => {
        if (!cancelado) setEstado({ fase: "pronto", leitura });
      })
      .catch((motivo: unknown) => {
        if (cancelado) return;
        setEstado({
          fase: "erro",
          mensagem:
            motivo instanceof Error
              ? motivo.message
              : "Camadas geoespaciais indisponíveis.",
        });
      });
    return () => {
      cancelado = true;
    };
  }, [obraId, obraNome, latitude, longitude, ciclo]);

  const leitura = estado.fase === "pronto" ? estado.leitura : null;
  const worksite = useMemo(
    () =>
      leitura?.dados.obra ?? {
        id: obraId,
        nome: obraNome,
        latitude,
        longitude,
      },
    [leitura?.dados.obra, obraId, obraNome, latitude, longitude],
  );
  const colecaoCompleta = useMemo(
    () =>
      buildOperationalFeatureCollection(worksite, leitura?.dados.features ?? []),
    [worksite, leitura?.dados.features],
  );
  // O recorte é decidido aqui, no pai dos dois mapas, e desce pronto para
  // ambos: recortar em cada metade permitiria que elas mostrassem obras
  // diferentes lado a lado.
  const colecao = useMemo(
    () => filtrarColecao(colecaoCompleta, filtro),
    [colecaoCompleta, filtro],
  );
  const categorias = useMemo(
    () => categoriasDaColecao(colecaoCompleta),
    [colecaoCompleta],
  );
  const centro = useMemo(
    () =>
      isValidWorksiteCoordinate(worksite.latitude, worksite.longitude)
        ? ([worksite.longitude, worksite.latitude] as [number, number])
        : primeiraCoordenada(colecao),
    [worksite, colecao],
  );

  /*
   * Sem coordenada e sem geometria, o mapa abre na região que o cadastro
   * declara. É um enquadramento, não uma localização: nada disso é gravado, e
   * a obra continua marcada como não georreferenciada até que alguém desenhe o
   * trecho ou registre a posição em campo.
   */
  const precisaDeEnquadramento = estado.fase !== "carregando" && centro === null;
  const cidade = endereco?.cidade ?? null;
  const uf = endereco?.uf ?? null;
  const rodovia = endereco?.rodovia ?? null;

  useEffect(() => {
    let cancelado = false;
    // A consulta só acontece quando falta coordenada; o resultado é limpo no
    // retorno do efeito, e não por uma escrita síncrona no corpo dele.
    if (precisaDeEnquadramento) {
      buscarEnquadramentoAproximado({ cidade, uf, rodovia })
        .then((resultado) => {
          if (!cancelado) setAproximado(resultado);
        })
        .catch(() => undefined);
    }
    return () => {
      cancelado = true;
      setAproximado(null);
    };
  }, [precisaDeEnquadramento, cidade, uf, rodovia]);

  // Soma o comprimento das linhas persistidas: é a extensão que a obra
  // realmente tem desenhada, distinta da extensão medida nos RDOs.
  const extensaoTrecho = useMemo(() => {
    const total = colecao.features.reduce((acumulado, feature) => {
      const comprimento = comprimentoAproximadoM(feature.geometry);
      return comprimento === null ? acumulado : acumulado + comprimento;
    }, 0);
    return total > 0 ? total : null;
  }, [colecao.features]);

  const emCadastro =
    marcando !== null || rascunho.inicio !== null || rascunho.fim !== null;

  /**
   * Abre a marcação de um extremo.
   *
   * O cadastro é aberto junto, e não depois de fechar a linha: os campos
   * precisam estar visíveis enquanto os pontos caem, para que dê para conferir
   * a coordenada marcada e corrigi-la sem refazer o outro extremo.
   */
  const marcarExtremo = useCallback(
    (extremo: ExtremoDoTrecho) => {
      setAviso(null);
      setCadastro((atual) =>
        atual.rodovia ? atual : { ...atual, rodovia: rodoviaDaObra ?? "" },
      );
      setMarcando((atual) => (atual === extremo ? null : extremo));
    },
    [rodoviaDaObra],
  );

  /**
   * Um extremo marcado no mapa.
   *
   * Marcado o início, o próximo clique cai naturalmente no fim; marcado o fim,
   * a marcação encerra em vez de continuar armada, senão o clique seguinte
   * moveria um ponto que já estava certo.
   */
  const aoMarcarPonto = useCallback(
    (extremo: ExtremoDoTrecho, ponto: PontoGeografico) => {
      setAviso(null);
      setRascunho((atual) =>
        extremo === "INICIO"
          ? { ...atual, inicio: ponto }
          : { ...atual, fim: ponto },
      );
      setMarcacoesNoMapa((anterior) => anterior + 1);
      setMarcando(
        extremo === "INICIO" && rascunho.fim === null ? "FIM" : null,
      );
    },
    [rascunho.fim],
  );

  const alterarExtremo = useCallback(
    (extremo: ExtremoDoTrecho, ponto: PontoGeografico | null) => {
      setRascunho((atual) =>
        extremo === "INICIO"
          ? { ...atual, inicio: ponto }
          : { ...atual, fim: ponto },
      );
    },
    [],
  );

  const extensaoDaLinha = useMemo(
    () =>
      rascunho.inicio && rascunho.fim
        ? comprimentoAproximadoM({
            type: "LineString",
            coordinates: [
              [rascunho.inicio.lng, rascunho.inicio.lat],
              [rascunho.fim.lng, rascunho.fim.lat],
            ],
          })
        : null,
    [rascunho.inicio, rascunho.fim],
  );

  const divergencias = useMemo(
    () => divergenciasComORdo(cadastro, segmentosDoRdo),
    [cadastro, segmentosDoRdo],
  );

  const cancelarCadastro = useCallback(() => {
    setRascunho(RASCUNHO_VAZIO);
    setMarcando(null);
    setCadastro(CADASTRO_VAZIO);
    setAviso(null);
  }, []);

  const salvarCadastro = useCallback(async () => {
    const { inicio, fim } = rascunho;
    if (!inicio || !fim) {
      // Um extremo isolado é ponto, não trecho: sem os dois, não há linha a
      // gravar nem extensão a declarar.
      setAviso(
        "Marque o início e o fim do trecho antes de registrar. Um extremo sozinho não descreve um trecho.",
      );
      return;
    }
    const problema = validarCadastro(cadastro);
    if (problema) {
      setAviso(problema);
      return;
    }
    setSalvandoCadastro(true);
    try {
      await registrarTrechoDesenhado({
        obraId: obra.id,
        objetoId: obra.id,
        pontos: [inicio, fim],
        propriedades: propriedadesDoCadastro(cadastro, extensaoDaLinha),
      });
      setRascunho(RASCUNHO_VAZIO);
      setMarcando(null);
      setCadastro(CADASTRO_VAZIO);
      setAviso(
        "Trecho registrado neste dispositivo. Ele sobe sozinho na próxima sincronização.",
      );
      recarregar();
    } catch (motivo: unknown) {
      // O que foi digitado permanece na tela: perder o preenchimento por uma
      // falha de gravação obrigaria a remarcar a linha inteira.
      setAviso(
        motivo instanceof Error
          ? motivo.message
          : "Não foi possível registrar o trecho.",
      );
    } finally {
      setSalvandoCadastro(false);
    }
  }, [cadastro, extensaoDaLinha, obra.id, rascunho, recarregar]);

  /**
   * Registra onde a equipe está agora.
   *
   * A posição vem do sensor do aparelho e é gravada como ponto operacional
   * ligado à obra, passando pela mesma fila de saída das demais mutações: o
   * apontador registra em campo, sem rede, e a evidência sobe sozinha depois.
   */
  const aoCapturarPosicao = useCallback(async () => {
    setAviso(null);
    setCapturando(true);
    try {
      const posicao = await lerPosicaoDeCampo();
      await registrarPontoDeCampo({
        obraId,
        objetoTipo: "OBRA",
        objetoId: obraId,
        latitude: posicao.latitude,
        longitude: posicao.longitude,
        precisaoM: posicao.precisaoM,
        observadoEm: posicao.observadoEm,
      });
      setAviso(
        posicao.precisaoM === null
          ? "Posição registrada neste dispositivo."
          : `Posição registrada com ${Math.round(
              posicao.precisaoM,
            )} m de precisão. Sobe na próxima sincronização.`,
      );
      recarregar();
    } catch (motivo: unknown) {
      setAviso(
        motivo instanceof CapturaDeCampoError || motivo instanceof Error
          ? motivo.message
          : "Não foi possível registrar a posição.",
      );
    } finally {
      setCapturando(false);
    }
  }, [obraId, recarregar]);

  const instrucaoDeGeorreferencia = podeDesenhar
    ? "Use “Desenhar trecho” para marcar o início e o fim sobre a rodovia, ou “Registrar posição” para gravar onde a equipe está agora."
    : "Use “Registrar posição” para gravar onde a equipe está agora; o trecho contratual é desenhado pela administração.";

  return (
    <section className="rodovia-workspace" aria-labelledby="rodovia-workspace-title">
      <header className="rodovia-workspace-header">
        <div>
          <p className="eyebrow">Mapa da rodovia</p>
          <h3 id="rodovia-workspace-title">{worksite.nome}</h3>
          <span>
            última atualização em{" "}
            {formatarInstante(leitura?.obtidoEm ?? null)}
            {leitura?.origem === "CACHE_LOCAL"
              ? " · dados do dispositivo, sem rede"
              : ""}
          </span>
        </div>
        <div className="rodovia-workspace-acoes">
          <button
            type="button"
            className="rodovia-desenho-botao"
            disabled={capturando}
            onClick={() => {
              void aoCapturarPosicao();
            }}
          >
            {capturando ? "Lendo o GPS…" : "Registrar posição"}
          </button>
          {podeDesenhar ? (
            <button
              type="button"
              className={
                marcando
                  ? "rodovia-desenho-botao rodovia-desenho-botao--ativo"
                  : "rodovia-desenho-botao"
              }
              aria-pressed={marcando !== null}
              onClick={() => {
                if (marcando) {
                  setMarcando(null);
                  return;
                }
                // Reabrir o desenho começa do início, mas não apaga o que já
                // foi marcado: o rascunho só some por "Descartar".
                marcarExtremo("INICIO");
              }}
            >
              {marcando
                ? "Parar de marcar"
                : emCadastro
                  ? "Continuar o desenho"
                  : "Desenhar trecho"}
            </button>
          ) : null}
        </div>
      </header>

      {centro ? (
        <dl className="rodovia-workspace-ficha">
          <div>
            <dt>Coordenada da obra</dt>
            <dd>
              {centro[1].toFixed(6)}, {centro[0].toFixed(6)}
              <small>
                {isValidWorksiteCoordinate(
                  worksite.latitude,
                  worksite.longitude,
                )
                  ? " · cadastro da obra"
                  : " · derivada da geometria"}
              </small>
            </dd>
          </div>
          <div>
            <dt>Extensão georreferenciada</dt>
            <dd>
              {extensaoTrecho !== null
                ? `${new Intl.NumberFormat("pt-BR", {
                    maximumFractionDigits: 0,
                  }).format(extensaoTrecho)} m`
                : "—"}
            </dd>
          </div>
          <div>
            <dt>Camadas ativas</dt>
            <dd>{colecao.features.length}</dd>
          </div>
        </dl>
      ) : null}

      {!centro && aproximado ? (
        <p className="rodovia-workspace-aviso rodovia-workspace-aviso--aproximado">
          <strong>Obra ainda não georreferenciada.</strong> O mapa está apenas
          enquadrado em {aproximado.local}, a partir do endereço do cadastro.
          Nada disso é gravado. {instrucaoDeGeorreferencia}
        </p>
      ) : null}

      {aviso ? <p className="rodovia-workspace-aviso">{aviso}</p> : null}
      {estado.fase === "erro" ? (
        <p className="rodovia-workspace-aviso">
          {estado.mensagem} Nenhuma camada foi encontrada neste dispositivo para
          esta obra.
        </p>
      ) : null}

      {emCadastro ? (
        <form
          className="rodovia-cadastro"
          aria-label="Cadastro do trecho desenhado"
          noValidate
          onSubmit={(evento) => {
            evento.preventDefault();
            void salvarCadastro();
          }}
        >
          <header>
            <div>
              <p className="eyebrow">Trecho desenhado</p>
              <h3>Descreva o que esta linha representa</h3>
            </div>
            <span>
              {extensaoDaLinha === null
                ? "Marque os dois extremos para medir a linha"
                : `${new Intl.NumberFormat("pt-BR", {
                    maximumFractionDigits: 0,
                  }).format(extensaoDaLinha)} m desenhados`}
            </span>
          </header>

          {/* Os dois extremos ficam à vista o tempo todo. Cada um se remarca
              sozinho, sem desfazer o outro nem obrigar a recomeçar a linha. */}
          <div className="rodovia-cadastro__extremos">
            <CampoDeExtremo
              key={`INICIO:${marcacoesNoMapa}`}
              extremo="INICIO"
              valor={rascunho.inicio}
              marcando={marcando === "INICIO"}
              onAlterar={(ponto) => alterarExtremo("INICIO", ponto)}
              onMarcarNoMapa={() => marcarExtremo("INICIO")}
            />
            <CampoDeExtremo
              key={`FIM:${marcacoesNoMapa}`}
              extremo="FIM"
              valor={rascunho.fim}
              marcando={marcando === "FIM"}
              onAlterar={(ponto) => alterarExtremo("FIM", ponto)}
              onMarcarNoMapa={() => marcarExtremo("FIM")}
            />
          </div>

          <div className="rodovia-cadastro__grade">
            <label>
              Rodovia
              <input
                value={cadastro.rodovia}
                maxLength={120}
                onChange={(evento) =>
                  setCadastro((atual) => ({
                    ...atual,
                    rodovia: evento.target.value,
                  }))}
              />
            </label>
            <label>
              Sentido
              <input
                value={cadastro.sentido}
                maxLength={60}
                placeholder="Norte, Sul, Leste…"
                onChange={(evento) =>
                  setCadastro((atual) => ({
                    ...atual,
                    sentido: evento.target.value,
                  }))}
              />
            </label>
            <label>
              Faixa interditada
              <select
                value={cadastro.faixa}
                onChange={(evento) =>
                  setCadastro((atual) => ({
                    ...atual,
                    faixa: evento.target.value,
                  }))}
              >
                <option value="">Não declarada</option>
                {FAIXAS_INTERDITAVEIS.map((opcao) => (
                  <option key={opcao.valor} value={opcao.valor}>
                    {opcao.rotulo}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Km inicial
              <input
                value={cadastro.kmInicial}
                inputMode="decimal"
                placeholder="172 ou 309+400"
                onChange={(evento) =>
                  setCadastro((atual) => ({
                    ...atual,
                    kmInicial: evento.target.value,
                  }))}
              />
            </label>
            <label>
              Km final
              <input
                value={cadastro.kmFinal}
                inputMode="decimal"
                placeholder="171"
                onChange={(evento) =>
                  setCadastro((atual) => ({
                    ...atual,
                    kmFinal: evento.target.value,
                  }))}
              />
            </label>
            <label>
              Extensão medida (m)
              <input
                value={cadastro.extensaoM}
                inputMode="decimal"
                placeholder={
                  extensaoDaLinha === null
                    ? "opcional"
                    : `${Math.round(extensaoDaLinha)} pela linha`
                }
                onChange={(evento) =>
                  setCadastro((atual) => ({
                    ...atual,
                    extensaoM: evento.target.value,
                  }))}
              />
            </label>
            <label>
              Situação
              <select
                value={cadastro.status}
                onChange={(evento) =>
                  setCadastro((atual) => ({
                    ...atual,
                    status: evento.target.value as StatusTrechoCadastrado,
                  }))}
              >
                {STATUS_DO_TRECHO.map((opcao) => (
                  <option key={opcao.valor} value={opcao.valor}>
                    {opcao.rotulo}
                  </option>
                ))}
              </select>
            </label>
          </div>

          {divergencias.length > 0 ? (
            <div className="rodovia-cadastro__divergencia" role="alert">
              <strong>
                O RDO apurou outra coisa neste mesmo pedaço da pista.
              </strong>
              <ul>
                {divergencias.map((item) => (
                  <li key={`${item.campo}:${item.apurado}`}>
                    {item.campo}: você declarou <b>{item.cadastrado}</b> e o
                    {item.numeroRdo ? ` ${item.numeroRdo}` : " RDO"} registrou{" "}
                    <b>{item.apurado}</b>.
                  </li>
                ))}
              </ul>
              <small>
                Nada é corrigido automaticamente. O cadastro é o combinado e o
                RDO é o executado; salvar mantém os dois, lado a lado.
              </small>
            </div>
          ) : null}

          <footer>
            <button type="button" onClick={cancelarCadastro}>
              Descartar linha
            </button>
            <button
              type="submit"
              className="is-primary"
              disabled={salvandoCadastro}
            >
              {salvandoCadastro ? "Registrando…" : "Registrar trecho"}
            </button>
          </footer>
          <small>
            Nada é gravado enquanto o trecho não for registrado. Marcar de novo
            um extremo substitui apenas aquele ponto.
          </small>
        </form>
      ) : null}

      {categorias.length > 0 ? (
        <div className="rodovia-workspace-filtros">
          <div
            className="rodovia-workspace-filtros__camadas"
            role="group"
            aria-label="Marcações exibidas no mapa"
          >
            {categorias.map((categoria) => {
              const visivel = !filtro.categoriasOcultas.has(categoria);
              return (
                <button
                  key={categoria}
                  type="button"
                  className={visivel ? "is-ativa" : ""}
                  aria-pressed={visivel}
                  onClick={() =>
                    setFiltro((atual) => ({
                      ...atual,
                      categoriasOcultas: alternarCategoria(
                        atual.categoriasOcultas,
                        categoria,
                      ),
                    }))}
                >
                  {rotuloDaCategoria(categoria)}
                </button>
              );
            })}
          </div>
          <label className="rodovia-workspace-filtros__dia">
            <span>Ver o dia</span>
            <input
              type="date"
              value={filtro.data}
              onChange={(event) =>
                setFiltro((atual) => ({
                  ...atual,
                  data: event.target.value,
                }))}
            />
          </label>
          {filtro.data || filtro.categoriasOcultas.size > 0 ? (
            <button
              type="button"
              className="rodovia-workspace-filtros__limpar"
              onClick={() => setFiltro(FILTRO_VAZIO)}
            >
              Mostrar tudo
            </button>
          ) : null}
          <small role="status">
            {filtro.data || filtro.categoriasOcultas.size > 0
              ? `${colecao.features.length} de ${colecaoCompleta.features.length} marcação(ões)`
              : "Sem recorte: o mapa mostra todo o histórico registrado."}
          </small>
        </div>
      ) : null}

      {/*
        O rótulo não muda com o estado, e o estado vive em aria-pressed. Botão
        que troca de nome ao ser apertado obriga a ler duas vezes para saber se
        anuncia o que faz ou o que já é — e, com aria-pressed junto, o leitor de
        tela diz as duas coisas e elas se contradizem.
      */}
      <div className="rodovia-workspace-trava">
        <button
          type="button"
          className={
            travado
              ? "rodovia-desenho-botao rodovia-desenho-botao--ativo"
              : "rodovia-desenho-botao"
          }
          aria-pressed={travado}
          title="Mover um mapa leva o outro junto."
          onClick={() => setTravado((atual) => !atual)}
        >
          Travar mapas
        </button>
      </div>

      <div className="rodovia-workspace-split">
        <div className="rodovia-workspace-painel">
          <OperationalMap
            obra={worksite}
            leitura={leitura}
            filtro={filtro}
            carregando={estado.fase === "carregando"}
            erroLeitura={estado.fase === "erro" ? estado.mensagem : null}
            camera={
              travado && camera?.origem === "leaflet" ? camera.valor : null
            }
            onCamera={travado ? aoMoverVetorial : null}
          />
        </div>
        <div className="rodovia-workspace-painel">
          {centro || aproximado ? (
            <LeafletTrechoMap
              features={colecao}
              center={centro ?? (aproximado as EnquadramentoAproximado).centro}
              limitesIniciais={centro ? null : aproximado?.limites ?? null}
              rascunho={rascunho}
              marcando={podeDesenhar ? marcando : null}
              onPontoMarcado={aoMarcarPonto}
              camera={
                travado && camera?.origem === "vetorial" ? camera.valor : null
              }
              onCamera={travado ? aoMoverLeaflet : null}
            />
          ) : (
            <div className="rodovia-workspace-vazio">
              <strong>Obra ainda não georreferenciada</strong>
              <p>{instrucaoDeGeorreferencia}</p>
            </div>
          )}
        </div>
      </div>
    </section>
  );
}
