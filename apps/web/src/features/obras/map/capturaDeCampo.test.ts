import { describe, expect, it, vi } from "vitest";

import {
  CapturaDeCampoError,
  PRECISAO_MAXIMA_M,
  lerPosicaoDeCampo,
} from "./capturaDeCampo";

function geolocation(
  posicao: Partial<GeolocationCoordinates> & { accuracy: number },
  timestamp = Date.UTC(2026, 2, 2, 12, 0, 0),
): Geolocation {
  return {
    getCurrentPosition: (sucesso) =>
      (sucesso as PositionCallback)({
        coords: {
          latitude: -22.439459,
          longitude: -47.567267,
          altitude: null,
          altitudeAccuracy: null,
          heading: null,
          speed: null,
          ...posicao,
        } as GeolocationCoordinates,
        timestamp,
        toJSON: () => ({}),
      } as GeolocationPosition),
    watchPosition: vi.fn(),
    clearWatch: vi.fn(),
  } as unknown as Geolocation;
}

function falha(code: number): Geolocation {
  return {
    getCurrentPosition: (_sucesso, erro) =>
      (erro as PositionErrorCallback)({
        code,
        message: "",
        PERMISSION_DENIED: 1,
        POSITION_UNAVAILABLE: 2,
        TIMEOUT: 3,
      } as GeolocationPositionError),
    watchPosition: vi.fn(),
    clearWatch: vi.fn(),
  } as unknown as Geolocation;
}

describe("lerPosicaoDeCampo", () => {
  it("devolve a posição real do aparelho com a precisão informada", async () => {
    const posicao = await lerPosicaoDeCampo(geolocation({ accuracy: 6 }));

    expect(posicao.latitude).toBe(-22.439459);
    expect(posicao.longitude).toBe(-47.567267);
    expect(posicao.precisaoM).toBe(6);
    expect(posicao.observadoEm).toBe("2026-03-02T12:00:00.000Z");
  });

  it("recusa uma leitura imprecisa demais para localizar a frente de serviço", async () => {
    await expect(
      lerPosicaoDeCampo(geolocation({ accuracy: PRECISAO_MAXIMA_M + 1 })),
    ).rejects.toMatchObject({ motivo: "POSICAO_INDISPONIVEL" });
  });

  it("aceita exatamente o limite de precisão", async () => {
    const posicao = await lerPosicaoDeCampo(
      geolocation({ accuracy: PRECISAO_MAXIMA_M }),
    );

    expect(posicao.precisaoM).toBe(PRECISAO_MAXIMA_M);
  });

  it("preserva a ausência de precisão sem inventar um número", async () => {
    const posicao = await lerPosicaoDeCampo(
      geolocation({ accuracy: Number.NaN }),
    );

    expect(posicao.precisaoM).toBeNull();
  });

  it("distingue permissão negada de sinal indisponível e de tempo esgotado", async () => {
    await expect(lerPosicaoDeCampo(falha(1))).rejects.toMatchObject({
      motivo: "PERMISSAO_NEGADA",
    });
    await expect(lerPosicaoDeCampo(falha(2))).rejects.toMatchObject({
      motivo: "POSICAO_INDISPONIVEL",
    });
    await expect(lerPosicaoDeCampo(falha(3))).rejects.toMatchObject({
      motivo: "TEMPO_ESGOTADO",
    });
  });

  it("falha declaradamente quando o aparelho não expõe localização", async () => {
    await expect(lerPosicaoDeCampo(undefined)).rejects.toBeInstanceOf(
      CapturaDeCampoError,
    );
  });
});
