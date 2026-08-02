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
import type { LeituraMapaObra } from "./obraMapApi";
import { descartarCacheDeMapa } from "./cacheDeTiles";
import {
  FILTRO_VAZIO,
  filtrarColecao,
  type FiltroDoMapa,
} from "./filtrosDoMapa";
import {
  availableMapProviders,
  providerReservaOsm,
  resolveMapProvider,
  resolveMapProviderForId,
  type MapProvider,
  type MapProviderId,
} from "./mapProvider";
import "./OperationalMap.css";

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
   * Recorte decidido pelo workspace. Chega pronto para que esta metade e a
   * metade Leaflet nunca mostrem obras diferentes lado a lado.
   */
  filtro?: FiltroDoMapa;
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
}: {
  provider: MapProvider;
  features: OperationalFeatureCollection;
  center: [number, number];
  mode: MapViewMode;
  centerRequest: number;
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
   * Basemap reserva, acionado sozinho quando a fonte do principal não vem.
   *
   * Sem isto o painel subia como um retângulo mudo: o renderizador declara
   * tudo carregado mesmo com a fonte inteira falhando. O operador vê o mapa
   * reserva na hora, com um aviso — não um vazio sem explicação.
   */
  const [reserva, setReserva] = useState(false);
  const [providerAnterior, setProviderAnterior] = useState(provider);
  if (provider !== providerAnterior) {
    // Troca explícita de provider zera a contingência: a escolha é de quem
    // opera, e o novo provider merece a primeira tentativa limpa.
    setProviderAnterior(provider);
    setReserva(false);
  }
  const providerEfetivo = useMemo(
    () => (reserva ? providerReservaOsm() : provider),
    [reserva, provider],
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
  useEffect(() => {
    featuresRef.current = features;
    centerRef.current = center;
    modeRef.current = mode;
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
      // Cache limpo, o principal merece nova chance antes da reserva.
      setReserva(false);
      setTentativa((anterior) => anterior + 1);
    }
  };

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    let cancelled = false;
    let controller: OperationalMapController | null = null;

    mountOperationalMap({
      container,
      provider: providerEfetivo,
      features: featuresRef.current,
      center: centerRef.current,
      mode: modeRef.current,
      onRuntimeError: (message) => {
        if (!cancelled) setError(message);
      },
      onFalhaDeBasemap: () => {
        // A reserva só se aciona uma vez; se ela própria falhar, o aviso de
        // erro comum assume, em vez de um laço de remontagens.
        if (!cancelled && !providerEfetivo.reserva) {
          setError(null);
          setReserva(true);
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
  }, [providerEfetivo, tentativa]);

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

  return (
    <div className="operational-map-canvas-wrap">
      <div ref={containerRef} className="operational-map-canvas" />
      {reserva && status === "ready" ? (
        <div className="operational-map-reserva-notice" role="status">
          <span>
            O servidor de mapas principal não respondeu nesta rede — exibindo
            o mapa reserva (OSM).
          </span>
          <button
            type="button"
            className="operational-map-retry"
            disabled={limpando}
            onClick={() => {
              void tentarDeNovo();
            }}
          >
            {limpando ? "Limpando…" : "Tentar o principal"}
          </button>
        </div>
      ) : null}
      {status === "loading" ? (
        <div className="operational-map-overlay" role="status">
          Carregando imagens de satélite…
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
  filtro = FILTRO_VAZIO,
}: OperationalMapProps) {
  const defaultProvider = useMemo(() => resolveMapProvider(), []);
  const providers = useMemo(() => availableMapProviders(), []);
  const [providerId, setProviderId] =
    useState<MapProviderId>(defaultProvider.id);
  const [mode, setMode] = useState<MapViewMode>("2d");
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
          <div className="operational-map-view-toggle" role="group" aria-label="Visualização">
            <button
              type="button"
              className={mode === "2d" ? "active" : ""}
              onClick={() => setMode("2d")}
            >
              2D
            </button>
            <button
              type="button"
              className={mode === "3d" ? "active" : ""}
              disabled={!provider.capabilities.perspective3d}
              onClick={() => setMode("3d")}
            >
              3D
            </button>
          </div>
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
        />
      )}

      <footer className="operational-map-footer">
        <div className="operational-map-legend" aria-label="Camadas exibidas">
          {categories.length > 0 ? (
            categories.map((category) => (
              <span key={category}>{category.replaceAll("_", " ")}</span>
            ))
          ) : (
            <span>Nenhuma camada geoespacial disponível</span>
          )}
        </div>
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
