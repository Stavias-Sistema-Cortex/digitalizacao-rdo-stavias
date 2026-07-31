import {
  apiFetch,
  readResponseBody,
  responseErrorMessage,
} from "../../../lib/api/apiClient";
import { falhaEhAusenciaDeRede } from "./leituraOffline";
import {
  featureDoRegistro,
  listarGeometriasLocais,
  reconciliarGeometriasDoServidor,
} from "./obraGeoCacheRepository";
import type {
  ObraMapFeature,
  OperationalGeometry,
  OperationalGeometryType,
  WorksiteMapPoint,
} from "./mapGeometry";

const GEOMETRY_TYPES = new Set<OperationalGeometryType>([
  "Point",
  "MultiPoint",
  "LineString",
  "MultiLineString",
  "Polygon",
  "MultiPolygon",
]);

export interface ObraMapData {
  obra: WorksiteMapPoint;
  features: ObraMapFeature[];
}

function objectValue(value: unknown): Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}

function nullableNumber(value: unknown): number | null {
  if (value === null || value === undefined || value === "") {
    return null;
  }
  const parsed = typeof value === "number" ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function nullableString(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value : null;
}

function geometryFromApi(value: unknown): OperationalGeometry | null {
  const object = objectValue(value);
  if (
    typeof object.type !== "string" ||
    !GEOMETRY_TYPES.has(object.type as OperationalGeometryType) ||
    !Array.isArray(object.coordinates)
  ) {
    return null;
  }

  return {
    type: object.type as OperationalGeometryType,
    coordinates: object.coordinates,
  };
}

function featureFromApi(value: unknown): ObraMapFeature | null {
  const object = objectValue(value);
  const geometry = geometryFromApi(object.geometry);
  if (
    typeof object.id !== "string" ||
    !object.id ||
    typeof object.categoria !== "string" ||
    !geometry
  ) {
    return null;
  }

  return {
    id: object.id,
    categoria: object.categoria,
    objetoTipo: nullableString(object.objetoTipo),
    objetoId: nullableString(object.objetoId),
    geometry,
    properties: objectValue(object.properties),
    fonte: nullableString(object.fonte) ?? "DESCONHECIDA",
    versao: nullableNumber(object.versao) ?? 0,
    validoDesde: nullableString(object.validoDesde) ?? "",
    validoAte: nullableString(object.validoAte),
  };
}

export function obraMapResponseFromApi(value: unknown): ObraMapData {
  const root = objectValue(value);
  const obra = objectValue(root.obra);
  const id = nullableString(obra.id);
  const nome = nullableString(obra.nome);
  if (!id || !nome) {
    throw new Error("Resposta do mapa não identifica a obra.");
  }

  return {
    obra: {
      id,
      nome,
      latitude: nullableNumber(obra.latitude),
      longitude: nullableNumber(obra.longitude),
    },
    features: Array.isArray(root.features)
      ? root.features.flatMap((item) => {
          const feature = featureFromApi(item);
          return feature ? [feature] : [];
        })
      : [],
  };
}

export async function buscarMapaObra(obraId: string): Promise<ObraMapData> {
  const response = await apiFetch(
    `/obras/${encodeURIComponent(obraId)}/mapa`,
  );
  const body = await readResponseBody(response);
  if (!response.ok) {
    throw new Error(responseErrorMessage(body, response.status));
  }
  return obraMapResponseFromApi(body);
}

export type OrigemLeituraMapa = "REDE" | "CACHE_LOCAL";

export interface LeituraMapaObra {
  dados: ObraMapData;
  origem: OrigemLeituraMapa;
  /** Instante da última confirmação do servidor para estas camadas. */
  obtidoEm: string | null;
}

/**
 * Lê as camadas da obra combinando servidor e dispositivo.
 *
 * Com rede, a resposta autoritativa reconcilia o armazenamento local e o
 * resultado devolvido já inclui as geometrias desenhadas ou capturadas que ainda
 * não subiram. Sem rede, a leitura vem inteira do dispositivo e a origem é
 * declarada, para que a tela informe que está mostrando o que já tinha em vez de
 * fingir que consultou agora.
 */
export async function carregarMapaObra(
  obra: WorksiteMapPoint,
): Promise<LeituraMapaObra> {
  const agora = new Date().toISOString();
  try {
    const dados = await buscarMapaObra(obra.id);
    await reconciliarGeometriasDoServidor(obra.id, dados.features, agora)
      .catch(() => undefined);
    const locais = await listarGeometriasLocais(obra.id).catch(() => []);
    return {
      dados: {
        obra: dados.obra,
        features: locais.length > 0 ? locais.map(featureDoRegistro) : dados.features,
      },
      origem: "REDE",
      obtidoEm: agora,
    };
  } catch (reason) {
    // Erro do servidor precisa subir: só a falta de transporte autoriza cair
    // para o que o dispositivo já tinha.
    if (!falhaEhAusenciaDeRede(reason)) {
      throw reason;
    }
    const locais = await listarGeometriasLocais(obra.id).catch(() => null);
    if (locais === null) {
      throw reason;
    }
    const confirmadoEm = locais
      .map((registro) => registro.fetchedAt)
      .filter((valor): valor is string => Boolean(valor))
      .sort()
      .at(-1) ?? null;
    return {
      dados: { obra, features: locais.map(featureDoRegistro) },
      origem: "CACHE_LOCAL",
      obtidoEm: confirmadoEm,
    };
  }
}
