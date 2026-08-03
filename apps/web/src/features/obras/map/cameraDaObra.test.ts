import { describe, expect, it } from "vitest";

import {
  cameraUtilizavel,
  mesmaCamera,
  type CameraDaObra,
} from "./cameraDaObra";

const BASE: CameraDaObra = {
  longitude: -47.579829,
  latitude: -22.424682,
  zoom: 16,
};

describe("câmera compartilhada entre os dois mapas", () => {
  /**
   * O caso que a tolerância existe para cobrir: dois mapas espelhados nunca
   * param no mesmo número, porque cada um arredonda o centro pelo seu próprio
   * tamanho em pixels. Sem folga, a devolução vira movimento novo e o par
   * oscila sem assentar.
   */
  it("trata a devolução do outro mapa como o mesmo enquadramento", () => {
    expect(
      mesmaCamera(BASE, {
        longitude: BASE.longitude + 2e-7,
        latitude: BASE.latitude - 3e-7,
        zoom: BASE.zoom + 0.004,
      }),
    ).toBe(true);
  });

  it("reconhece movimento que a pessoa realmente fez", () => {
    expect(
      mesmaCamera(BASE, { ...BASE, longitude: BASE.longitude + 0.002 }),
    ).toBe(false);
    expect(mesmaCamera(BASE, { ...BASE, zoom: BASE.zoom + 1 })).toBe(false);
  });

  it("compara ausência sem confundir com igualdade", () => {
    expect(mesmaCamera(null, null)).toBe(true);
    expect(mesmaCamera(BASE, null)).toBe(false);
    expect(mesmaCamera(null, BASE)).toBe(false);
  });

  /** Coordenada inválida arrastaria o outro mapa para o nada. */
  it("recusa enquadramento que não dá para impor", () => {
    expect(cameraUtilizavel(BASE)).toBe(true);
    expect(cameraUtilizavel(null)).toBe(false);
    expect(cameraUtilizavel({ ...BASE, latitude: Number.NaN })).toBe(false);
    expect(cameraUtilizavel({ ...BASE, latitude: 91 })).toBe(false);
    expect(cameraUtilizavel({ ...BASE, longitude: 181 })).toBe(false);
    expect(
      cameraUtilizavel({ ...BASE, zoom: Number.POSITIVE_INFINITY }),
    ).toBe(false);
  });
});
