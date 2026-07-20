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
