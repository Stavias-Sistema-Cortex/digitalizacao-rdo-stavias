import {
  apiFetch,
  readResponseBody,
  responseErrorMessage,
} from "../../../lib/api/apiClient";
import { falhaEhAusenciaDeRede } from "../map/leituraOffline";
import {
  gravarTrechoEmCache,
  lerTrechoEmCache,
} from "../map/obraGeoCacheRepository";
import {
  mesclarLancamentosLocais,
  projecaoDoDispositivo,
} from "./trechoGeometry";
import { lancamentosLocaisDaObra } from "./trechoLocal";
import type {
  DiaExecutado,
  OrigemSegmento,
  Periodo,
  ProjecaoTrecho,
  ResumoTrecho,
  SegmentoTrecho,
} from "./trechoGeometry";

/**
 * Origens aceitas na leitura do servidor.
 *
 * Era uma lista literal mantida à mão, e um segmento de origem ausente dela
 * era descartado em silêncio — a projeção chegava correta e sumia antes de
 * virar bloco. A checagem abaixo quebra a compilação quando o tipo ganha uma
 * origem que este parser não conhece, em vez de deixar o dado evaporar.
 */
const ORIGENS_ACEITAS = [
  "PROGRAMACAO",
  "RDO_CONTROLE",
  "EXECUCAO_SERVICO",
  "CADASTRO_MAPA",
] as const satisfies readonly OrigemSegmento[];

type OrigemSemLeitura = Exclude<
  OrigemSegmento,
  (typeof ORIGENS_ACEITAS)[number]
>;

const _TODA_ORIGEM_TEM_LEITURA: [OrigemSemLeitura] extends [never]
  ? true
  : never = true;
void _TODA_ORIGEM_TEM_LEITURA;

const ORIGENS = new Set<OrigemSegmento>(ORIGENS_ACEITAS);

export type OrigemLeitura = "REDE" | "CACHE_LOCAL" | "DISPOSITIVO";

export interface LeituraTrecho {
  projecao: ProjecaoTrecho;
  origem: OrigemLeitura;
  /** Instante em que esta projeção foi obtida do servidor. */
  obtidoEm: string;
}

function objectValue(value: unknown): Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}

/**
 * Converte apenas números realmente presentes. Ausência permanece nula: um
 * quilômetro ou uma extensão que o RDO não registrou não pode virar zero.
 */
function nullableNumber(value: unknown): number | null {
  if (value === null || value === undefined || value === "") {
    return null;
  }
  const parsed = typeof value === "number" ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function requiredNumber(value: unknown): number {
  return nullableNumber(value) ?? 0;
}

function nullableString(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function stringList(value: unknown): string[] {
  return Array.isArray(value)
    ? value.flatMap((item) => {
        const text = nullableString(item);
        return text ? [text] : [];
      })
    : [];
}

function origem(value: unknown): OrigemSegmento | null {
  return typeof value === "string" && ORIGENS.has(value as OrigemSegmento)
    ? (value as OrigemSegmento)
    : null;
}

function segmentoFromApi(value: unknown): SegmentoTrecho | null {
  const item = objectValue(value);
  const id = nullableString(item.id);
  const origemSegmento = origem(item.origem);
  if (!id || !origemSegmento) {
    return null;
  }
  return {
    id,
    origem: origemSegmento,
    rdoId: nullableString(item.rdoId),
    numeroRdo: nullableString(item.numeroRdo),
    data: nullableString(item.data),
    servicoNome: nullableString(item.servicoNome),
    subtrecho: nullableString(item.subtrecho),
    sentido: nullableString(item.sentido),
    pista: nullableString(item.pista),
    faixa: nullableString(item.faixa),
    kmInicial: nullableNumber(item.kmInicial),
    kmFinal: nullableNumber(item.kmFinal),
    estacaInicial: nullableString(item.estacaInicial),
    estacaFinal: nullableString(item.estacaFinal),
    extensaoM: nullableNumber(item.extensaoM),
    larguraM: nullableNumber(item.larguraM),
    areaM2: nullableNumber(item.areaM2),
    massaTonelada: nullableNumber(item.massaTonelada),
    status: nullableString(item.status),
    rdoStatus: nullableString(item.rdoStatus),
    // Tudo que vem por esta porta é a projeção autoritativa do servidor.
    procedencia: "SERVIDOR",
    pistaInferida: item.pistaInferida === true,
  };
}

function resumoFromApi(value: unknown): ResumoTrecho {
  const resumo = objectValue(value);
  return {
    totalSegmentos: requiredNumber(resumo.totalSegmentos),
    totalRdos: requiredNumber(resumo.totalRdos),
    totalRascunhos: requiredNumber(resumo.totalRascunhos),
    // Conceito do dispositivo: o servidor não tem o que ainda não recebeu.
    totalPendentes: 0,
    extensaoTotalM: requiredNumber(resumo.extensaoTotalM),
    areaTotalM2: requiredNumber(resumo.areaTotalM2),
    massaTotalTonelada: requiredNumber(resumo.massaTotalTonelada),
    primeiraExecucao: nullableString(resumo.primeiraExecucao),
    ultimaExecucao: nullableString(resumo.ultimaExecucao),
  };
}

const DATA_ISO = /^\d{4}-\d{2}-\d{2}$/;

function dataIso(value: unknown): string | null {
  const texto = nullableString(value);
  return texto && DATA_ISO.test(texto.slice(0, 10)) ? texto.slice(0, 10) : null;
}

function periodoFromApi(value: unknown): Periodo {
  const periodo = objectValue(value);
  return { de: dataIso(periodo.de), ate: dataIso(periodo.ate) };
}

function diaFromApi(value: unknown): DiaExecutado | null {
  const dia = objectValue(value);
  const data = dataIso(dia.data);
  if (!data) {
    return null;
  }
  return {
    data,
    totalSegmentos: requiredNumber(dia.totalSegmentos),
    totalRdos: requiredNumber(dia.totalRdos),
    extensaoM: requiredNumber(dia.extensaoM),
    massaTonelada: requiredNumber(dia.massaTonelada),
    kmMin: nullableNumber(dia.kmMin),
    kmMax: nullableNumber(dia.kmMax),
  };
}

export function trechoResponseFromApi(value: unknown): ProjecaoTrecho {
  const root = objectValue(value);
  const obraId = nullableString(root.obraId);
  const obraNome = nullableString(root.obraNome);
  if (!obraId || !obraNome) {
    throw new Error("Resposta do trecho não identifica a obra.");
  }

  return {
    obraId,
    obraNome,
    rodovia: nullableString(root.rodovia),
    operavel: root.operavel === true,
    sentidos: stringList(root.sentidos),
    faixas: stringList(root.faixas),
    kmMin: nullableNumber(root.kmMin),
    kmMax: nullableNumber(root.kmMax),
    atualizadoEm: nullableString(root.atualizadoEm),
    periodoDisponivel: periodoFromApi(root.periodoDisponivel),
    periodoAplicado: periodoFromApi(root.periodoAplicado),
    segmentos: Array.isArray(root.segmentos)
      ? root.segmentos.flatMap((item) => {
          const segmento = segmentoFromApi(item);
          return segmento ? [segmento] : [];
        })
      : [],
    diasExecutados: Array.isArray(root.diasExecutados)
      ? root.diasExecutados.flatMap((item) => {
          const dia = diaFromApi(item);
          return dia ? [dia] : [];
        })
      : [],
    resumo: resumoFromApi(root.resumo),
  };
}

/**
 * Busca a projeção linear do trecho, opcionalmente restrita a um período.
 *
 * A resposta da rede alimenta o cache local. Sem rede, a leitura cai para o que
 * já foi consultado neste dispositivo e devolve a origem para que a interface
 * possa dizer de onde veio. Quando não há nem rede nem cache, o erro sobe: uma
 * obra sem projeção conhecida não pode ser apresentada como vazia.
 *
 * O cache é gravado apenas para a leitura sem filtro, que é a que precisa
 * sobreviver offline. Um recorte de datas exige rede — e diz isso — em vez de
 * devolver silenciosamente um período diferente do pedido.
 */
export async function buscarTrechoObra(
  obraId: string,
  periodo: Periodo = { de: null, ate: null },
): Promise<LeituraTrecho> {
  const parametros = new URLSearchParams();
  if (periodo.de) parametros.set("de", periodo.de);
  if (periodo.ate) parametros.set("ate", periodo.ate);
  const consulta = parametros.toString();
  const semFiltro = consulta === "";

  try {
    const response = await apiFetch(
      `/obras/${encodeURIComponent(obraId)}/trecho${
        semFiltro ? "" : `?${consulta}`
      }`,
    );
    const body = await readResponseBody(response);
    if (!response.ok) {
      throw new Error(responseErrorMessage(body, response.status));
    }
    const projecao = trechoResponseFromApi(body);
    const obtidoEm = new Date().toISOString();
    if (semFiltro) {
      await gravarTrechoEmCache(obraId, body, obtidoEm).catch(() => undefined);
    }
    return { projecao, origem: "REDE", obtidoEm };
  } catch (reason) {
    // Um recorte de datas exige rede, e um erro do servidor precisa aparecer:
    // apenas a leitura completa sem transporte cai para o cache local.
    if (!semFiltro || !falhaEhAusenciaDeRede(reason)) {
      throw reason;
    }
    const cached = await lerTrechoEmCache(obraId).catch(() => null);
    if (cached) {
      return {
        projecao: trechoResponseFromApi(cached.payload),
        origem: "CACHE_LOCAL",
        obtidoEm: cached.fetchedAt,
      };
    }
    throw reason;
  }
}

/**
 * Projeção do trecho como o apontador precisa vê-la.
 *
 * É a leitura autoritativa do servidor somada ao que foi lançado neste
 * aparelho e ainda não subiu — o RDO do dia, preenchido em campo, quase sempre
 * sem rede. Sem essa soma o trecho só desenharia o trabalho de ontem.
 *
 * Quando nem a rede nem o cache respondem mas há lançamento local, a projeção é
 * montada só com ele: é melhor mostrar o próprio trabalho marcado como
 * pendente do que um erro.
 */
export async function carregarTrechoDaObra(obra: {
  id: string;
  nome: string;
  operavel: boolean;
}): Promise<LeituraTrecho> {
  const locais = await lancamentosLocaisDaObra(obra.id).catch(() => ({
    segmentos: [],
    rodovia: null,
  }));

  try {
    const leitura = await buscarTrechoObra(obra.id);
    return {
      ...leitura,
      projecao: mesclarLancamentosLocais(leitura.projecao, locais.segmentos),
    };
  } catch (reason) {
    // Erro do servidor continua subindo: só a falta de transporte autoriza
    // apresentar a obra apenas com o que o aparelho tem.
    if (locais.segmentos.length === 0 || !falhaEhAusenciaDeRede(reason)) {
      throw reason;
    }
    return {
      projecao: projecaoDoDispositivo(obra, locais.segmentos, locais.rodovia),
      origem: "DISPOSITIVO",
      obtidoEm: new Date().toISOString(),
    };
  }
}
