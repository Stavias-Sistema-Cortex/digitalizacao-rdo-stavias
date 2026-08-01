import { useState } from "react";

import type { ExtremoDoTrecho, PontoGeografico } from "./rascunhoDoTrecho";

interface CampoDeExtremoProps {
  extremo: ExtremoDoTrecho;
  valor: PontoGeografico | null;
  /** Verdadeiro quando o próximo clique no mapa define este extremo. */
  marcando: boolean;
  onAlterar: (ponto: PontoGeografico | null) => void;
  onMarcarNoMapa: () => void;
}

const ROTULO: Readonly<Record<ExtremoDoTrecho, string>> = Object.freeze({
  INICIO: "Início",
  FIM: "Fim",
});

function numeroDigitado(texto: string): number | null {
  const limpo = texto.trim().replace(",", ".");
  if (!limpo) return null;
  const valor = Number(limpo);
  return Number.isFinite(valor) ? valor : null;
}

function comoTexto(valor: number | undefined): string {
  return valor === undefined ? "" : String(valor);
}

/**
 * Coordenada de um extremo do trecho, marcada no mapa ou digitada.
 *
 * Digitar existe porque a coordenada às vezes vem de um GPS de mão ou de um
 * projeto, e clicar no mapa nunca reproduz o valor exato. O ponto só é
 * atualizado quando latitude e longitude fecham juntas e dentro do globo:
 * meia coordenada não é ponto, e aceitar uma faria o marcador desaparecer do
 * mapa no meio da digitação.
 */
export function CampoDeExtremo({
  extremo,
  valor,
  marcando,
  onAlterar,
  onMarcarNoMapa,
}: CampoDeExtremoProps) {
  const [lat, setLat] = useState(() => comoTexto(valor?.lat));
  const [lng, setLng] = useState(() => comoTexto(valor?.lng));

  const propagar = (proximoLat: string, proximoLng: string) => {
    const latitude = numeroDigitado(proximoLat);
    const longitude = numeroDigitado(proximoLng);
    if (
      latitude !== null &&
      longitude !== null &&
      Math.abs(latitude) <= 90 &&
      Math.abs(longitude) <= 180
    ) {
      onAlterar({ lat: latitude, lng: longitude });
      return;
    }
    if (!proximoLat.trim() && !proximoLng.trim()) {
      onAlterar(null);
    }
  };

  const rotulo = ROTULO[extremo];

  return (
    <fieldset className="rodovia-cadastro__extremo">
      <legend>{rotulo} do trecho</legend>
      <div className="rodovia-cadastro__extremo-campos">
        <label>
          Latitude
          <input
            value={lat}
            inputMode="decimal"
            placeholder="grau decimal"
            aria-label={`Latitude do ${rotulo.toLowerCase()}`}
            onChange={(evento) => {
              setLat(evento.target.value);
              propagar(evento.target.value, lng);
            }}
          />
        </label>
        <label>
          Longitude
          <input
            value={lng}
            inputMode="decimal"
            placeholder="grau decimal"
            aria-label={`Longitude do ${rotulo.toLowerCase()}`}
            onChange={(evento) => {
              setLng(evento.target.value);
              propagar(lat, evento.target.value);
            }}
          />
        </label>
      </div>
      <button
        type="button"
        className={marcando ? "is-marcando" : ""}
        aria-pressed={marcando}
        onClick={onMarcarNoMapa}
      >
        {marcando
          ? "Clique no mapa…"
          : valor
            ? `Remarcar o ${rotulo.toLowerCase()} no mapa`
            : `Marcar o ${rotulo.toLowerCase()} no mapa`}
      </button>
    </fieldset>
  );
}
