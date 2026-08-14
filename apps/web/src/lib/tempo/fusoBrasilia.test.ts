import { describe, expect, it } from "vitest";

import {
  compararCarimbosEmBrasilia,
  compararInstantesDoServidor,
  dataHojeEmBrasilia,
  FUSO_BRASILIA,
  formatarCarimboEmBrasilia,
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

  it("não transforma data pura em instante", () => {
    expect(Number.isNaN(instanteDoServidor("2026-08-14").getTime())).toBe(
      true,
    );
  });

  it("devolve data inválida para texto que não é data", () => {
    expect(Number.isNaN(instanteDoServidor("não é data").getTime())).toBe(true);
  });
});

describe("compararCarimbosEmBrasilia", () => {
  it("compara legado civil com o relógio de um instante em Brasília", () => {
    expect(
      compararCarimbosEmBrasilia(
        "2026-08-14T12:00:00",
        "2026-08-14T14:00:00Z",
      ),
    ).toBeGreaterThan(0);
  });

  it("preserva microssegundos ao converter um instante para Brasília", () => {
    expect(
      compararCarimbosEmBrasilia(
        "2026-08-14T12:00:00.123",
        "2026-08-14T15:00:00.123456Z",
      ),
    ).toBeLessThan(0);
  });
});

describe("compararInstantesDoServidor", () => {
  it("preserva microssegundos que Date não representa", () => {
    expect(
      compararInstantesDoServidor(
        "2026-08-14T12:00:00.123Z",
        "2026-08-14T12:00:00.123456Z",
      ),
    ).toBeLessThan(0);
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

  it("recusa data pura para não deslocar o dia anterior", () => {
    expect(
      formatarEmBrasilia("2026-08-14", { dateStyle: "short" }),
    ).toBeNull();
  });

  it("fixa o fuso do Córtex", () => {
    expect(FUSO_BRASILIA).toBe("America/Sao_Paulo");
  });
});

describe("formatarCarimboEmBrasilia", () => {
  it("converte um instante explícito para o relógio de Brasília", () => {
    expect(
      formatarCarimboEmBrasilia("2026-07-27T12:31:00Z", relogio),
    ).toBe("09:31");
  });

  it("preserva o relógio civil legado que chegou sem offset", () => {
    expect(
      formatarCarimboEmBrasilia("2026-07-27T12:31:00", relogio),
    ).toBe("12:31");
  });

  it("não inventa hora para data pura", () => {
    expect(
      formatarCarimboEmBrasilia("2026-07-27", { dateStyle: "short" }),
    ).toBeNull();
  });
});

describe("dataHojeEmBrasilia", () => {
  it("continua no dia de Brasília depois da virada em UTC", () => {
    expect(dataHojeEmBrasilia(new Date("2026-08-15T00:30:00Z"))).toBe(
      "2026-08-14",
    );
  });

  it("vira o dia quando chega a meia-noite de Brasília", () => {
    expect(dataHojeEmBrasilia(new Date("2026-08-15T03:00:00Z"))).toBe(
      "2026-08-15",
    );
  });
});
