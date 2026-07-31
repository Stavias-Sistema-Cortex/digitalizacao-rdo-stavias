import { describe, expect, it } from "vitest";

import {
  availableMapProviders,
  resolveMapProvider,
  resolveMapProviderForId,
} from "./mapProvider";

describe("resolveMapProvider", () => {
  it("entrega um mapa utilizável sem chave nenhuma", () => {
    const provider = resolveMapProvider({});

    expect(provider.id).toBe("maplibre");
    expect(provider.keyless).toBe(true);
    expect(provider.configured).toBe(true);
    expect(provider.styleUrl).toBe(
      "https://tiles.openfreemap.org/styles/liberty",
    );
    expect(provider.missingConfiguration).toBeNull();
    expect(provider.capabilities.perspective3d).toBe(true);
    expect(provider.capabilities.buildingExtrusion).toBe(true);
  });

  it("aceita um estilo MapLibre próprio quando o ambiente declara um", () => {
    const provider = resolveMapProvider({
      VITE_MAPLIBRE_STYLE_URL: "https://tiles.internos.exemplo/estilo.json",
    });

    expect(provider.styleUrl).toBe("https://tiles.internos.exemplo/estilo.json");
    expect(provider.keyless).toBe(true);
  });

  it("monta o estilo do MapTiler a partir da chave do ambiente", () => {
    const provider = resolveMapProvider({
      VITE_MAP_PROVIDER: "maptiler",
      VITE_MAPTILER_API_KEY: "map tiler/key",
    });

    expect(provider.id).toBe("maptiler");
    expect(provider.configured).toBe(true);
    expect(provider.engine).toBe("maplibre");
    expect(provider.styleUrl).toBe(
      "https://api.maptiler.com/maps/satellite/style.json?key=map+tiler%2Fkey",
    );
    expect(provider.capabilities.satellite).toBe(true);
    // Estilo de satélite é raster: não há volume de edificação a extrudar.
    expect(provider.capabilities.buildingExtrusion).toBe(false);
  });

  it("ativa o Mapbox quando o token público existe", () => {
    const provider = resolveMapProvider({
      VITE_MAP_PROVIDER: "mapbox",
      VITE_MAPBOX_ACCESS_TOKEN: "pk.public-token",
    });

    expect(provider.id).toBe("mapbox");
    expect(provider.configured).toBe(true);
    expect(provider.engine).toBe("mapbox");
    expect(provider.styleUrl).toBe(
      "mapbox://styles/mapbox/satellite-streets-v12",
    );
  });

  it("cai para a malha aberta quando o provider pago não tem credencial", () => {
    const provider = resolveMapProvider({ VITE_MAP_PROVIDER: "mapbox" });

    expect(provider.id).toBe("maplibre");
    expect(provider.configured).toBe(true);
    expect(provider.fallbackReason).toContain("Mapbox");
  });

  it("cai para a malha aberta em provider desconhecido", () => {
    const provider = resolveMapProvider({
      VITE_MAP_PROVIDER: "provider-inexistente",
    });

    expect(provider.id).toBe("maplibre");
    expect(provider.fallbackReason).toContain("provider-inexistente");
  });
});

describe("resolveMapProviderForId", () => {
  it("não finge que um provider pago está pronto sem chave", () => {
    const maptiler = resolveMapProviderForId("maptiler", {});
    const mapbox = resolveMapProviderForId("mapbox", {});

    expect(maptiler.configured).toBe(false);
    expect(maptiler.styleUrl).toBeNull();
    expect(maptiler.missingConfiguration).toContain("VITE_MAPTILER_API_KEY");
    expect(mapbox.configured).toBe(false);
    expect(mapbox.missingConfiguration).toContain("VITE_MAPBOX_ACCESS_TOKEN");
  });
});

describe("availableMapProviders", () => {
  it("oferece apenas a malha aberta quando nenhum provider pago tem chave", () => {
    // Escolher um provider sem credencial apagava o mapa e devolvia um pedido
    // de configuração que quem está em campo não tem como atender.
    const providers = availableMapProviders({});

    expect(providers.map((provider) => provider.id)).toEqual(["maplibre"]);
    expect(providers[0].configured).toBe(true);
  });

  it("passa a oferecer o provider pago assim que ele ganha credencial", () => {
    const providers = availableMapProviders({
      VITE_MAPTILER_API_KEY: "chave-de-teste",
    });

    expect(providers.map((provider) => provider.id)).toEqual([
      "maplibre",
      "maptiler",
    ]);
    expect(providers.every((provider) => provider.configured)).toBe(true);
  });
});
