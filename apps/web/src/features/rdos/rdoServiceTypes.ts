import type {
  RdoContextCoverageSection,
  RdoContextServiceCatalog,
} from "./rdoLookupApi";

export interface RdoServiceType {
  catalogId: string;
  code: string;
  name: string;
  displayName: string;
  description: string | null;
  priceChoices: RdoContextServiceCatalog["priceChoices"];
  searchText: string;
}

function normalizeSearchText(value: string): string {
  return value
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .trim();
}

export function formatRdoServiceType(serviceType: RdoServiceType): string {
  return `${serviceType.code} - ${serviceType.name}`;
}

export function searchRdoServiceTypes(
  catalog: readonly RdoContextServiceCatalog[],
  query: string,
  limit = 40,
): RdoServiceType[] {
  const terms = normalizeSearchText(query).split(/\s+/).filter(Boolean);
  return catalog
    .map((service): RdoServiceType => {
      const displayName = `${service.code} - ${service.name}`;
      return {
        catalogId: service.id,
        code: service.code,
        name: service.name,
        displayName,
        description: service.description,
        priceChoices: service.priceChoices,
        searchText: normalizeSearchText(
          [service.code, service.name, service.description]
            .filter(Boolean)
            .join(" "),
        ),
      };
    })
    .filter((service) =>
      terms.every((term) => service.searchText.includes(term)))
    .slice(0, limit);
}

function complete(section: RdoContextCoverageSection | undefined): boolean {
  return section?.status === "COMPLETE" && section.complete === true &&
    section.returned === section.total;
}

export function isRdoPriceCatalogSelectable(
  serviceCoverage: RdoContextCoverageSection | undefined,
  priceCoverage: RdoContextCoverageSection | undefined,
): boolean {
  return complete(serviceCoverage) && complete(priceCoverage);
}
