import { describe, expect, it } from "vitest";

import {
  conversationInitials,
  formatClock,
  formatFileSize,
  formatRelativeTime,
  initials,
} from "./mensagensFormat";

/**
 * Constrói o ISO do instante que marca a hora dada no relógio de Brasília.
 *
 * O rótulo é lido em Brasília, então o teste tem de falar em Brasília — senão
 * ele passaria ou falharia conforme o fuso de quem roda. O deslocamento é fixo
 * em três horas porque o Brasil não tem mais horário de verão desde 2019, e as
 * datas aqui são de 2026.
 */
function brasiliaIso(
  year: number,
  month: number,
  day: number,
  hour: number,
  minute: number,
): string {
  return new Date(Date.UTC(year, month - 1, day, hour + 3, minute)).toISOString();
}

const agora = new Date(brasiliaIso(2026, 7, 21, 14, 0));

describe("formatRelativeTime", () => {
  it("mostra 'agora' abaixo de um minuto", () => {
    expect(formatRelativeTime(brasiliaIso(2026, 7, 21, 14, 0), agora)).toBe("agora");
  });

  it("conta o primeiro minuto completo", () => {
    expect(formatRelativeTime(brasiliaIso(2026, 7, 21, 13, 59), agora)).toBe("há 1 min");
  });

  it("conta minutos na primeira hora", () => {
    expect(formatRelativeTime(brasiliaIso(2026, 7, 21, 13, 55), agora)).toBe("há 5 min");
  });

  it("conta horas nas primeiras 24 horas", () => {
    expect(formatRelativeTime(brasiliaIso(2026, 7, 21, 11, 0), agora)).toBe("há 3 h");
  });

  it("ainda conta horas às 23 horas, mesmo já sendo o dia anterior", () => {
    expect(formatRelativeTime(brasiliaIso(2026, 7, 20, 14, 32), agora)).toBe("há 23 h");
  });

  it("nomeia o dia anterior passadas 24 horas", () => {
    expect(formatRelativeTime(brasiliaIso(2026, 7, 20, 13, 0), agora)).toBe("ontem 13:00");
  });

  it("usa data curta a partir de dois dias", () => {
    expect(formatRelativeTime(brasiliaIso(2026, 7, 12, 14, 32), agora)).toBe("12/07 14:32");
  });

  it("trata relógio adiantado como agora", () => {
    expect(formatRelativeTime(brasiliaIso(2026, 7, 21, 14, 30), agora)).toBe("agora");
  });

  it("devolve vazio para data inválida", () => {
    expect(formatRelativeTime("não é data", agora)).toBe("");
  });
});

describe("formatClock", () => {
  it("formata hora e minuto", () => {
    expect(formatClock(brasiliaIso(2026, 7, 21, 9, 5))).toBe("09:05");
  });

  /*
   * A mensagem enviada às nove da manhã aparecia como 12:03 quando o aparelho
   * — ou o servidor que serve a tela — estava em UTC. O horário do Córtex é o
   * de Brasília, e o instante abaixo é escrito em UTC de propósito: é assim
   * que ele chega da API.
   */
  it("lê o instante do servidor no relógio de Brasília, não no do aparelho", () => {
    expect(formatClock("2026-08-14T12:03:00Z")).toBe("09:03");
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

describe("initials", () => {
  it("usa a primeira letra do primeiro e do último nome", () => {
    expect(initials("Ana Ribeiro")).toBe("AR");
  });

  it("usa uma letra quando há só um nome", () => {
    expect(initials("Ana")).toBe("A");
  });

  it("devolve interrogação para nome vazio", () => {
    expect(initials("   ")).toBe("?");
  });
});

describe("conversationInitials", () => {
  it("ignora o número final do título da obra", () => {
    expect(conversationInitials("Obra Rodovia Vila Nova — Trecho 3")).toBe("OR");
  });

  it("funciona para nome de pessoa em conversa direta", () => {
    expect(conversationInitials("João Souza")).toBe("JS");
  });

  it("usa uma letra quando há só uma palavra", () => {
    expect(conversationInitials("Terraplenagem")).toBe("T");
  });

  it("devolve interrogação quando não há palavra alguma", () => {
    expect(conversationInitials("— 3")).toBe("?");
  });
});
