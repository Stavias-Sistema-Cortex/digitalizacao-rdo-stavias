import { describe, expect, it } from "vitest";

import { resolveMapProvider } from "./mapProvider";

describe("resolveMapProvider", () => {
  it("uses MapTiler as the default provider and reports a missing key", () => {
    const provider = resolveMapProvider({});

    expect(provider.id).toBe("maptiler");
    expect(provider.configured).toBe(false);
    expect(provider.styleUrl).toBeNull();
    expect(provider.missingConfiguration).toContain("VITE_MAPTILER_API_KEY");
  });

  it("builds the MapTiler Satellite style from the environment key", () => {
    const provider = resolveMapProvider({
      VITE_MAP_PROVIDER: "maptiler",
      VITE_MAPTILER_API_KEY: "map tiler/key",
    });

    expect(provider.configured).toBe(true);
    expect(provider.engine).toBe("maplibre");
    expect(provider.styleUrl).toBe(
      "https://api.maptiler.com/maps/satellite/style.json?key=map+tiler%2Fkey",
    );
    expect(provider.capabilities.perspective3d).toBe(true);
  });

  it("prepares Mapbox without pretending it is configured", () => {
    const provider = resolveMapProvider({ VITE_MAP_PROVIDER: "mapbox" });

    expect(provider.id).toBe("mapbox");
    expect(provider.configured).toBe(false);
    expect(provider.engine).toBe("mapbox");
    expect(provider.missingConfiguration).toContain(
      "VITE_MAPBOX_ACCESS_TOKEN",
    );
  });

  it("activates the shared Mapbox contract when its public token exists", () => {
    const provider = resolveMapProvider({
      VITE_MAP_PROVIDER: "mapbox",
      VITE_MAPBOX_ACCESS_TOKEN: "pk.public-token",
    });

    expect(provider.configured).toBe(true);
    expect(provider.styleUrl).toBe(
      "mapbox://styles/mapbox/satellite-streets-v12",
    );
    expect(provider.capabilities.perspective3d).toBe(true);
  });

  it("falls back safely to MapTiler for an unknown provider", () => {
    const provider = resolveMapProvider({
      VITE_MAP_PROVIDER: "provider-inexistente",
      VITE_MAPTILER_API_KEY: "valid-key",
    });

    expect(provider.id).toBe("maptiler");
    expect(provider.fallbackReason).toContain("provider-inexistente");
  });
});
