import { useEffect, useRef, useState } from "react";

import {
  corDaCategoria,
  corDoToken,
  rotuloDaCategoria,
  rotuloDaFonte,
} from "./mapCategories";
import type { OperationalFeatureCollection } from "./mapGeometry";
import "./LeafletTrechoMap.css";

export interface PontoGeografico {
  lat: number;
  lng: number;
}

export type ModoDesenho = "INATIVO" | "TRECHO";

interface LeafletTrechoMapProps {
  features: OperationalFeatureCollection;
  /** Centro no formato GeoJSON `[longitude, latitude]`. */
  center: [number, number];
  /**
   * Caixa delimitadora do enquadramento aproximado, `[[oeste, sul], [leste,
   * norte]]`. Usada uma única vez, quando não há geometria: enquadra o
   * município inteiro em vez de abrir num zoom arbitrário sobre o centro.
   */
  limitesIniciais?: [[number, number], [number, number]] | null;
  modo: ModoDesenho;
  /** Chamado quando o desenho fecha os dois extremos do trecho. */
  onTrechoDesenhado?: (pontos: PontoGeografico[]) => void;
  /** Progresso do desenho, para o container refletir no cabeçalho. */
  onPontoCapturado?: (quantidade: number) => void;
}

const TILE_URL = "https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png";
const TILE_ATTRIBUTION =
  '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>';

function escapeHtml(value: string): string {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

/**
 * Marcador desenhado em CSS.
 *
 * O ícone padrão do Leaflet é uma imagem resolvida em tempo de execução; um
 * `DivIcon` mantém o marcador disponível offline e dentro da CSP sem depender de
 * nenhum recurso externo.
 */
function marcadorHtml(cor: string, sigla: string): string {
  return `<span class="leaflet-trecho-pin" style="--pin-cor:${cor}">${escapeHtml(
    sigla,
  )}</span>`;
}

function siglaDaCategoria(categoria: unknown): string {
  if (typeof categoria !== "string" || !categoria) {
    return "•";
  }
  if (categoria === "LOCALIZACAO_OBRA") return "OB";
  if (categoria === "PONTO_OPERACIONAL") return "PO";
  if (categoria === "FRENTE_TRABALHO") return "FT";
  return categoria.slice(0, 2);
}

function popupHtml(properties: Record<string, unknown>): string {
  const titulo =
    typeof properties.nome === "string" && properties.nome
      ? properties.nome
      : rotuloDaCategoria(properties.categoria);
  const detalhes = [
    typeof properties.numeroRdo === "string"
      ? `RDO ${properties.numeroRdo}`
      : null,
    typeof properties.validoDesde === "string"
      ? `desde ${properties.validoDesde.slice(0, 10)}`
      : null,
    typeof properties.categoria === "string"
      ? rotuloDaCategoria(properties.categoria)
      : null,
    typeof properties.fonte === "string"
      ? rotuloDaFonte(properties.fonte)
      : null,
  ].filter((item): item is string => Boolean(item));

  return `<strong>${escapeHtml(titulo)}</strong><span>${escapeHtml(
    detalhes.join(" · "),
  )}</span>`;
}

/**
 * Painel Leaflet do trecho.
 *
 * Usa exclusivamente as geometrias autoritativas recebidas por propriedade.
 * Quando o modo de desenho está ativo, dois cliques definem os extremos do
 * trecho e a persistência fica a cargo de quem monta o componente — aqui só se
 * captura a coordenada que a pessoa realmente marcou.
 */
export function LeafletTrechoMap({
  features,
  center,
  limitesIniciais = null,
  modo,
  onTrechoDesenhado,
  onPontoCapturado,
}: LeafletTrechoMapProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<import("leaflet").Map | null>(null);
  const camadaRef = useRef<import("leaflet").LayerGroup | null>(null);
  const rascunhoRef = useRef<import("leaflet").LayerGroup | null>(null);
  const leafletRef = useRef<typeof import("leaflet") | null>(null);
  const pontosRef = useRef<PontoGeografico[]>([]);
  const modoRef = useRef<ModoDesenho>(modo);
  const enquadramentoRef = useRef<string | null>(null);
  const [estado, setEstado] = useState<"carregando" | "pronto" | "erro">(
    "carregando",
  );
  const [erro, setErro] = useState<string | null>(null);

  useEffect(() => {
    modoRef.current = modo;
    if (modo === "INATIVO") {
      pontosRef.current = [];
      rascunhoRef.current?.clearLayers();
      onPontoCapturado?.(0);
    }
  }, [modo, onPontoCapturado]);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    let cancelado = false;

    async function montar(alvo: HTMLDivElement) {
      const leaflet = await import("leaflet");
      await import("leaflet/dist/leaflet.css");
      if (cancelado) return;

      leafletRef.current = leaflet;
      const map = leaflet.map(alvo, {
        center: [center[1], center[0]],
        zoom: 14,
        attributionControl: true,
      });
      leaflet
        .tileLayer(TILE_URL, { attribution: TILE_ATTRIBUTION, maxZoom: 19 })
        .addTo(map);

      camadaRef.current = leaflet.layerGroup().addTo(map);
      rascunhoRef.current = leaflet.layerGroup().addTo(map);
      mapRef.current = map;

      map.on("click", (event: import("leaflet").LeafletMouseEvent) => {
        if (modoRef.current !== "TRECHO") return;
        const ponto = {
          lat: Number(event.latlng.lat.toFixed(6)),
          lng: Number(event.latlng.lng.toFixed(6)),
        };
        pontosRef.current = [...pontosRef.current, ponto];
        onPontoCapturado?.(pontosRef.current.length);

        // As cores do rascunho saem do tema, como todas as outras: amarelo
        // estrutural para o início, vermelho de alerta para o fim.
        const cor =
          pontosRef.current.length === 1
            ? corDoToken("--color-brand-yellow")
            : corDoToken("--color-danger");
        leaflet
          .marker([ponto.lat, ponto.lng], {
            icon: leaflet.divIcon({
              className: "leaflet-trecho-divicon",
              html: marcadorHtml(
                cor,
                pontosRef.current.length === 1 ? "IN" : "FI",
              ),
              iconSize: [24, 24],
              iconAnchor: [12, 12],
            }),
          })
          .addTo(rascunhoRef.current as import("leaflet").LayerGroup);

        if (pontosRef.current.length >= 2) {
          const capturados = pontosRef.current;
          pontosRef.current = [];
          onTrechoDesenhado?.(capturados);
        }
      });

      setEstado("pronto");
    }

    montar(container).catch((motivo: unknown) => {
      if (cancelado) return;
      setEstado("erro");
      setErro(
        motivo instanceof Error
          ? motivo.message
          : "Não foi possível carregar o mapa Leaflet.",
      );
    });

    return () => {
      cancelado = true;
      mapRef.current?.remove();
      mapRef.current = null;
      camadaRef.current = null;
      rascunhoRef.current = null;
    };
    // O mapa é montado uma vez; centro e camadas são atualizados nos efeitos
    // seguintes para não recriar a instância a cada sincronização.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // O centro só reposiciona quando a coordenada muda de fato; o enquadramento
  // pela geometria, logo abaixo, tem precedência quando há trecho desenhado.
  const [longitude, latitude] = center;
  useEffect(() => {
    const map = mapRef.current;
    if (map && estado === "pronto" && enquadramentoRef.current === null) {
      map.setView([latitude, longitude], map.getZoom());
    }
  }, [latitude, longitude, estado]);

  // Sem geometria nenhuma, o enquadramento aproximado abre o município
  // inteiro. Acontece uma única vez: assim que existir geometria real, o
  // efeito de camadas assume o enquadramento e este não volta a rodar.
  useEffect(() => {
    const map = mapRef.current;
    if (
      !map ||
      estado !== "pronto" ||
      !limitesIniciais ||
      features.features.length > 0 ||
      enquadramentoRef.current !== null
    ) {
      return;
    }
    enquadramentoRef.current = "enquadramento-aproximado";
    map.fitBounds(
      [
        [limitesIniciais[0][1], limitesIniciais[0][0]],
        [limitesIniciais[1][1], limitesIniciais[1][0]],
      ],
      { padding: [16, 16] },
    );
  }, [limitesIniciais, estado, features.features.length]);

  useEffect(() => {
    const leaflet = leafletRef.current;
    const camada = camadaRef.current;
    const map = mapRef.current;
    if (!leaflet || !camada || !map || estado !== "pronto") return;

    camada.clearLayers();
    if (features.features.length === 0) return;

    // O contorno escuro é desenhado primeiro para que a linha colorida assente
    // sobre ele e o trecho leia como pista, e não como risco solto no mapa.
    leaflet
      .geoJSON(features as never, {
        style: (feature) => ({
          color: corDoToken("--color-ink"),
          weight: feature?.properties?.categoria === "TRECHO" ? 10 : 6,
          opacity: 0.85,
          fill: false,
          lineCap: "round",
          lineJoin: "round",
        }),
        pointToLayer: () => leaflet.layerGroup(),
        interactive: false,
      })
      .addTo(camada);

    const geoJson = leaflet.geoJSON(features as never, {
      style: (feature) => ({
        color: corDaCategoria(feature?.properties?.categoria),
        weight: feature?.properties?.categoria === "TRECHO" ? 6 : 3,
        opacity: 0.95,
        fillOpacity: 0.25,
        lineCap: "round",
        lineJoin: "round",
      }),
      pointToLayer: (feature, latlng) =>
        leaflet.marker(latlng, {
          icon: leaflet.divIcon({
            className: "leaflet-trecho-divicon",
            html: marcadorHtml(
              corDaCategoria(feature.properties?.categoria),
              siglaDaCategoria(feature.properties?.categoria),
            ),
            iconSize: [24, 24],
            iconAnchor: [12, 12],
          }),
        }),
      onEachFeature: (feature, layer) => {
        layer.bindPopup(
          popupHtml((feature.properties ?? {}) as Record<string, unknown>),
          { closeButton: false },
        );
      },
    });
    geoJson.addTo(camada);

    // O enquadramento só é refeito quando a geometria realmente muda. Cada
    // rodada de sincronização recria a coleção, e reenquadrar por identidade de
    // objeto jogaria fora o pan e o zoom de quem está lendo o mapa.
    const assinatura = features.features
      .map((feature) => `${feature.id}:${String(feature.properties.versao ?? "")}`)
      .join("|");
    if (enquadramentoRef.current === assinatura) {
      return;
    }
    enquadramentoRef.current = assinatura;

    const bounds = geoJson.getBounds();
    if (bounds.isValid()) {
      map.fitBounds(bounds, { padding: [24, 24], maxZoom: 16 });
    }
  }, [features, estado]);

  return (
    <div className="leaflet-trecho">
      <div
        ref={containerRef}
        className="leaflet-trecho-canvas"
        data-testid="leaflet-trecho-canvas"
      />
      {estado === "carregando" ? (
        <div className="leaflet-trecho-overlay" role="status">
          Carregando o mapa da rodovia…
        </div>
      ) : null}
      {estado === "erro" ? (
        <div className="leaflet-trecho-overlay leaflet-trecho-overlay--erro">
          <strong>Mapa da rodovia indisponível</strong>
          <span>{erro}</span>
        </div>
      ) : null}
      {modo === "TRECHO" && estado === "pronto" ? (
        <p className="leaflet-trecho-instrucao">
          Marque o início e o fim do trecho sobre a rodovia.
        </p>
      ) : null}
    </div>
  );
}
