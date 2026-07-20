import { describe, expect, it } from "vitest";

import { relativeTime } from "./relativeTime";

const NOW = Date.parse("2026-07-06T12:00:00.000Z");

describe("relativeTime", () => {
  it("formata minutos, horas e dias", () => {
    expect(
      relativeTime("2026-07-06T11:59:40.000Z", NOW),
    ).toBe("agora há pouco");
    expect(
      relativeTime("2026-07-06T11:30:00.000Z", NOW),
    ).toBe("há 30 min");
    expect(
      relativeTime("2026-07-06T09:00:00.000Z", NOW),
    ).toBe("há 3 h");
    expect(
      relativeTime("2026-07-04T12:00:00.000Z", NOW),
    ).toBe("há 2 dias");
  });

  it("cai para data curta acima de 30 dias", () => {
    expect(
      relativeTime("2026-05-01T12:00:00.000Z", NOW),
    ).toBe("01/05/2026");
  });
});
