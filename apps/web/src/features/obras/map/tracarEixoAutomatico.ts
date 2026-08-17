import type { PontoGeografico } from "./rascunhoDoTrecho";
import {
  buscarEnquadramentoAproximado,
  type EnderecoDaObra,
} from "./enquadramentoAproximado";

/**
 * O eixo traçado pelo próprio mapa, calibrado pelos marcos quilométricos.
 *
 * <p>Cadastrar o eixo à mão pede dois cliques certeiros sobre a rodovia e os
 * dois quilômetros de ponta — quatro informações que o mapa público já sabe. A
 * rodovia do cadastro da obra tem geometria no OpenStreetMap, e as rodovias
 * paulistas têm os marcos quilométricos mapeados como {@code highway=milestone}
 * com a distância no marco. Com a linha e dois marcos, a régua se calibra
 * sozinha: cada vértice da rodovia ganha um quilômetro, e as pontas do recorte
 * saem com o km que de fato têm no chão.
 *
 * <p><b>Nada aqui inventa posição nem quilômetro.</b> Sem a rodovia mapeada na
 * região da cidade, ou sem dois marcos que sustentem a régua, o traçado é
 * recusado com o motivo — cadastrar errado automaticamente seria pior do que
 * pedir os dois cliques. O eixo registrado continua editável como qualquer
 * outro: a lixeira o encerra e um novo cadastro o substitui.
 *
 * <p>A hierarquia não muda: o eixo é régua, o RDO é o fato. Editar a régua no
 * mapa nunca toca o RDO — no máximo desloca a linha derivada, e quando ela sai
 * da faixa o aviso de divergência aparece. Corrigir o quilômetro no RDO é o
 * que move a linha de verdade.
 */

/**
 * Os espelhos do mapa público, na ordem em que são tentados.
 *
 * <p>O Overpass é infraestrutura voluntária e compartilhada pelo mundo: o
 * espelho principal vive saturado em horário comercial e responde 429, 504 ou
 * simplesmente não responde. Um endereço só transformava isso em "o serviço
 * não respondeu" — verdadeiro e inútil, porque a rodovia estava lá o tempo
 * todo, atrás de outro espelho que serve exatamente a mesma base de dados.
 *
 * <p>Tentar em ordem custa espera no pior caso e resolve o caso comum, que é o
 * primeiro estar ocupado. Configurar {@code VITE_OVERPASS_URL} substitui a
 * lista inteira, para quem tiver um espelho próprio.
 */
/*
 * Todos os espelhos daqui precisam servir o planeta inteiro. O overpass.osm.ch
 * esteve nesta lista e só serve a Suíça: para qualquer caixa brasileira ele
 * responde 200 com zero elementos, e o código lia esse vazio como verdade —
 * "a rodovia não aparece mapeada" sobre uma rodovia inteiramente mapeada.
 * Como ele era o último da fila, o defeito só aparecia quando os dois
 * primeiros tropeçavam, que é exatamente o momento em que o fallback existe
 * para valer.
 */
const ESPELHOS_OVERPASS = [
  "https://overpass-api.de/api/interpreter",
  "https://overpass.kumi.systems/api/interpreter",
  "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
  "https://overpass.private.coffee/api/interpreter",
] as const;
const TEMPO_LIMITE_MS = 25_000;

/**
 * O que se tenta de novo no espelho seguinte.
 *
 * <p>Ocupado, sobrecarregado ou atrás de um portal quebrado é circunstância do
 * espelho e não da pergunta. Recusa de conteúdo — 400 na consulta malformada —
 * seria igual em todos, e insistir só faria a pessoa esperar três vezes pela
 * mesma negativa.
 */
const ESTADOS_QUE_MERECEM_OUTRO_ESPELHO = new Set([408, 429, 500, 502, 503, 504]);
/** O recorte máximo de vértices enviado ao cadastro: curva preservada, payload contido. */
const MAXIMO_DE_VERTICES = 800;
/** Dois marcos coladas não seguram régua nenhuma: exigem-se 2 km entre eles. */
const AMPLITUDE_MINIMA_DOS_MARCOS_KM = 2;
/** Marco a mais que isto da linha é de outra pista ou de outra rodovia. */
const AFASTAMENTO_MAXIMO_DO_MARCO_M = 250;
/**
 * Quanto a caixa da cidade cresce, em graus, antes de perguntar ao mapa.
 *
 * <p>A malha paulista tem marco quilométrico mapeado a cada 30–40 km — em
 * Pirassununga, os vizinhos do km 215 são o 182 e o 253. A caixa municipal
 * raramente contém dois deles, e régua com um marco só não se sustenta: a
 * amplitude mínima nunca fecha e o traçado recusava uma rodovia perfeitamente
 * mapeada. Três décimos de grau (~33 km) para cada lado alcançam o marco
 * vizinho no caso típico sem transformar a consulta num download de estado.
 */
const MARGEM_DA_CAIXA_GRAUS = 0.3;

function servicosOverpass(): readonly string[] {
  const configurado = import.meta.env?.VITE_OVERPASS_URL?.trim?.();
  return configurado ? [configurado] : ESPELHOS_OVERPASS;
}

/** "SP-330", "SP 330", "sp330" — a referência como o OSM a escreve: "SP-330". */
export function referenciaDaRodovia(rodovia: string): string | null {
  const compacta = rodovia.trim().toUpperCase().replace(/[\s-]+/g, "");
  const partes = /^([A-Z]{2,3})0*(\d{1,4})$/.exec(compacta);
  if (!partes) return null;
  return `${partes[1]}-${partes[2]}`;
}

export interface MarcoQuilometrico {
  lat: number;
  lng: number;
  km: number;
}

export interface RodoviaDoMapa {
  /** Trechos da rodovia como o OSM os entrega, cada um uma sequência de pontos. */
  trechos: PontoGeografico[][];
  marcos: MarcoQuilometrico[];
}

/**
 * A consulta Overpass: a rodovia pela referência + os marcos colados nela,
 * na caixa da cidade crescida pela margem.
 *
 * <p>Duas lições de campo moram aqui. A referência entra com {@code (^|;)}
 * porque a rodovia concorrida pode listar a outra primeiro — a Anhanguera em
 * Pirassununga é {@code SP-330;BR-050}, e quem cadastrasse a obra como BR-050
 * não achava rodovia nenhuma com o regex ancorado no começo. E os marcos são
 * buscados ao longo da rodovia ({@code around}), não soltos na caixa: o marco
 * de outra estrada que cruza a cidade não serve de régua, e o da própria
 * rodovia um pouco além da divisa municipal serve — era ele que faltava.
 */
export function consultaOverpass(
  referencia: string,
  limites: [[number, number], [number, number]],
): string {
  const [[lngMin, latMin], [lngMax, latMax]] = limites;
  // Arredondada porque -47,7 - 0,3 em ponto flutuante é -48,00000000000001,
  // e a consulta não precisa carregar esse ruído.
  const borda = (valor: number) => Math.round(valor * 1e6) / 1e6;
  const caixa = [
    borda(latMin - MARGEM_DA_CAIXA_GRAUS),
    borda(lngMin - MARGEM_DA_CAIXA_GRAUS),
    borda(latMax + MARGEM_DA_CAIXA_GRAUS),
    borda(lngMax + MARGEM_DA_CAIXA_GRAUS),
  ].join(",");
  const ref = referencia.replace("-", "[- ]?");
  return `
[out:json][timeout:${Math.floor(TEMPO_LIMITE_MS / 1000)}];
way["highway"]["ref"~"(^|;)${ref}($|;)"](${caixa})->.rodovia;
(
  .rodovia;
  node(around.w.rodovia:${AFASTAMENTO_MAXIMO_DO_MARCO_M})["highway"="milestone"]["distance"];
);
out geom;
`.trim();
}

function numero(valor: unknown): number | null {
  if (typeof valor === "number" && Number.isFinite(valor)) return valor;
  if (typeof valor !== "string") return null;
  const convertido = Number(valor.replace(",", "."));
  return Number.isFinite(convertido) ? convertido : null;
}

/** Lê a resposta do Overpass sem confiar nela: lixo vira ausência, não erro. */
export function rodoviaDaResposta(valor: unknown): RodoviaDoMapa {
  const trechos: PontoGeografico[][] = [];
  const marcos: MarcoQuilometrico[] = [];
  const raiz =
    typeof valor === "object" && valor !== null
      ? (valor as Record<string, unknown>)
      : {};
  const elementos = Array.isArray(raiz.elements) ? raiz.elements : [];
  for (const bruto of elementos) {
    if (typeof bruto !== "object" || bruto === null) continue;
    const elemento = bruto as Record<string, unknown>;
    if (elemento.type === "way" && Array.isArray(elemento.geometry)) {
      const pontos: PontoGeografico[] = [];
      for (const par of elemento.geometry) {
        const p = par as Record<string, unknown>;
        const lat = numero(p.lat);
        const lng = numero(p.lon);
        if (lat !== null && lng !== null) pontos.push({ lat, lng });
      }
      if (pontos.length >= 2) trechos.push(pontos);
    }
    if (elemento.type === "node") {
      const etiquetas =
        typeof elemento.tags === "object" && elemento.tags !== null
          ? (elemento.tags as Record<string, unknown>)
          : {};
      const km = numero(etiquetas.distance);
      const lat = numero(elemento.lat);
      const lng = numero(elemento.lon);
      if (km !== null && lat !== null && lng !== null) {
        marcos.push({ lat, lng, km });
      }
    }
  }
  return { trechos, marcos };
}

const RAIO_DA_TERRA_M = 6_371_008.8;

function radianos(graus: number): number {
  return (graus * Math.PI) / 180;
}

function distanciaM(a: PontoGeografico, b: PontoGeografico): number {
  const dLat = radianos(b.lat - a.lat);
  const dLng = radianos(b.lng - a.lng);
  const seno =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(radianos(a.lat)) *
      Math.cos(radianos(b.lat)) *
      Math.sin(dLng / 2) ** 2;
  return 2 * RAIO_DA_TERRA_M * Math.asin(Math.min(1, Math.sqrt(seno)));
}

/** A que distância a pista oposta da dupla corre da costura: colada. */
const LIMIAR_DE_PISTA_OPOSTA_M = 100;
/**
 * Fração do pedaço que precisa correr colada para ele ser a volta.
 *
 * <p>Não é 100% de propósito: a continuação legítima de uma serra em zigue-zague
 * passa perto da costura logo depois do grampo, mas diverge no resto do corpo.
 * A pista oposta de uma dupla corre colada do começo ao fim.
 */
const FRACAO_QUE_DENUNCIA_A_VOLTA = 0.7;
/** Quantos pontos do candidato são medidos contra a costura. */
const AMOSTRAS_DO_CANDIDATO = 12;

/** Menor distância de um ponto à polilinha, em metros. */
function afastamentoDaLinhaM(
  linha: readonly PontoGeografico[],
  alvo: PontoGeografico,
): number {
  let melhor = Number.POSITIVE_INFINITY;
  for (let i = 1; i < linha.length; i += 1) {
    const a = linha[i - 1];
    const b = linha[i];
    const dx = b.lng - a.lng;
    const dy = b.lat - a.lat;
    const denominador = dx * dx + dy * dy;
    const fracao =
      denominador <= 0
        ? 0
        : Math.min(
            1,
            Math.max(
              0,
              ((alvo.lng - a.lng) * dx + (alvo.lat - a.lat) * dy) /
                denominador,
            ),
          );
    const projetado = { lat: a.lat + dy * fracao, lng: a.lng + dx * fracao };
    melhor = Math.min(melhor, distanciaM(alvo, projetado));
  }
  return melhor;
}

/**
 * O pedaço é a pista oposta correndo de volta pela costura já feita?
 *
 * <p>Numa pista dupla, cada sentido é uma linha própria a vinte ou sessenta
 * metros da outra — e nas bordas do recorte as pontas das duas ficam a menos
 * de quinhentos metros, dentro da tolerância de emenda. A costura descia uma
 * pista inteira e voltava pela outra: em Pirassununga, 64,7 km de linha para
 * um vão real de 32,3. Com a régua dobrada, o quilômetro cresce na ida e
 * "volta" na vinda, e nenhum conjunto de marcos calibra uma linha assim.
 */
function ehAVoltaPelaPistaOposta(
  costura: readonly PontoGeografico[],
  candidato: readonly PontoGeografico[],
): boolean {
  if (costura.length < 2 || candidato.length === 0) return false;
  /*
   * A medição corre contra uma costura decimada: numa rodovia longa a linha
   * junta milhares de vértices, e a pergunta aqui não pede essa resolução —
   * a pista oposta corre colada por quilômetros, não por um vão de esquina.
   */
  const linha =
    costura.length > 400 ? enxugar(costura, 400) : costura;
  const passo = Math.max(
    1,
    Math.floor(candidato.length / AMOSTRAS_DO_CANDIDATO),
  );
  let medidos = 0;
  let colados = 0;
  for (let i = 0; i < candidato.length; i += passo) {
    medidos += 1;
    if (
      afastamentoDaLinhaM(linha, candidato[i]) <= LIMIAR_DE_PISTA_OPOSTA_M
    ) {
      colados += 1;
    }
  }
  return medidos > 0 && colados / medidos >= FRACAO_QUE_DENUNCIA_A_VOLTA;
}

/**
 * Costura os trechos do OSM numa polilinha só.
 *
 * <p>A rodovia chega picada — um way por quarteirão de atributos — e fora de
 * ordem. A costura é gulosa pelas pontas: começa no trecho mais longo e vai
 * anexando o trecho cujo extremo está mais próximo de uma das pontas da
 * costura, invertendo-o quando preciso. Trecho longe demais (mais de 500 m de
 * vão) fica de fora: é outra pista ou outro pedaço da cidade, e forçar a
 * emenda desenharia uma régua que salta. E o trecho que corre colado à
 * costura já feita também fica de fora — é a pista oposta da dupla, cuja
 * anexação dobraria a linha de volta sobre si mesma.
 */
export function costurarTrechos(
  trechos: readonly PontoGeografico[][],
): PontoGeografico[] {
  if (trechos.length === 0) return [];
  const restantes = [...trechos].sort(
    (a, b) => comprimento(b) - comprimento(a),
  );
  let costura = [...(restantes.shift() as PontoGeografico[])];

  let progrediu = true;
  while (progrediu && restantes.length > 0) {
    progrediu = false;
    const inicio = costura[0];
    const fim = costura[costura.length - 1];
    let melhor = -1;
    let melhorVao = 500;
    let anexarNoFim = true;
    let inverter = false;
    for (let i = 0; i < restantes.length; i += 1) {
      const trecho = restantes[i];
      const casos: [number, boolean, boolean][] = [
        [distanciaM(fim, trecho[0]), true, false],
        [distanciaM(fim, trecho[trecho.length - 1]), true, true],
        [distanciaM(inicio, trecho[trecho.length - 1]), false, false],
        [distanciaM(inicio, trecho[0]), false, true],
      ];
      for (const [vao, noFim, invertido] of casos) {
        if (vao < melhorVao) {
          melhorVao = vao;
          melhor = i;
          anexarNoFim = noFim;
          inverter = invertido;
        }
      }
    }
    if (melhor >= 0) {
      const [trecho] = restantes.splice(melhor, 1);
      /*
       * Descartar sem anexar mantém o laço vivo: o mesmo lugar na fila pode
       * ter, logo atrás, o pedaço legítimo da mesma ponta.
       */
      if (ehAVoltaPelaPistaOposta(costura, trecho)) {
        progrediu = true;
        continue;
      }
      const pedaco = inverter ? [...trecho].reverse() : [...trecho];
      costura = anexarNoFim
        ? [...costura, ...pedaco.slice(1)]
        : [...pedaco.slice(0, -1), ...costura];
      progrediu = true;
    }
  }
  return costura;
}

function comprimento(pontos: readonly PontoGeografico[]): number {
  let total = 0;
  for (let i = 1; i < pontos.length; i += 1) {
    total += distanciaM(pontos[i - 1], pontos[i]);
  }
  return total;
}

/** Distância acumulada de cada vértice ao primeiro, em metros. */
function acumulado(pontos: readonly PontoGeografico[]): number[] {
  const somas = [0];
  for (let i = 1; i < pontos.length; i += 1) {
    somas.push(somas[i - 1] + distanciaM(pontos[i - 1], pontos[i]));
  }
  return somas;
}

/** Onde, ao longo da linha, cai a projeção de um ponto — em metros do início. */
function aoLongoDaLinha(
  pontos: readonly PontoGeografico[],
  somas: readonly number[],
  alvo: PontoGeografico,
): { aoLongoM: number; afastamentoM: number } {
  let melhor = { aoLongoM: 0, afastamentoM: Number.POSITIVE_INFINITY };
  for (let i = 1; i < pontos.length; i += 1) {
    const a = pontos[i - 1];
    const b = pontos[i];
    const dx = b.lng - a.lng;
    const dy = b.lat - a.lat;
    const denominador = dx * dx + dy * dy;
    const fracao =
      denominador <= 0
        ? 0
        : Math.min(
            1,
            Math.max(
              0,
              ((alvo.lng - a.lng) * dx + (alvo.lat - a.lat) * dy) /
                denominador,
            ),
          );
    const projetado = { lat: a.lat + dy * fracao, lng: a.lng + dx * fracao };
    const afastamento = distanciaM(alvo, projetado);
    if (afastamento < melhor.afastamentoM) {
      melhor = {
        aoLongoM: somas[i - 1] + distanciaM(a, projetado),
        afastamentoM: afastamento,
      };
    }
  }
  return melhor;
}

export interface EixoCalibrado {
  pontos: PontoGeografico[];
  kmInicial: number;
  kmFinal: number;
  marcosUsados: number;
}

/**
 * Calibra o quilômetro da linha pelos marcos e devolve o eixo pronto.
 *
 * <p>Cada marco vira um par (distância ao longo da linha, km declarado no
 * marco); a régua é a reta de mínimos quadrados desses pares. Marco longe da
 * linha (mais de 250 m) é de outra pista ou de outra rodovia e fica de fora.
 * Sem dois marcos aproveitáveis com pelo menos {@link
 * AMPLITUDE_MINIMA_DOS_MARCOS_KM} km entre eles, não há régua — e régua
 * chutada é pior que régua nenhuma.
 */
export function calibrarPelosMarcos(
  linha: readonly PontoGeografico[],
  marcos: readonly MarcoQuilometrico[],
): EixoCalibrado | null {
  if (linha.length < 2) return null;
  const somas = acumulado(linha);
  const pares: { aoLongoM: number; km: number }[] = [];
  for (const marco of marcos) {
    const projecao = aoLongoDaLinha(linha, somas, marco);
    if (projecao.afastamentoM <= AFASTAMENTO_MAXIMO_DO_MARCO_M) {
      pares.push({ aoLongoM: projecao.aoLongoM, km: marco.km });
    }
  }
  if (pares.length < 2) return null;
  const kms = pares.map((par) => par.km);
  if (
    Math.max(...kms) - Math.min(...kms) <
    AMPLITUDE_MINIMA_DOS_MARCOS_KM
  ) {
    return null;
  }

  const n = pares.length;
  const mediaX = pares.reduce((soma, par) => soma + par.aoLongoM, 0) / n;
  const mediaY = pares.reduce((soma, par) => soma + par.km, 0) / n;
  let numerador = 0;
  let denominador = 0;
  for (const par of pares) {
    numerador += (par.aoLongoM - mediaX) * (par.km - mediaY);
    denominador += (par.aoLongoM - mediaX) ** 2;
  }
  if (denominador === 0) return null;
  const inclinacao = numerador / denominador;
  // Um quilômetro tem mil metros: régua saudável tem |inclinação| perto de
  // 0,001 km/m. Fora de 30% disso, os marcos não descrevem esta linha.
  const porKm = Math.abs(inclinacao) * 1000;
  if (porKm < 0.7 || porKm > 1.3) return null;

  const kmDe = (aoLongoM: number) =>
    mediaY + inclinacao * (aoLongoM - mediaX);
  const pontos = enxugar(linha, MAXIMO_DE_VERTICES);
  return {
    pontos,
    kmInicial: arredondaKm(kmDe(0)),
    kmFinal: arredondaKm(kmDe(somas[somas.length - 1])),
    marcosUsados: pares.length,
  };
}

function arredondaKm(valor: number): number {
  return Math.round(valor * 1000) / 1000;
}

/** Reduz vértices preservando o desenho: pega 1 a cada passo, pontas sempre. */
function enxugar(
  pontos: readonly PontoGeografico[],
  maximo: number,
): PontoGeografico[] {
  if (pontos.length <= maximo) return [...pontos];
  const passo = (pontos.length - 1) / (maximo - 1);
  const saida: PontoGeografico[] = [];
  for (let i = 0; i < maximo; i += 1) {
    saida.push(pontos[Math.round(i * passo)]);
  }
  return saida;
}

export class TracadoRecusado extends Error {}

/**
 * Faz a pergunta ao mapa público, insistindo espelho a espelho.
 *
 * <p>Devolve o JSON do primeiro que responder <em>com conteúdo útil</em>. Um
 * 200 vazio de um espelho não encerra a busca enquanto houver outro para
 * ouvir: foi assim que um espelho regional — que serve outra parte do mundo e
 * responde vazio para qualquer caixa daqui — transformava "espelho ocupado"
 * em "a rodovia não aparece mapeada". O vazio só vale como resposta quando o
 * último espelho o confirma: aí ele é o mapa dizendo que não tem, e não um
 * servidor dizendo que não sabe.
 *
 * <p>Só desiste quando a lista inteira falhou, e a frase final diz o que
 * aconteceu no último — "recusou (429)" e "não respondeu" pedem coisas
 * diferentes de quem lê.
 */
async function consultarMapaPublico(
  consulta: string,
  fetchImpl: typeof fetch,
  respostaUtil: (json: unknown) => boolean = () => true,
): Promise<unknown> {
  const espelhos = servicosOverpass();
  let ultimoMotivo = "não respondeu";
  let ultimaRespostaVazia: unknown;
  let houveRespostaVazia = false;

  for (const espelho of espelhos) {
    const controlador = new AbortController();
    const cronometro = setTimeout(() => controlador.abort(), TEMPO_LIMITE_MS);
    try {
      const resposta = await fetchImpl(espelho, {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: `data=${encodeURIComponent(consulta)}`,
        signal: controlador.signal,
      });
      if (resposta.ok) {
        const json: unknown = await resposta.json();
        if (respostaUtil(json)) return json;
        ultimaRespostaVazia = json;
        houveRespostaVazia = true;
        ultimoMotivo = "respondeu sem a rodovia";
        continue;
      }
      ultimoMotivo = `recusou a consulta (${resposta.status})`;
      if (!ESTADOS_QUE_MERECEM_OUTRO_ESPELHO.has(resposta.status)) break;
    } catch {
      ultimoMotivo = "não respondeu";
    } finally {
      clearTimeout(cronometro);
    }
  }

  if (houveRespostaVazia) return ultimaRespostaVazia;

  throw new TracadoRecusado(
    `O serviço de mapa público ${ultimoMotivo}; tente de novo daqui a pouco` +
      " ou cadastre à mão.",
  );
}

/**
 * Traça o eixo da obra a partir do endereço do cadastro.
 *
 * <p>Cidade enquadra, rodovia acha a linha, marcos calibram. Qualquer elo
 * ausente recusa com o motivo — quem chamou mostra a frase e oferece o
 * cadastro manual, que continua existindo.
 */
export async function tracarEixoPelaRodovia(
  endereco: EnderecoDaObra,
  fetchImpl: typeof fetch | undefined = typeof fetch === "undefined"
    ? undefined
    : fetch,
): Promise<EixoCalibrado> {
  const rodovia = endereco.rodovia?.trim();
  if (!rodovia) {
    throw new TracadoRecusado(
      "A obra não declara rodovia no cadastro — sem ela não há o que traçar.",
    );
  }
  const referencia = referenciaDaRodovia(rodovia);
  if (!referencia) {
    throw new TracadoRecusado(
      `Não reconheci "${rodovia}" como referência de rodovia (esperava algo como SP-330).`,
    );
  }
  if (!fetchImpl) {
    throw new TracadoRecusado("Sem rede não há mapa público para consultar.");
  }

  const enquadramento = await buscarEnquadramentoAproximado(
    endereco,
    fetchImpl,
  );
  if (!enquadramento?.limites) {
    throw new TracadoRecusado(
      "Não encontrei a cidade do cadastro no mapa para delimitar a busca.",
    );
  }

  const rodoviaDoMapa = rodoviaDaResposta(
    await consultarMapaPublico(
      consultaOverpass(referencia, enquadramento.limites),
      fetchImpl,
      (json) => rodoviaDaResposta(json).trechos.length > 0,
    ),
  );
  if (rodoviaDoMapa.trechos.length === 0) {
    throw new TracadoRecusado(
      `A ${referencia} não aparece mapeada na região de ${
        endereco.cidade ?? "cadastro"
      } — cadastre o eixo à mão.`,
    );
  }
  const linha = costurarTrechos(rodoviaDoMapa.trechos);
  const calibrado = calibrarPelosMarcos(linha, rodoviaDoMapa.marcos);
  if (!calibrado) {
    throw new TracadoRecusado(
      "Achei a rodovia, mas não marcos quilométricos suficientes para" +
        " calibrar a régua — cadastre o eixo à mão, com o km das pontas.",
    );
  }
  return calibrado;
}
