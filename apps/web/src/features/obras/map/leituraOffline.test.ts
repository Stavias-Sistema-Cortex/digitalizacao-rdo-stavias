import { describe, expect, it } from "vitest";

import { ApiTransportError } from "../../../lib/api/apiClient";
import { falhaEhAusenciaDeRede } from "./leituraOffline";

/**
 * O que autoriza mostrar o que o dispositivo já tem.
 *
 * A separação é entre "o servidor respondeu" e "não houve servidor". A
 * primeira precisa chegar como erro — exibir uma obra sem permissão como se
 * ela apenas não tivesse geometria seria mentir sobre o estado do sistema. A
 * segunda é o caso que o modo offline existe para atender.
 */
describe("ausência de remoto", () => {
  it("aceita falta de rede e tempo esgotado", () => {
    expect(
      falhaEhAusenciaDeRede(new ApiTransportError("", "CONNECTION")),
    ).toBe(true);
    expect(falhaEhAusenciaDeRede(new ApiTransportError("", "TIMEOUT")))
      .toBe(true);
  });

  /**
   * Quem abriu os dados pelo modo offline não tem sessão de servidor para
   * gastar, e toda chamada é recusada antes de sair do aparelho. Do ponto de
   * vista da leitura isso é exatamente estar sem servidor — tratá-lo como
   * resposta fazia o mapa anunciar falha em cima de camadas inteiras ali no
   * dispositivo.
   */
  it("aceita a sessão remota isolada", () => {
    expect(
      falhaEhAusenciaDeRede(
        new ApiTransportError("", "REMOTE_SESSION_ISOLATED"),
      ),
    ).toBe(true);
  });

  it("recusa o que o servidor de fato respondeu", () => {
    expect(falhaEhAusenciaDeRede(new Error("403"))).toBe(false);
    expect(
      falhaEhAusenciaDeRede(
        new ApiTransportError("", "CLIENT_INSTANCE_REAUTH_REQUIRED"),
      ),
    ).toBe(false);
  });
});
