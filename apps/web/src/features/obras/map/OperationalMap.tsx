import { useEffect, useMemo, useRef, useState } from "react";

import {
  buildOperationalFeatureCollection,
  isValidWorksiteCoordinate,
  type OperationalFeatureCollection,
  type WorksiteMapPoint,
} from "./mapGeometry";
import {
  mountOperationalMap,
  type MapViewMode,
  type OperationalMapController,
} from "./mapAdapter";
import type { CameraDaObra } from "./cameraDaObra";
import type { LeituraMapaObra } from "./obraMapApi";
import { descartarCacheDeMapa } from "./cacheDeTiles";
import {
  FILTRO_VAZIO,
  filtrarColecao,
  type FiltroDoMapa,
} from "./filtrosDoMapa";
import {
  availableMapProviders,
  providerRasterOsm,
  resolveMapProvider,
  resolveMapProviderForId,
  type MapProvider,
  type MapProviderId,
} from "./mapProvider";
import { sondarEstilo } from "./sondaDeEstilo";
import { corDaCategoria, corDoToken, rotuloDaCategoria } from "./mapCategories";
import {
  ROTULO_POR_FASE,
  TOKEN_POR_FASE,
  fasesPresentes,
} from "./execucaoDoTrecho";
import "./OperationalMap.css";
import "./balaoDoMapa.css";

interface OperationalMapProps {
  obra: WorksiteMapPoint;
  /**
   * Leitura geoespacial do workspace, cache-through e assinada a cada rodada
   * de sincronização. Os dois painéis do mapa dividido leem exatamente o mesmo
   * dado: este componente não faz nenhuma busca própria, senão a metade
   * satélite discordaria da metade Leaflet offline e após um desenho local.
   */
  leitura: LeituraMapaObra | null;
  carregando: boolean;
  /** Falha da leitura compartilhada, já explicada no aviso do workspace. */
  erroLeitura: string | null;
  /**
   * Enquadramento imposto pelo outro mapa enquanto o travamento está ligado.
   * Nulo quando destravado, que é como cada metade volta a andar sozinha.
   */
  camera?: CameraDaObra | null;
  /** Publica o enquadramento desta metade; nulo destravado. */
  onCamera?: ((camera: CameraDaObra) => void) | null;
  /**
   * Recorte decidido pelo workspace. Chega pronto para que esta metade e a
   * metade Leaflet nunca mostrem obras diferentes lado a lado.
   */
  filtro?: FiltroDoMapa;
  /**
   * Pedido de remoção do ponto operacional aberto no balão deste painel.
   *
   * <p>Os dois mapas leem a mesma geometria, então apagar num apaga no outro:
   * é a mesma feição, e quem está com o mapa vetorial aberto não deveria
   * precisar trocar de painel para tirar uma marcação errada da tela.
   */
  onRemoverPonto?: ((id: string) => void) | null;
}

function firstCoordinate(
  collection: OperationalFeatureCollection,
): [number, number] | null {
  function visit(value: unknown): [number, number] | null {
    if (
      Array.isArray(value) &&
      value.length >= 2 &&
      typeof value[0] === "number" &&
      typeof value[1] === "number"
    ) {
      return [value[0], value[1]];
    }
    if (Array.isArray(value)) {
      for (const child of value) {
        const found = visit(child);
        if (found) return found;
      }
    }
    return null;
  }

  for (const feature of collection.features) {
    const found = visit(feature.geometry.coordinates);
    if (found) return found;
  }
  return null;
}

function MapCanvas({
  provider,
  features,
  center,
  mode,
  centerRequest,
  camera,
  onCamera,
  onRemoverPonto,
}: {
  provider: MapProvider;
  features: OperationalFeatureCollection;
  center: [number, number];
  mode: MapViewMode;
  centerRequest: number;
  camera: CameraDaObra | null;
  onCamera: ((camera: CameraDaObra) => void) | null;
  onRemoverPonto: ((id: string) => void) | null;
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  const controllerRef = useRef<OperationalMapController | null>(null);
  const [status, setStatus] = useState<"loading" | "ready" | "error">(
    "loading",
  );
  const [error, setError] = useState<string | null>(null);
  const [tentativa, setTentativa] = useState(0);
  const [limpando, setLimpando] = useState(false);
  /**
   * Basemap embutido, acionado quando o estilo remoto não tem como desenhar.
   *
   * Chega por dois caminhos. A sonda reprova o estilo antes da montagem, e o
   * vigia do adaptador reprova depois, se o mapa subir e o canvas ficar de uma
   * cor só. Em qualquer um dos dois o operador vê o mapa base na hora, com um
   * aviso — não um vazio sem explicação.
   */
  const [embutido, setEmbutido] = useState(false);
  const [providerAnterior, setProviderAnterior] = useState(provider);
  if (provider !== providerAnterior) {
    // Troca explícita de provider zera a contingência: a escolha é de quem
    // opera, e o novo provider merece a primeira tentativa limpa.
    setProviderAnterior(provider);
    setEmbutido(false);
  }

  /*
   * A sonda roda antes de qualquer montagem.
   *
   * É a inversão que faltava: em vez de montar sobre um estilo remoto e depois
   * tentar adivinhar, pelos eventos do renderizador, se ele desenhou, pergunta
   * -se à rede se o estilo responde. Só entra na tela o que tem como pintar; o
   * resto cede lugar ao basemap embutido, que não depende de servidor nenhum.
   *
   * O resultado carrega a chave do que foi sondado. Assim ele expira sozinho
   * quando o estilo ou a tentativa mudam, sem um segundo estado a zerar — e a
   * espera nunca é confundida com a aprovação do estilo anterior.
   */
  const chaveDaSonda = `${provider.styleUrl ?? "embutido"}#${tentativa}`;
  const [sondado, setSondado] = useState<{
    chave: string;
    motivo: string | null;
  } | null>(null);
  useEffect(() => {
    let cancelada = false;
    void sondarEstilo(provider.styleUrl).then((resultado) => {
      if (cancelada) return;
      setSondado({
        chave: chaveDaSonda,
        motivo: resultado.usavel ? null : resultado.motivo,
      });
    });
    return () => {
      cancelada = true;
    };
  }, [chaveDaSonda, provider.styleUrl]);

  const sondaConcluida = sondado?.chave === chaveDaSonda;
  const motivoDaSonda = sondaConcluida ? sondado.motivo : null;
  const usarEmbutido = embutido || Boolean(motivoDaSonda);
  const providerEfetivo = useMemo(
    () => (usarEmbutido ? providerRasterOsm() : provider),
    [usarEmbutido, provider],
  );

  /*
   * O renderizador é criado uma vez e depois atualizado.
   *
   * Geometria, centro e modo de câmera mudam a cada leitura sincronizada;
   * tê-los como dependência da montagem derrubava e reconstruía o mapa inteiro
   * a cada rodada de sincronização, e em campo ele nunca chegava a terminar de
   * abrir. Os valores correntes ficam em referências para que a montagem use o
   * mais recente sem depender da identidade deles.
   */
  const featuresRef = useRef(features);
  const centerRef = useRef(center);
  const modeRef = useRef(mode);
  const aoRemoverRef = useRef(onRemoverPonto);
  useEffect(() => {
    featuresRef.current = features;
    centerRef.current = center;
    modeRef.current = mode;
    aoRemoverRef.current = onRemoverPonto;
  });

  /**
   * Nova tentativa depois de descartar o que o service worker guardou.
   *
   * Um tile gravado quebrado é servido de novo a cada recarga, porque
   * `CacheFirst` responde antes da rede. Sem apagar o cache, insistir no mesmo
   * botão repetiria exatamente a mesma falha.
   */
  const tentarDeNovo = async () => {
    setLimpando(true);
    try {
      await descartarCacheDeMapa();
    } finally {
      setLimpando(false);
      setStatus("loading");
      setError(null);
      // Cache limpo, o estilo remoto merece nova sonda antes do embutido.
      setEmbutido(false);
      setTentativa((anterior) => anterior + 1);
    }
  };

  useEffect(() => {
    const container = containerRef.current;
    // Montar antes da sonda seria montar às cegas, que é exatamente o que
    // deixava o painel mudo. O overlay de carregamento cobre esta espera.
    if (!container || !sondaConcluida) return;
    let cancelled = false;
    // Decidida a troca de basemap, o mapa que sai ainda emite erros até ser
    // desmontado. Publicá-los empilharia um aviso do mapa antigo ao lado do
    // aviso do novo, sobre a tela do operador.
    let trocando = false;
    let controller: OperationalMapController | null = null;

    mountOperationalMap({
      container,
      provider: providerEfetivo,
      features: featuresRef.current,
      center: centerRef.current,
      mode: modeRef.current,
      // O mapa é montado uma vez; o balão precisa chamar o destino corrente,
      // e não o que valia na montagem.
      onRemoverPonto: (id) => aoRemoverRef.current?.(id),
      onRuntimeError: (message) => {
        if (!cancelled && !trocando) setError(message);
      },
      onFalhaDeBasemap: () => {
        // O embutido só se aciona uma vez; se ele próprio não pintar, o aviso
        // de erro comum assume, em vez de um laço de remontagens.
        if (!cancelled && !providerEfetivo.embutido) {
          trocando = true;
          setError(null);
          setEmbutido(true);
        }
      },
    })
      .then((mounted) => {
        if (cancelled) {
          mounted.destroy();
          return;
        }
        controller = mounted;
        controllerRef.current = mounted;
        setStatus("ready");
      })
      .catch((reason: unknown) => {
        if (!cancelled) {
          setStatus("error");
          setError(
            reason instanceof Error
              ? reason.message
              : "Não foi possível carregar o provider de mapa.",
          );
        }
      });

    return () => {
      cancelled = true;
      controllerRef.current = null;
      controller?.destroy();
    };
  }, [providerEfetivo, sondaConcluida, tentativa]);

  // As geometrias novas entram na fonte que já está no mapa. `status` participa
  // porque uma leitura pode chegar enquanto o mapa ainda abre: sem ele, a
  // atualização seria perdida no controlador que ainda não existia.
  useEffect(() => {
    controllerRef.current?.setFeatures(features);
  }, [features, status]);

  useEffect(() => {
    controllerRef.current?.setViewMode(mode);
  }, [mode, status]);

  useEffect(() => {
    if (centerRequest > 0) {
      const [longitude, latitude] = centerRef.current;
      controllerRef.current?.centerOn(longitude, latitude);
    }
  }, [centerRequest]);


  /*
   * Publica o enquadramento desta tela enquanto o travamento estiver ligado.
   *
   * A inscrição é refeita quando `onCamera` troca — inclusive para nulo, que é
   * como o travamento desligado chega aqui. Sem desinscrever, o mapa
   * continuaria empurrando o outro depois de destravado.
   */
  useEffect(() => {
    if (!onCamera || status !== "ready") {
      return undefined;
    }
    return controllerRef.current?.onCameraChange(onCamera);
  }, [onCamera, status]);

  useEffect(() => {
    if (!camera || status !== "ready") {
      return;
    }
    controllerRef.current?.applyCamera(camera);
  }, [camera, status]);
  return (
    <div className="operational-map-canvas-wrap">
      <div ref={containerRef} className="operational-map-canvas" />
      {usarEmbutido && status === "ready" ? (
        <div className="operational-map-reserva-notice" role="status">
          <span>
            {motivoDaSonda
              ? `Nesta rede, ${motivoDaSonda} — exibindo o mapa base (OpenStreetMap).`
              : "O mapa do provider abriu sem desenhar nesta rede — exibindo o mapa base (OpenStreetMap)."}
          </span>
          <button
            type="button"
            className="operational-map-retry"
            disabled={limpando}
            onClick={() => {
              void tentarDeNovo();
            }}
          >
            {limpando ? "Limpando…" : "Tentar o provider"}
          </button>
        </div>
      ) : null}
      {status === "loading" ? (
        <div className="operational-map-overlay" role="status">
          {sondaConcluida
            ? "Carregando o mapa da obra…"
            : "Verificando o servidor de mapas…"}
        </div>
      ) : null}
      {status === "error" ? (
        <div className="operational-map-overlay operational-map-overlay--error">
          <strong>Mapa temporariamente indisponível</strong>
          <span>{error}</span>
          <button
            type="button"
            className="operational-map-retry"
            disabled={limpando}
            onClick={() => {
              void tentarDeNovo();
            }}
          >
            {limpando ? "Limpando…" : "Baixar de novo"}
          </button>
          <small>
            Descarta os tiles guardados neste aparelho e busca outra vez. O
            painel ao lado continua funcionando enquanto isso.
          </small>
        </div>
      ) : null}
      {status === "ready" && error ? (
        <div className="operational-map-runtime-warning" role="alert">
          <span>{error}</span>
          {/* O aviso pós-carga precisa da mesma saída do erro de montagem:
              sem ela, o mapa que subiu e não pintou fica sem nenhuma ação
              possível além de recarregar, que o cache torna inútil. */}
          <button
            type="button"
            className="operational-map-retry"
            disabled={limpando}
            onClick={() => {
              void tentarDeNovo();
            }}
          >
            {limpando ? "Limpando…" : "Baixar de novo"}
          </button>
        </div>
      ) : null}
    </div>
  );
}

export function OperationalMap({
  obra,
  leitura,
  carregando,
  erroLeitura,
  camera = null,
  onCamera = null,
  filtro = FILTRO_VAZIO,
  onRemoverPonto = null,
}: OperationalMapProps) {
  const defaultProvider = useMemo(() => resolveMapProvider(), []);
  const providers = useMemo(() => availableMapProviders(), []);
  const [providerId, setProviderId] =
    useState<MapProviderId>(defaultProvider.id);
  /*
   * O painel é tridimensional, e só.
   *
   * O alternador oferecia um 2D que duplicava o mapa ao lado — o Leaflet já
   * mostra a rodovia em planta. Manter os dois pintando a mesma leitura tirava
   * do painel a única coisa que ele faz e o outro não: mostrar o relevo
   * construído do canteiro.
   */
  const [mode] = useState<MapViewMode>("3d");
  const [centerRequest, setCenterRequest] = useState(0);

  const provider = useMemo(
    () => resolveMapProviderForId(providerId),
    [providerId],
  );
  const authoritativeWorksite = leitura?.dados.obra ?? obra;
  const featureCollection = useMemo(
    () =>
      filtrarColecao(
        buildOperationalFeatureCollection(
          authoritativeWorksite,
          leitura?.dados.features ?? [],
        ),
        filtro,
      ),
    [authoritativeWorksite, filtro, leitura?.dados.features],
  );
  const center = useMemo(
    () =>
      isValidWorksiteCoordinate(
        authoritativeWorksite.latitude,
        authoritativeWorksite.longitude,
      )
        ? ([
            authoritativeWorksite.longitude,
            authoritativeWorksite.latitude,
          ] as [number, number])
        : firstCoordinate(featureCollection),
    [authoritativeWorksite, featureCollection],
  );
  const categories = useMemo(
    () =>
      [...new Set(
        featureCollection.features.map((feature) =>
          String(feature.properties.categoria),
        ),
      )],
    [featureCollection.features],
  );
  const fases = useMemo(
    () => fasesPresentes(featureCollection),
    [featureCollection],
  );
  return (
    <section className="operational-map" aria-labelledby="operational-map-title">
      <header className="operational-map-header">
        <div>
          <p className="eyebrow">Território operacional</p>
          <h3 id="operational-map-title">Mapa da obra</h3>
          <span>
            {center
              ? `${center[1].toFixed(6)}, ${center[0].toFixed(6)}`
              : "Sem coordenadas autoritativas"}
          </span>
        </div>
        <div className="operational-map-controls">
          {/*
            Com um provider só não há escolha a fazer, e um seletor de uma
            opção apenas ocupa a barra e sugere alternativas que não existem.
          */}
          {providers.length > 1 ? (
            <label>
              <span>Provider</span>
              <select
                value={providerId}
                onChange={(event) =>
                  setProviderId(event.target.value as MapProviderId)
                }
              >
                {providers.map((candidate) => (
                  <option key={candidate.id} value={candidate.id}>
                    {candidate.label}
                  </option>
                ))}
              </select>
            </label>
          ) : null}
          <button
            type="button"
            className="operational-map-center-button"
            disabled={!center || !provider.configured}
            onClick={() => setCenterRequest((value) => value + 1)}
          >
            Centralizar
          </button>
        </div>
      </header>

      {defaultProvider.fallbackReason ? (
        <p className="operational-map-notice">{defaultProvider.fallbackReason}</p>
      ) : null}

      {!provider.configured ? (
        <div className="operational-map-empty operational-map-empty--config">
          <span aria-hidden="true">⌁</span>
          <strong>{provider.label} ainda não está configurado</strong>
          <p>{provider.missingConfiguration}</p>
          <small>
            Nenhum provider é simulado; a página permanece funcional sem a chave.
          </small>
        </div>
      ) : !center ? (
        <div className="operational-map-empty">
          <span aria-hidden="true">⌖</span>
          <strong>Localização ainda não registrada</strong>
          <p>
            Cadastre coordenadas ou uma geometria real para posicionar esta obra.
          </p>
        </div>
      ) : (
        <MapCanvas
          /* Só a troca de obra pede um mapa novo — é outra localidade, com
             outro enquadramento. Trocar de provider e tentar de novo já são
             tratados dentro do painel, sem descartar o que está na tela. */
          key={authoritativeWorksite.id}
          provider={provider}
          features={featureCollection}
          center={center}
          mode={mode}
          centerRequest={centerRequest}
          camera={camera}
          onCamera={onCamera}
          onRemoverPonto={onRemoverPonto}
        />
      )}

      <footer className="operational-map-footer">
        <div className="operational-map-legend" aria-label="Camadas exibidas">
          {categories.length > 0 ? (
            categories.map((category) => (
              <span key={category}>
                <i
                  aria-hidden="true"
                  style={{ background: corDaCategoria(category) }}
                />
                {rotuloDaCategoria(category)}
              </span>
            ))
          ) : (
            <span>Nenhuma camada geoespacial disponível</span>
          )}
        </div>
        {fases.length > 0 ? (
          <div
            className="operational-map-legend operational-map-legend--execucao"
            aria-label="Fases de execução do traçado"
          >
            {fases.map((fase) => (
              <span key={fase}>
                <i
                  aria-hidden="true"
                  style={{ background: corDoToken(TOKEN_POR_FASE[fase]) }}
                />
                {ROTULO_POR_FASE[fase]}
              </span>
            ))}
          </div>
        ) : null}
        <small>
          {carregando
            ? "Consultando camadas autoritativas…"
            : erroLeitura
              ? "Camadas indisponíveis nesta leitura"
              : `${featureCollection.features.length} geometria(s) em exibição`}
        </small>
      </footer>
    </section>
  );
}
