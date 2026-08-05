import { describe, expect, it } from "vitest";

import type { ColaboradorLocalRecord } from "../../lib/db/db.types";
import {
  correspondeAoTermo,
  normalizar,
  ordenarPorRelevancia,
  pessoasParaMostrar,
  proximoDestaque,
} from "./buscaDePessoas";

function pessoa(
  nome: string,
  extras: Partial<ColaboradorLocalRecord> = {},
): ColaboradorLocalRecord {
  return {
    id: nome.toLowerCase().replace(/\s+/g, "-"),
    nome,
    cpfMascarado: null,
    nomePerfil: null,
    ativo: true,
    updatedAt: null,
    cachedAt: "",
    ...extras,
  };
}

describe("busca de pessoas", () => {
  /**
   * Quem aponta em campo digita no celular, com pressa, e quase nunca acerta o
   * acento. Exigi-lo faria da grafia um requisito de busca.
   */
  it("acha quem tem acento sem que o acento seja digitado", () => {
    expect(normalizar("JOSÉ ANTÔNIO")).toBe("jose antonio");
    expect(correspondeAoTermo(pessoa("José Antônio"), "jose")).toBe(true);
    expect(correspondeAoTermo(pessoa("Conceição"), "conceicao")).toBe(true);
  });

  it("procura também pelo CPF mascarado e pelo perfil", () => {
    const alguem = pessoa("Ana Lima", {
      cpfMascarado: "*********-42",
      nomePerfil: "Apontador",
    });

    expect(correspondeAoTermo(alguem, "42")).toBe(true);
    expect(correspondeAoTermo(alguem, "apontador")).toBe(true);
    expect(correspondeAoTermo(alguem, "encarregado")).toBe(false);
  });

  /**
   * Quem digita "silva" quer ver primeiro quem começa com Silva, não quem tem
   * Silva no quarto sobrenome.
   */
  it("põe na frente quem começa com o que foi digitado", () => {
    const ordenadas = ordenarPorRelevancia(
      [
        pessoa("Carlos da Silva Souza"),
        pessoa("Silva Marques"),
        pessoa("Ana Silva"),
      ],
      "silva",
    );

    expect(ordenadas.map((p) => p.nome)).toEqual([
      "Silva Marques",
      "Ana Silva",
      "Carlos da Silva Souza",
    ]);
  });

  it("ordena por nome quando não há termo", () => {
    const ordenadas = ordenarPorRelevancia(
      [pessoa("Zeca"), pessoa("Ana"), pessoa("Marcos")],
      "",
    );

    expect(ordenadas.map((p) => p.nome)).toEqual(["Ana", "Marcos", "Zeca"]);
  });

  /**
   * O defeito que este módulo existe para não repetir: o seletor anterior
   * cortava a lista sem dizer, e uma lista truncada em silêncio é
   * indistinguível de uma lista completa.
   */
  it("avisa quando cortou em vez de cortar calado", () => {
    const muitas = Array.from({ length: 12 }, (_, indice) =>
      pessoa(`Pessoa ${String(indice).padStart(2, "0")}`),
    );

    const recortado = pessoasParaMostrar(muitas, "", 5);
    expect(recortado.pessoas).toHaveLength(5);
    expect(recortado.excedeu).toBe(true);

    const inteiro = pessoasParaMostrar(muitas.slice(0, 3), "", 5);
    expect(inteiro.pessoas).toHaveLength(3);
    expect(inteiro.excedeu).toBe(false);
  });

  it("estreita conforme o nome é digitado, letra a letra", () => {
    const todas = [
      pessoa("Rita Ferreira"),
      pessoa("Ricardo Alves"),
      pessoa("Roberto Dias"),
      pessoa("Ana Paula"),
    ];

    expect(pessoasParaMostrar(todas, "r").pessoas).toHaveLength(3);
    expect(pessoasParaMostrar(todas, "ri").pessoas).toHaveLength(2);
    expect(
      pessoasParaMostrar(todas, "rit").pessoas.map((p) => p.nome),
    ).toEqual(["Rita Ferreira"]);
  });

  /** A tecla nunca cai no vazio: dá a volta em vez de parar na borda. */
  it("circula o destaque pelas pontas da lista", () => {
    expect(proximoDestaque(-1, 1, 3)).toBe(0);
    expect(proximoDestaque(-1, -1, 3)).toBe(2);
    expect(proximoDestaque(2, 1, 3)).toBe(0);
    expect(proximoDestaque(0, -1, 3)).toBe(2);
    expect(proximoDestaque(0, 1, 0)).toBe(-1);
  });
});

describe("busca por palavras soltas", () => {
  const paulo = pessoa("PAULO SERGIO DA SILVA NERIS", {
    cpfMascarado: "***.***.***-50",
  });

  /*
   * O filtro comparava o texto inteiro como pedaço contíguo, então exigia o
   * nome completo, na ordem exata. Era o oposto do que uma busca deve pedir.
   */
  it("acha pelo primeiro e pelo último nome, sem os do meio", () => {
    expect(correspondeAoTermo(paulo, "paulo neris")).toBe(true);
    expect(correspondeAoTermo(paulo, "neris paulo")).toBe(true);
  });

  it("continua achando por uma palavra só e pelo nome inteiro", () => {
    expect(correspondeAoTermo(paulo, "paulo")).toBe(true);
    expect(correspondeAoTermo(paulo, "neris")).toBe(true);
    expect(correspondeAoTermo(paulo, "PAULO SERGIO DA SILVA NERIS")).toBe(true);
  });

  it("exige todas as palavras, para cada uma estreitar o resultado", () => {
    expect(correspondeAoTermo(paulo, "paulo ferreira")).toBe(false);
  });

  it("ignora acento e caixa", () => {
    expect(correspondeAoTermo(pessoa("JOSÉ ANTÔNIO"), "jose antonio")).toBe(true);
  });

  it("cruza nome com CPF mascarado na mesma busca", () => {
    expect(correspondeAoTermo(paulo, "neris 50")).toBe(true);
  });
});
