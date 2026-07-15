import {
  apiFetch,
  readResponseBody,
  responseErrorMessage,
} from "../../../lib/api/apiClient";
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
