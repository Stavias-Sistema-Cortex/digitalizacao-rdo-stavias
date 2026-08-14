import { describe, expect, it } from "vitest";

import {
  FUSO_BRASILIA,
  formatarEmBrasilia,
  instanteDoServidor,
} from "./fusoBrasilia";

const relogio: Intl.DateTimeFormatOptions = {
  hour: "2-digit",
  minute: "2-digit",
};

describe("instanteDoServidor", () => {
  /*
   * O caso do relato. O backend produz UTC com LocalDateTime.now(ZoneOffset.UTC)
   * e serializa sem o Z, então o JSON chega "2026-08-14T12:03:00". Lido como
   * hora local — que é o que a especificação manda — o instante saía errado por
   * um fuso inteiro, e num aparelho em Brasília o erro se disfarçava: a tela
   * mostrava 12:03, o relógio de UTC, como se fosse o daqui.
   */
  it("lê data e hora sem fuso como UTC, que é como o backend a produz", () => {
    expect(instanteDoServidor("2026-08-14T12:03:00").toISOString()).toBe(
      "2026-08-14T12:03:00.000Z",
    );
  });

  it("aceita as variações que o serializador produz", () => {
    const esperado = "2026-08-14T12:03:00.000Z";
    expect(instanteDoServidor("2026-08-14T12:03").toISOString()).toBe(
      "2026-08-14T12:03:00.000Z",
    );
    expect(instanteDoServidor("2026-08-14T12:03:00.000").toISOString()).toBe(
      esperado,
    );
    expect(instanteDoServidor("2026-08-14 12:03:00").toISOString()).toBe(
      esperado,
    );
  });

  it("não mexe em quem já declara o fuso", () => {
    expect(instanteDoServidor("2026-08-14T12:03:00Z").toISOString()).toBe(
      "2026-08-14T12:03:00.000Z",
    );
    expect(
      instanteDoServidor("2026-08-14T09:03:00-03:00").toISOString(),
    ).toBe("2026-08-14T12:03:00.000Z");
  });

  /*
   * Data pura não tem hora nem fuso, e carimbar UTC nela seria inventar um
   * instante. Fica de fora da expressão de propósito — o próprio ECMAScript já
   * lê "2026-08-14" como o dia em UTC.
   */
  it("preserva a data pura", () => {
    expect(instanteDoServidor("2026-08-14").toISOString()).toBe(
      "2026-08-14T00:00:00.000Z",
    );
  });

  it("devolve data inválida para texto que não é data", () => {
    expect(Number.isNaN(instanteDoServidor("não é data").getTime())).toBe(true);
  });
});

describe("formatarEmBrasilia", () => {
  it("mostra o carimbo do servidor no relógio de Brasília", () => {
    expect(formatarEmBrasilia("2026-08-14T12:03:00", relogio)).toBe("09:03");
    expect(formatarEmBrasilia("2026-08-14T12:03:00Z", relogio)).toBe("09:03");
  });

  it("devolve nulo para data inválida, sem inventar texto", () => {
    expect(formatarEmBrasilia("não é data", relogio)).toBeNull();
  });

  it("fixa o fuso do Córtex", () => {
    expect(FUSO_BRASILIA).toBe("America/Sao_Paulo");
  });
});
