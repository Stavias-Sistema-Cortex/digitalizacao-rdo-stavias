import { getCortexDb } from "../../../lib/db/cortexDb";
import type {
  LocalRdoControleGeometricoRecord,
  LocalRdoRecord,
} from "../../../lib/db/db.types";
import { herdarPistaDoControle, type SegmentoTrecho } from "./trechoGeometry";

/**
 * O que o apontador lançou no RDO e ainda não subiu.
 *
 * O trecho é o desenho do trabalho do dia, e o dia acontece em campo, quase
 * sempre sem rede. Enquanto a projeção do servidor não conhece o RDO, ela é
 * lida aqui direto dos stores locais, com a mesma leitura que o
 * `ObraTrechoService` faz no PostgreSQL: o controle geométrico dá o trecho
 * medido e os serviços executados dão a produção apontada.
 *
 * Nada é inventado. Lançamento sem marcação quilométrica utilizável continua
 * sem posição, exatamente como do lado do servidor.
 */

/** Situações em que o RDO ainda não foi confirmado pelo servidor. */
const NAO_CONFIRMADO = new Set([
  "LOCAL_ONLY",
  "LOCAL_PENDING",
  "PENDING_SYNC",
  "SYNCING",
  "ERROR",
  "CONFLICT",
]);

const DECIMAL = /^\d{1,4}(?:\.\d{1,3})?$/;
const KM_MAIS_METROS = /^(\d{1,4})\+(\d{1,3})$/;

/**
 * Espelha `QuilometroParser` do servidor: as colunas de km são texto em toda a
 * base e chegam como o campo digitou. Texto irreconhecível devolve `null` —
 * nunca zero, que é uma marcação válida no km 0.
 */
export function quilometroDeTexto(bruto: unknown): number | null {
  if (typeof bruto !== "string" && typeof bruto !== "number") {
    return null;
  }
  const normalizado = String(bruto)
    .trim()
    .toUpperCase()
    .replaceAll("KM", "")
    .replaceAll(" ", "")
    .replace(",", ".");
  if (!normalizado) {
    return null;
  }
  const kmMaisMetros = KM_MAIS_METROS.exec(normalizado);
  if (kmMaisMetros) {
    return Number(kmMaisMetros[1]) + Number(kmMaisMetros[2]) / 1000;
  }
  return DECIMAL.test(normalizado) ? Number(normalizado) : null;
}

function texto(valor: unknown): string | null {
  return typeof valor === "string" && valor.trim() ? valor.trim() : null;
}

function numero(valor: unknown): number | null {
  if (valor === null || valor === undefined || valor === "") {
    return null;
  }
  const convertido = typeof valor === "number" ? valor : Number(valor);
  return Number.isFinite(convertido) ? convertido : null;
}

function objeto(valor: unknown): Record<string, unknown> {
  return typeof valor === "object" && valor !== null && !Array.isArray(valor)
    ? (valor as Record<string, unknown>)
    : {};
}

function lista(valor: unknown): Record<string, unknown>[] {
  return Array.isArray(valor) ? valor.map(objeto) : [];
}

/**
 * Extensão medida do controle geométrico.
 *
 * O comprimento lançado é o dado do apontador e prevalece. Só quando ele falta
 * a extensão é derivada dos quilômetros — nunca o contrário.
 */
function extensaoDoControle(
  payload: Record<string, unknown>,
  kmInicial: number | null,
  kmFinal: number | null,
): number | null {
  const comprimento = numero(payload.comprimentoM);
  if (comprimento !== null) {
    return comprimento;
  }
  if (kmInicial === null || kmFinal === null) {
    return null;
  }
  return Math.abs(kmFinal - kmInicial) * 1000;
}

/**
 * Converte um RDO do dispositivo nos segmentos que ele descreve.
 *
 * Exportada separada da leitura do banco para que a regra — que é a parte que
 * erra — seja testável sem IndexedDB.
 */
export function segmentosDoRdoLocal(
  rdo: LocalRdoRecord,
  controles: readonly LocalRdoControleGeometricoRecord[],
): SegmentoTrecho[] {
  const payload = objeto(rdo.payload);
  const data = rdo.dataRdo || texto(payload.dataRdo);
  const numeroRdo = rdo.numeroRdo || texto(payload.numeroRdo);
  const comum = {
    rdoId: rdo.id,
    numeroRdo,
    data,
    rdoStatus: rdo.statusRdo,
    procedencia: "DISPOSITIVO" as const,
  };

  const doControle = controles.map((controle) => {
    const item = objeto(controle.payload);
    const kmInicial = quilometroDeTexto(item.kmInicial);
    const kmFinal = quilometroDeTexto(item.kmFinal);
    return {
      ...comum,
      id: `local:${rdo.id}:controle:${controle.id}`,
      origem: "RDO_CONTROLE" as const,
      servicoNome: texto(item.atividadeObservacoes),
      subtrecho: texto(item.subtrecho),
      sentido: null,
      pista: texto(item.pista),
      faixa: texto(item.faixa),
      kmInicial,
      kmFinal,
      estacaInicial: texto(item.estacaInicial),
      estacaFinal: texto(item.estacaFinal),
      extensaoM: extensaoDoControle(item, kmInicial, kmFinal),
      larguraM: numero(item.larguraM),
      areaM2: null,
      massaTonelada: null,
      status: rdo.statusRdo,
      pistaInferida: false,
    } satisfies SegmentoTrecho;
  });

  const doServico = lista(payload.servicosExecutados).map((item, indice) => ({
    ...comum,
    id: `local:${rdo.id}:servico:${texto(item.id) ?? indice}`,
    origem: "EXECUCAO_SERVICO" as const,
    servicoNome: texto(item.servicoNome),
    subtrecho: texto(item.localizacao),
    sentido: null,
    pista: texto(item.pista),
    faixa: texto(item.faixa),
    kmInicial: quilometroDeTexto(item.trechoInicial),
    kmFinal: quilometroDeTexto(item.trechoFinal),
    estacaInicial: null,
    estacaFinal: null,
    extensaoM: null,
    larguraM: null,
    areaM2: null,
    massaTonelada: null,
    status: texto(item.statusValidacao),
    pistaInferida: false,
  })) satisfies SegmentoTrecho[];

  // Mesma regra do servidor: serviço sem pista herda a do controle geométrico
  // deste mesmo RDO, marcado como inferência. Aplicada aqui porque estes
  // segmentos ainda não passaram pela projeção autoritativa.
  return herdarPistaDoControle([...doControle, ...doServico]);
}

/** Rodovia declarada pelo RDO mais recente que a informou. */
export function rodoviaDosRdosLocais(
  rdos: readonly LocalRdoRecord[],
): string | null {
  for (const rdo of [...rdos].sort((a, b) =>
    b.dataRdo.localeCompare(a.dataRdo),
  )) {
    const rodovia = texto(objeto(rdo.payload).rodovia);
    if (rodovia) {
      return rodovia;
    }
  }
  return null;
}

export interface LancamentosLocais {
  segmentos: SegmentoTrecho[];
  rodovia: string | null;
}

/**
 * Lê do dispositivo os lançamentos da obra que o servidor ainda não conhece.
 *
 * RDO já sincronizado é deliberadamente ignorado: ele volta pela projeção
 * autoritativa, com os identificadores e os estados que o servidor atribuiu.
 */
export async function lancamentosLocaisDaObra(
  obraId: string,
): Promise<LancamentosLocais> {
  const database = await getCortexDb();
  const rdos = (
    await database.getAllFromIndex("rdos", "by-obra-id", obraId)
  ).filter((rdo) => NAO_CONFIRMADO.has(rdo.syncStatus));

  if (rdos.length === 0) {
    return { segmentos: [], rodovia: null };
  }

  const segmentos: SegmentoTrecho[] = [];
  for (const rdo of rdos) {
    const controles = await database.getAllFromIndex(
      "rdoControlesGeometricos",
      "by-rdo-id",
      rdo.id,
    );
    segmentos.push(...segmentosDoRdoLocal(rdo, controles));
  }

  return { segmentos, rodovia: rodoviaDosRdosLocais(rdos) };
}
