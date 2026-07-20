import { describe, expect, it } from "vitest";

import type { ObraLocalRecord } from "../../lib/db/db.types";
import {
  filterObrasByChip,
  filterObrasByRodovia,
  filterObrasByUf,
} from "./homeFilters";

function obra(
  id: string,
  status: string,
  uf: string | null = "MS",
): ObraLocalRecord {
  return {
    id,
    codigoContrato: `CT-${id}`,
    nome: `Obra ${id}`,
    cliente: null,
    cidade: null,
    uf,
    rodovia: null,
    status,
    observacoes: null,
    latitude: null,
    longitude: null,
    valorContratual: null,
    updatedAt: "2026-07-06T12:00:00.000Z",
  };
}

describe("filterObrasByChip", () => {
  const obras = [
    obra("1", "ATIVA"),
    obra("2", "EM_EXECUCAO"),
    obra("3", "CONCLUIDA"),
    obra("4", "DESATIVADA"),
    obra("5", "A_COMECAR"),
  ];

  it("EM_EXECUCAO cobre ATIVA e EM_EXECUCAO", () => {
    expect(
      filterObrasByChip(obras, "EM_EXECUCAO").map((o) => o.id),
    ).toEqual(["1", "2"]);
  });

  it("TODAS não filtra", () => {
    expect(filterObrasByChip(obras, "TODAS")).toHaveLength(5);
  });

  it("status desconhecido só aparece em TODAS", () => {
    const exoticas = [obra("9", "PAUSADA")];
    expect(filterObrasByChip(exoticas, "EM_EXECUCAO")).toHaveLength(0);
    expect(filterObrasByChip(exoticas, "TODAS")).toHaveLength(1);
  });
});

describe("filterObrasByUf", () => {
  it("filtra por UF ignorando caixa e aceita vazio", () => {
    const obras = [obra("1", "ATIVA", "MS"), obra("2", "ATIVA", "SP")];
    expect(filterObrasByUf(obras, "ms").map((o) => o.id)).toEqual(["1"]);
    expect(filterObrasByUf(obras, "")).toHaveLength(2);
  });
});

describe("filterObrasByRodovia", () => {
  it("filtra por rodovia exata ignorando caixa e aceita vazio", () => {
    const obras = [
      { ...obra("1", "ATIVA"), rodovia: "BR-262" },
      { ...obra("2", "ATIVA"), rodovia: "BR-163" },
      { ...obra("3", "ATIVA"), rodovia: null },
    ];
    expect(
      filterObrasByRodovia(obras, "br-262").map((o) => o.id),
    ).toEqual(["1"]);
    expect(filterObrasByRodovia(obras, "")).toHaveLength(3);
  });
});
