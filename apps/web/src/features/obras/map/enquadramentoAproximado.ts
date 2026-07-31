export interface EnderecoDaObra {
  cidade: string | null;
  uf: string | null;
  rodovia: string | null;
}

export interface EnquadramentoAproximado {
  /** Centro no formato GeoJSON `[longitude, latitude]`. */
  centro: [number, number];
  /** Extensão sugerida, quando o serviço devolve uma caixa delimitadora. */
  limites: [[number, number], [number, number]] | null;
  /** O que foi realmente consultado, para a interface poder declarar. */
  consulta: string;
  /** Nome que o serviço reconheceu, exibido junto do aviso de aproximação. */
  local: string;
}

/**
 * Serviço de geocodificação. O padrão é o Nominatim público do OpenStreetMap,
 * sem chave; uma instalação pode apontar para a própria instância.
 */
const SERVICO_PADRAO = "https://nominatim.openstreetmap.org/search";
const TEMPO_LIMITE_MS = 8_000;
const CHAVE_SESSAO = "cortex.mapa.enquadramento.";

function servico(): string {
  const configurado = import.meta.env?.VITE_GEOCODER_URL?.trim();
  return configurado || SERVICO_PADRAO;
}

/**
 * Monta a consulta a partir do que a obra declara.
 *
 * Sem cidade não há consulta: rodovia sozinha atravessa o estado inteiro e o
 * resultado apontaria para qualquer ponto dela.
 */
export function consultaDoEndereco(endereco: EnderecoDaObra): string | null {
  const cidade = endereco.cidade?.trim();
  if (!cidade) {
    return null;
  }
  return [endereco.rodovia?.trim(), cidade, endereco.uf?.trim(), "Brasil"]
    .filter((parte): parte is string => Boolean(parte))
    .join(", ");
}

function numero(valor: unknown): number | null {
  const convertido = typeof valor === "number" ? valor : Number(valor);
  return Number.isFinite(convertido) ? convertido : null;
}

export function enquadramentoDaResposta(
  valor: unknown,
  consulta: string,
): EnquadramentoAproximado | null {
  const primeiro = Array.isArray(valor) ? valor[0] : null;
  if (!primeiro || typeof primeiro !== "object") {
    return null;
  }
  const item = primeiro as Record<string, unknown>;
  const latitude = numero(item.lat);
  const longitude = numero(item.lon);
  if (
    latitude === null ||
    longitude === null ||
    Math.abs(latitude) > 90 ||
    Math.abs(longitude) > 180
  ) {
    return null;
  }

  // boundingbox vem como [sul, norte, oeste, leste] em texto.
  const caixa = Array.isArray(item.boundingbox)
    ? item.boundingbox.map(numero)
    : [];
  const limites =
    caixa.length === 4 && caixa.every((parte) => parte !== null)
      ? ([
          [caixa[2] as number, caixa[0] as number],
          [caixa[3] as number, caixa[1] as number],
        ] as [[number, number], [number, number]])
      : null;

  return {
    centro: [longitude, latitude],
    limites,
    consulta,
    local:
      typeof item.display_name === "string" && item.display_name
        ? item.display_name.split(",").slice(0, 2).join(",").trim()
        : consulta,
  };
}

function lerDaSessao(consulta: string): EnquadramentoAproximado | null {
  if (typeof sessionStorage === "undefined") {
    return null;
  }
  try {
    const bruto = sessionStorage.getItem(CHAVE_SESSAO + consulta);
    return bruto ? (JSON.parse(bruto) as EnquadramentoAproximado) : null;
  } catch {
    return null;
  }
}

function gravarNaSessao(
  consulta: string,
  enquadramento: EnquadramentoAproximado,
): void {
  if (typeof sessionStorage === "undefined") {
    return;
  }
  try {
    sessionStorage.setItem(
      CHAVE_SESSAO + consulta,
      JSON.stringify(enquadramento),
    );
  } catch {
    // Sem espaço de sessão o enquadramento apenas não é reaproveitado.
  }
}

/**
 * Enquadramento inicial aproximado, derivado do endereço cadastral da obra.
 *
 * Serve exclusivamente para abrir o mapa na região certa quando a obra ainda
 * não tem coordenada nem geometria. **O resultado nunca é persistido, nunca
 * vira geometria e nunca preenche `obra.latitude/longitude`**: continua sendo o
 * município que o cadastro declara, não o lugar onde a equipe está. A
 * localização real segue vindo do trecho desenhado ou da captura em campo.
 *
 * Falha em silêncio: sem rede, sem cidade ou sem resposta utilizável, a tela
 * volta ao estado explícito de obra não georreferenciada.
 */
export async function buscarEnquadramentoAproximado(
  endereco: EnderecoDaObra,
  fetchImpl: typeof fetch | undefined = typeof fetch === "undefined"
    ? undefined
    : fetch,
): Promise<EnquadramentoAproximado | null> {
  const consulta = consultaDoEndereco(endereco);
  if (!consulta || !fetchImpl) {
    return null;
  }

  const memorizado = lerDaSessao(consulta);
  if (memorizado) {
    return memorizado;
  }

  const parametros = new URLSearchParams({
    q: consulta,
    format: "jsonv2",
    limit: "1",
    countrycodes: "br",
    addressdetails: "0",
  });
  const controlador = new AbortController();
  const relogio = setTimeout(() => controlador.abort(), TEMPO_LIMITE_MS);

  try {
    const resposta = await fetchImpl(`${servico()}?${parametros.toString()}`, {
      signal: controlador.signal,
      headers: { Accept: "application/json" },
      // O enquadramento é público e não pode carregar sessão do Córtex.
      credentials: "omit",
      referrerPolicy: "strict-origin",
    });
    if (!resposta.ok) {
      return null;
    }
    const enquadramento = enquadramentoDaResposta(
      await resposta.json(),
      consulta,
    );
    if (enquadramento) {
      gravarNaSessao(consulta, enquadramento);
    }
    return enquadramento;
  } catch {
    return null;
  } finally {
    clearTimeout(relogio);
  }
}
