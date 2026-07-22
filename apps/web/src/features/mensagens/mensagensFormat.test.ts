import { describe, expect, it } from "vitest";

import {
  formatClock,
  formatFileSize,
  formatRelativeTime,
} from "./mensagensFormat";

/** Constrói um ISO a partir do fuso local para o teste não depender de TZ. */
function localIso(
  year: number,
  month: number,
  day: number,
  hour: number,
  minute: number,
): string {
  return new Date(year, month - 1, day, hour, minute).toISOString();
}

const agora = new Date(2026, 6, 21, 14, 0);

describe("formatRelativeTime", () => {
  it("mostra 'agora' abaixo de um minuto", () => {
    expect(formatRelativeTime(localIso(2026, 7, 21, 14, 0), agora)).toBe("agora");
  });

  it("conta o primeiro minuto completo", () => {
    expect(formatRelativeTime(localIso(2026, 7, 21, 13, 59), agora)).toBe("há 1 min");
  });

  it("conta minutos na primeira hora", () => {
    expect(formatRelativeTime(localIso(2026, 7, 21, 13, 55), agora)).toBe("há 5 min");
  });

  it("conta horas nas primeiras 24 horas", () => {
    expect(formatRelativeTime(localIso(2026, 7, 21, 11, 0), agora)).toBe("há 3 h");
  });

  it("ainda conta horas às 23 horas, mesmo já sendo o dia anterior", () => {
    expect(formatRelativeTime(localIso(2026, 7, 20, 14, 32), agora)).toBe("há 23 h");
  });

  it("nomeia o dia anterior passadas 24 horas", () => {
    expect(formatRelativeTime(localIso(2026, 7, 20, 13, 0), agora)).toBe("ontem 13:00");
  });

  it("usa data curta a partir de dois dias", () => {
    expect(formatRelativeTime(localIso(2026, 7, 12, 14, 32), agora)).toBe("12/07 14:32");
  });

  it("trata relógio adiantado como agora", () => {
    expect(formatRelativeTime(localIso(2026, 7, 21, 14, 30), agora)).toBe("agora");
  });

  it("devolve vazio para data inválida", () => {
    expect(formatRelativeTime("não é data", agora)).toBe("");
  });
});

describe("formatClock", () => {
  it("formata hora e minuto", () => {
    expect(formatClock(localIso(2026, 7, 21, 9, 5))).toBe("09:05");
  });

  it("devolve vazio para data inválida", () => {
    expect(formatClock("não é data")).toBe("");
  });
});

describe("formatFileSize", () => {
  it("usa KB abaixo de um megabyte", () => {
    expect(formatFileSize(284_000)).toBe("277 KB");
  });

  it("nunca mostra zero KB", () => {
    expect(formatFileSize(10)).toBe("1 KB");
  });

  it("usa MB com uma casa a partir de um megabyte", () => {
    expect(formatFileSize(3_500_000)).toBe("3.3 MB");
  });
});
