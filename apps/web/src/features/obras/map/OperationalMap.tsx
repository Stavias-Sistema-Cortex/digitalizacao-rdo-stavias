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
import {
  availableMapProviders,
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

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    let cancelled = false;
    let controller: OperationalMapController | null = null;

    mountOperationalMap({
      container,
      provider,
      features,
      center,
      mode,
      onRuntimeError: (message) => {
        if (!cancelled) setError(message);
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
  }, [center, features, mode, provider]);

  useEffect(() => {
    if (centerRequest > 0) {
      controllerRef.current?.centerOn(center[0], center[1]);
    }
  }, [center, centerRequest]);

  return (
    <div className="operational-map-canvas-wrap">
      <div ref={containerRef} className="operational-map-canvas" />
      {status === "loading" ? (
        <div className="operational-map-overlay" role="status">
          Carregando imagens de satélite…
        </div>
      ) : null}
      {status === "error" ? (
        <div className="operational-map-overlay operational-map-overlay--error">
          <strong>Mapa temporariamente indisponível</strong>
          <span>{error}</span>
        </div>
      ) : null}
      {status === "ready" && error ? (
        <p className="operational-map-runtime-warning">{error}</p>
      ) : null}
    </div>
  );
}

export function OperationalMap({
  obra,
  leitura,
  carregando,
  erroLeitura,
}: OperationalMapProps) {
  const defaultProvider = useMemo(() => resolveMapProvider(), []);
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
      buildOperationalFeatureCollection(
        authoritativeWorksite,
        leitura?.dados.features ?? [],
      ),
    [authoritativeWorksite, leitura?.dados.features],
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
  const mapSignature = `${provider.id}:${carregando ? "carregando" : "pronto"}:${featureCollection.features
    .map((feature) => `${feature.id}:${String(feature.properties.versao ?? "")}`)
    .join("|")}`;

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
          <label>
            <span>Provider</span>
            <select
              value={providerId}
              onChange={(event) =>
                setProviderId(event.target.value as MapProviderId)
              }
            >
              {availableMapProviders().map((candidate) => (
                <option key={candidate.id} value={candidate.id}>
                  {candidate.label}
                  {candidate.configured ? "" : " · sem chave"}
                </option>
              ))}
            </select>
          </label>
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
          key={mapSignature}
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
              : `${leitura?.dados.features.length ?? 0} geometria(s) ontológica(s)`}
        </small>
      </footer>
    </section>
  );
}
