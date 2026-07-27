import { describe, expect, it } from "vitest";

import type { RdoContextServiceCatalog } from "./rdoLookupApi";
import {
  isRdoPriceCatalogSelectable,
  searchRdoServiceTypes,
} from "./rdoServiceTypes";

const catalog: RdoContextServiceCatalog[] = [{
  id: "service-1",
  code: "PAV-001",
  name: "Aplicação de CBUQ",
  description: "Pavimentação da faixa",
  priceChoices: [{
    id: "price-7",
    serviceId: "service-1",
    unit: "M2",
    version: 7,
    validFrom: "2026-07-01",
    effectiveValidTo: null,
  }],
}];

describe("catálogo real de serviços do RDO", () => {
  it("pesquisa somente o catálogo recebido da obra e preserva seus IDs", () => {
    expect(searchRdoServiceTypes(catalog, "cbuq")).toMatchObject([{
      catalogId: "service-1",
      displayName: "PAV-001 - Aplicação de CBUQ",
      priceChoices: [{ id: "price-7" }],
    }]);
    expect(searchRdoServiceTypes(catalog, "serviço inexistente")).toEqual([]);
  });

  it("libera seleção apenas com cobertura integral de serviço e preço", () => {
    const complete = {
      status: "COMPLETE",
      total: 1,
      returned: 1,
      complete: true,
    };
    expect(isRdoPriceCatalogSelectable(complete, complete)).toBe(true);
    expect(isRdoPriceCatalogSelectable(
      complete,
      { ...complete, status: "PARTIAL", complete: false },
    )).toBe(false);
  });
});
