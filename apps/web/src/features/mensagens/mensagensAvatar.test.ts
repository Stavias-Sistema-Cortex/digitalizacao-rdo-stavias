import { describe, expect, it } from "vitest";

import type { ConversaLocalRecord } from "../../lib/db/db.types";
import {
  AVATAR_TINTAS,
  sementeDaConversa,
  silhuetaDaConversa,
  tintaDoAvatar,
} from "./mensagensAvatar";

function conversa(
  tipo: ConversaLocalRecord["tipo"],
  participantes: Array<[string, ConversaLocalRecord["participantes"][number]["status"]]>,
): ConversaLocalRecord {
  return {
    id: "conversa-1",
    tipo,
    titulo: null,
    obraId: null,
    equipeId: null,
    status: "ATIVA",
    participantes: participantes.map(([colaboradorId, status]) => ({
      colaboradorId,
      nome: colaboradorId,
      papel: "MEMBRO",
      status,
      adicionadoEm: "2026-07-01T12:00:00.000Z",
    })),
    criadaEm: "2026-07-01T12:00:00.000Z",
    atualizadaEm: "2026-07-01T12:00:00.000Z",
  } as ConversaLocalRecord;
}

describe("silhuetaDaConversa", () => {
  it("usa a figura única na conversa direta", () => {
    expect(silhuetaDaConversa("DIRETA")).toBe("pessoa");
  });

  it("usa a dupla no grupo", () => {
    expect(silhuetaDaConversa("GRUPO")).toBe("dupla");
  });

  it("marca com capacete os canais presos ao canteiro", () => {
    expect(silhuetaDaConversa("EQUIPE")).toBe("obra");
    expect(silhuetaDaConversa("OBRA")).toBe("obra");
  });
});

describe("sementeDaConversa", () => {
  it("usa o id do outro na conversa direta, para a cor da pessoa não mudar de painel para painel", () => {
    const direta = conversa("DIRETA", [
      ["eu", "ATIVO"],
      ["joao", "ATIVO"],
    ]);
    expect(sementeDaConversa(direta, "eu")).toBe("joao");
    expect(tintaDoAvatar(sementeDaConversa(direta, "eu"))).toBe(
      tintaDoAvatar("joao"),
    );
  });

  it("ignora quem saiu da conversa direta", () => {
    const direta = conversa("DIRETA", [
      ["eu", "ATIVO"],
      ["antigo", "REMOVIDO"],
      ["joao", "ATIVO"],
    ]);
    expect(sementeDaConversa(direta, "eu")).toBe("joao");
  });

  it("volta ao id da conversa quando não sobra ninguém do outro lado", () => {
    const so_eu = conversa("DIRETA", [["eu", "ATIVO"]]);
    expect(sementeDaConversa(so_eu, "eu")).toBe("conversa-1");
  });

  it("usa o id da conversa nos canais coletivos", () => {
    for (const tipo of ["GRUPO", "EQUIPE", "OBRA"] as const) {
      const canal = conversa(tipo, [
        ["eu", "ATIVO"],
        ["joao", "ATIVO"],
      ]);
      expect(sementeDaConversa(canal, "eu")).toBe("conversa-1");
    }
  });
});

describe("tintaDoAvatar", () => {
  it("devolve sempre a mesma tinta para a mesma semente", () => {
    expect(tintaDoAvatar("conversa-7f3a")).toBe(tintaDoAvatar("conversa-7f3a"));
  });

  it("mantém a tinta dentro da paleta", () => {
    const sementes = Array.from({ length: 200 }, (_, index) => `id-${index}`);
    for (const semente of sementes) {
      const tinta = tintaDoAvatar(semente);
      expect(tinta).toBeGreaterThanOrEqual(0);
      expect(tinta).toBeLessThan(AVATAR_TINTAS);
    }
  });

  it("usa a paleta inteira em vez de encalhar numa cor", () => {
    const usadas = new Set(
      Array.from({ length: 200 }, (_, index) => tintaDoAvatar(`id-${index}`)),
    );
    expect(usadas.size).toBe(AVATAR_TINTAS);
  });

  it("não estoura com semente vazia", () => {
    expect(tintaDoAvatar("")).toBe(0);
  });

  it("aceita nome com acento sem quebrar o índice", () => {
    expect(tintaDoAvatar("João Souza")).toBeLessThan(AVATAR_TINTAS);
  });
});
