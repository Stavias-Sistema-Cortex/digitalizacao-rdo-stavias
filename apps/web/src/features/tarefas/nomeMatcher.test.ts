import { describe, expect, it } from "vitest";

import {
  buscarPorNome,
  normalizeNome,
  reconhecerNomeExato,
} from "./nomeMatcher";

interface Pessoa {
  id: string;
  nome: string;
}

const PESSOAS: Pessoa[] = [
  { id: "1", nome: "João Lucas Oliveira Silva" },
  { id: "2", nome: "Carlos Meireles" },
  { id: "3", nome: "Maria José Antônio" },
  { id: "4", nome: "Lucas Pereira" },
  { id: "5", nome: "Edu Prado" },
];

const nomeDe = (pessoa: Pessoa) => pessoa.nome;

describe("normalizeNome", () => {
  it("remove acentos, caixa e espaços extras", () => {
    expect(normalizeNome("  JOÃO   Lucas ")).toBe(
      "joao lucas",
    );
    expect(normalizeNome("MARIA JOSÉ ANTÔNIO")).toBe(
      "maria jose antonio",
    );
  });
});

describe("reconhecerNomeExato", () => {
  it("reconhece com capslock e sem acento", () => {
    expect(
      reconhecerNomeExato(
        PESSOAS,
        nomeDe,
        "JOAO LUCAS OLIVEIRA SILVA",
      )?.id,
    ).toBe("1");
    expect(
      reconhecerNomeExato(
        PESSOAS,
        nomeDe,
        "maria jose antonio",
      )?.id,
    ).toBe("3");
  });

  it("reconhece quando só um candidato casa todos os tokens", () => {
    expect(
      reconhecerNomeExato(PESSOAS, nomeDe, "carlos meireles")
        ?.id,
    ).toBe("2");
    expect(
      reconhecerNomeExato(PESSOAS, nomeDe, "edu")?.id,
    ).toBe("5");
  });

  it("não chuta quando o nome é ambíguo", () => {
    expect(
      reconhecerNomeExato(PESSOAS, nomeDe, "lucas"),
    ).toBeNull();
    expect(
      reconhecerNomeExato(PESSOAS, nomeDe, ""),
    ).toBeNull();
  });
});

describe("buscarPorNome", () => {
  it("ranqueia prefixos e tolera acento/caixa", () => {
    const resultados = buscarPorNome(
      PESSOAS,
      nomeDe,
      "JOAO lu",
    );

    expect(resultados[0]?.id).toBe("1");
  });

  it("casa tokens fora de ordem", () => {
    const resultados = buscarPorNome(
      PESSOAS,
      nomeDe,
      "silva joao",
    );

    expect(resultados[0]?.id).toBe("1");
  });

  it("retorna vazio sem consulta ou sem candidatos", () => {
    expect(buscarPorNome(PESSOAS, nomeDe, "  ")).toEqual(
      [],
    );
    expect(
      buscarPorNome(PESSOAS, nomeDe, "zzz"),
    ).toEqual([]);
  });

  it("limita a quantidade de sugestões", () => {
    const muitos = Array.from(
      { length: 20 },
      (_, index) => ({
        id: String(index),
        nome: `Lucas ${index}`,
      }),
    );

    expect(
      buscarPorNome(muitos, nomeDe, "lucas", 6),
    ).toHaveLength(6);
  });
});
