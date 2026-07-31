export type OperationalGeometryType =
  | "Point"
  | "MultiPoint"
  | "LineString"
  | "MultiLineString"
  | "Polygon"
  | "MultiPolygon";

export interface OperationalGeometry {
  type: OperationalGeometryType;
  coordinates: unknown;
}

export interface ObraMapFeature {
  id: string;
  categoria: string;
  objetoTipo: string | null;
  objetoId: string | null;
  geometry: OperationalGeometry;
  properties: Record<string, unknown>;
  fonte: string;
  versao: number;
  validoDesde: string;
  validoAte: string | null;
}

export interface WorksiteMapPoint {
  id: string;
  nome: string;
  latitude: number | null;
  longitude: number | null;
}

export interface OperationalFeature {
  type: "Feature";
  id: string;
  geometry: OperationalGeometry;
  properties: Record<string, unknown>;
}

export interface OperationalFeatureCollection {
  type: "FeatureCollection";
  features: OperationalFeature[];
}

export function isValidWorksiteCoordinate(
  latitude: number | null,
  longitude: number | null,
): latitude is number {
  return (
    typeof latitude === "number" &&
    Number.isFinite(latitude) &&
    latitude >= -90 &&
    latitude <= 90 &&
    typeof longitude === "number" &&
    Number.isFinite(longitude) &&
    longitude >= -180 &&
    longitude <= 180
  );
}

export interface LimitesGeograficos {
  oeste: number;
  sul: number;
  leste: number;
  norte: number;
}

/**
 * Extensão real ocupada pelas geometrias da obra.
 *
 * É o que permite enquadrar o mapa exatamente sobre o trecho em vez de centrar
 * num ponto e chutar o zoom. Devolve `null` quando não há coordenada alguma —
 * nesse caso não existe enquadramento honesto a fazer.
 */
export function limitesDaColecao(
  collection: OperationalFeatureCollection,
): LimitesGeograficos | null {
  let oeste = Infinity;
  let sul = Infinity;
  let leste = -Infinity;
  let norte = -Infinity;

  function visitar(value: unknown): void {
    if (
      Array.isArray(value) &&
      value.length >= 2 &&
      typeof value[0] === "number" &&
      typeof value[1] === "number"
    ) {
      const [longitude, latitude] = value as [number, number];
      if (!isValidWorksiteCoordinate(latitude, longitude)) {
        return;
      }
      oeste = Math.min(oeste, longitude);
      leste = Math.max(leste, longitude);
      sul = Math.min(sul, latitude);
      norte = Math.max(norte, latitude);
      return;
    }
    if (Array.isArray(value)) {
      for (const filho of value) {
        visitar(filho);
      }
    }
  }

  for (const feature of collection.features) {
    visitar(feature.geometry.coordinates);
  }

  return Number.isFinite(oeste) && Number.isFinite(sul)
    ? { oeste, sul, leste, norte }
    : null;
}

/** Comprimento aproximado de uma linha, em metros, pela fórmula de Haversine. */
export function comprimentoAproximadoM(geometry: OperationalGeometry): number | null {
  const linhas: [number, number][][] = [];
  if (geometry.type === "LineString") {
    linhas.push(geometry.coordinates as [number, number][]);
  } else if (geometry.type === "MultiLineString") {
    linhas.push(...(geometry.coordinates as [number, number][][]));
  } else {
    return null;
  }

  const RAIO_TERRA_M = 6_371_000;
  const rad = (grau: number) => (grau * Math.PI) / 180;
  let total = 0;

  for (const linha of linhas) {
    for (let i = 1; i < linha.length; i += 1) {
      const [lng1, lat1] = linha[i - 1];
      const [lng2, lat2] = linha[i];
      const dLat = rad(lat2 - lat1);
      const dLng = rad(lng2 - lng1);
      const a =
        Math.sin(dLat / 2) ** 2 +
        Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(dLng / 2) ** 2;
      total += 2 * RAIO_TERRA_M * Math.asin(Math.min(1, Math.sqrt(a)));
    }
  }
  return total > 0 ? total : null;
}

export function buildOperationalFeatureCollection(
  worksite: WorksiteMapPoint,
  authoritativeFeatures: ObraMapFeature[],
): OperationalFeatureCollection {
  const features: OperationalFeature[] = [];

  if (isValidWorksiteCoordinate(worksite.latitude, worksite.longitude)) {
    features.push({
      type: "Feature",
      id: `${worksite.id}:localizacao`,
      geometry: {
        type: "Point",
        coordinates: [worksite.longitude, worksite.latitude],
      },
      properties: {
        categoria: "LOCALIZACAO_OBRA",
        objetoTipo: "OBRA",
        objetoId: worksite.id,
        nome: worksite.nome,
        fonte: "OBRA",
        versao: null,
      },
    });
  }

  for (const feature of authoritativeFeatures) {
    features.push({
      type: "Feature",
      id: feature.id,
      geometry: feature.geometry,
      properties: {
        ...feature.properties,
        categoria: feature.categoria,
        objetoTipo: feature.objetoTipo,
        objetoId: feature.objetoId,
        fonte: feature.fonte,
        versao: feature.versao,
        validoDesde: feature.validoDesde,
        validoAte: feature.validoAte,
      },
    });
  }

  return { type: "FeatureCollection", features };
}
