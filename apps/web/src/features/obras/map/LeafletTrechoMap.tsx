import { useEffect, useRef, useState } from "react";

import {
  corDaCategoria,
  corDoToken,
  rotuloDaCategoria,
  rotuloDaFonte,
} from "./mapCategories";
import type { OperationalFeatureCollection } from "./mapGeometry";
import {
  RASCUNHO_VAZIO,
  type ExtremoDoTrecho,
  type PontoGeografico,
  type RascunhoDoTrecho,
} from "./rascunhoDoTrecho";
import "./LeafletTrechoMap.css";

export type {
  ExtremoDoTrecho,
  PontoGeografico,
  RascunhoDoTrecho,
} from "./rascunhoDoTrecho";

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
  /**
   * Extremos que estão sendo marcados, ainda não gravados.
   *
   * Chegam prontos de quem monta o componente, e o mapa apenas os desenha. O
   * rascunho já viveu aqui dentro, acumulado a cada clique, e qualquer coisa
   * que zerasse o modo de desenho apagava o marco de início da tela sem que
   * ninguém tivesse desistido dele.
   */
  rascunho?: RascunhoDoTrecho;
  /** Extremo que o próximo clique no mapa define; `null` desliga a marcação. */
  marcando?: ExtremoDoTrecho | null;
  /** Coordenada realmente clicada, para o extremo em marcação. */
  onPontoMarcado?: (extremo: ExtremoDoTrecho, ponto: PontoGeografico) => void;
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

const APARENCIA_DO_EXTREMO: Readonly<
  Record<ExtremoDoTrecho, { token: string; sigla: string; rotulo: string }>
> = Object.freeze({
  INICIO: {
    token: "--color-brand-yellow",
    sigla: "IN",
    rotulo: "Início do trecho",
  },
  FIM: { token: "--color-danger", sigla: "FI", rotulo: "Fim do trecho" },
});

/**
 * Painel Leaflet do trecho.
 *
 * Usa exclusivamente as geometrias autoritativas recebidas por propriedade.
 * A marcação dos extremos é controlada de fora: o mapa desenha o rascunho que
 * recebe e devolve a coordenada clicada, sem guardar estado próprio. É o que
 * mantém o marco de início na tela enquanto o de fim é escolhido, e o que
 * permite remarcar um extremo sem refazer o outro.
 */
export function LeafletTrechoMap({
  features,
  center,
  limitesIniciais = null,
  rascunho = RASCUNHO_VAZIO,
  marcando = null,
  onPontoMarcado,
}: LeafletTrechoMapProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<import("leaflet").Map | null>(null);
  const camadaRef = useRef<import("leaflet").LayerGroup | null>(null);
  const rascunhoRef = useRef<import("leaflet").LayerGroup | null>(null);
  const leafletRef = useRef<typeof import("leaflet") | null>(null);
  const marcandoRef = useRef<ExtremoDoTrecho | null>(marcando);
  const aoMarcarRef = useRef(onPontoMarcado);
  const enquadramentoRef = useRef<string | null>(null);
  const [estado, setEstado] = useState<"carregando" | "pronto" | "erro">(
    "carregando",
  );
  const [erro, setErro] = useState<string | null>(null);

  // O mapa é montado uma vez, então o ouvinte de clique precisa ler o extremo
  // e o destino correntes por referência, e não o que valia na montagem.
  useEffect(() => {
    marcandoRef.current = marcando;
    aoMarcarRef.current = onPontoMarcado;
  });

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
        // Abre sobre o canteiro, não sobre a região: em 14 a obra
        // vira um traço no meio do município.
        zoom: 15,
        attributionControl: true,
      });
      leaflet
        .tileLayer(TILE_URL, { attribution: TILE_ATTRIBUTION, maxZoom: 19 })
        .addTo(map);

      camadaRef.current = leaflet.layerGroup().addTo(map);
      rascunhoRef.current = leaflet.layerGroup().addTo(map);
      mapRef.current = map;

      map.on("click", (event: import("leaflet").LeafletMouseEvent) => {
        const extremo = marcandoRef.current;
        if (!extremo) return;
        aoMarcarRef.current?.(extremo, {
          lat: Number(event.latlng.lat.toFixed(6)),
          lng: Number(event.latlng.lng.toFixed(6)),
        });
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

  /*
   * Redesenha o rascunho a partir do que foi recebido.
   *
   * Sendo derivado, o marco só sai da tela quando o extremo deixa de existir
   * de fato. A linha entre os dois aparece assim que ambos estão marcados,
   * para que dê para conferir o trecho antes de registrar.
   */
  useEffect(() => {
    const leaflet = leafletRef.current;
    const camada = rascunhoRef.current;
    if (!leaflet || !camada || estado !== "pronto") return;

    camada.clearLayers();
    const extremos: [ExtremoDoTrecho, PontoGeografico][] = [];
    if (rascunho.inicio) extremos.push(["INICIO", rascunho.inicio]);
    if (rascunho.fim) extremos.push(["FIM", rascunho.fim]);

    if (rascunho.inicio && rascunho.fim) {
      leaflet
        .polyline(
          [
            [rascunho.inicio.lat, rascunho.inicio.lng],
            [rascunho.fim.lat, rascunho.fim.lng],
          ],
          {
            color: corDoToken("--color-brand-yellow"),
            weight: 5,
            opacity: 0.9,
            dashArray: "8 6",
            interactive: false,
          },
        )
        .addTo(camada);
    }

    for (const [extremo, ponto] of extremos) {
      const aparencia = APARENCIA_DO_EXTREMO[extremo];
      leaflet
        .marker([ponto.lat, ponto.lng], {
          icon: leaflet.divIcon({
            className: "leaflet-trecho-divicon",
            html: marcadorHtml(
              corDoToken(aparencia.token),
              aparencia.sigla,
            ),
            iconSize: [24, 24],
            iconAnchor: [12, 12],
          }),
        })
        .bindPopup(
          `<strong>${aparencia.rotulo}</strong><span>${ponto.lat.toFixed(
            6,
          )}, ${ponto.lng.toFixed(6)}</span>`,
          { closeButton: false },
        )
        .addTo(camada);
    }
  }, [rascunho, estado]);

  // O cursor de mira é o que diz que o próximo clique vale como marcação.
  useEffect(() => {
    const map = mapRef.current;
    if (!map || estado !== "pronto") return;
    map.getContainer().style.cursor = marcando ? "crosshair" : "";
  }, [marcando, estado]);

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
      {marcando && estado === "pronto" ? (
        <p className="leaflet-trecho-instrucao" role="status">
          {marcando === "INICIO"
            ? "Clique sobre a rodovia para marcar o início do trecho."
            : "Clique sobre a rodovia para marcar o fim do trecho."}
        </p>
      ) : null}
    </div>
  );
}
