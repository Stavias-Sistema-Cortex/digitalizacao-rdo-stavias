import { beforeEach, describe, expect, it, vi } from "vitest";

const getLocalRdo = vi.hoisted(() => vi.fn());
const rdoDraftFromLocalRecord = vi.hoisted(() => vi.fn());
const saveExistingRdoDraftAtomically = vi.hoisted(() => vi.fn());

vi.mock("../../../lib/db/rdoRepository", () => ({ getLocalRdo }));
vi.mock("../../../lib/db/localRdoService", () => ({
  rdoDraftFromLocalRecord,
  saveExistingRdoDraftAtomically,
}));

const { corrigirKmPeloMapa, kmDaCorrecao } = await import(
  "./corrigirKmPeloMapa"
);

/** Eixo reto sobre o equador: 1 grau de longitude por quilômetro declarado. */
const EIXO = {
  id: "eixo-1",
  coordenadas: [
    [0, 0],
    [1, 0],
    [2, 0],
  ] as const,
  kmInicial: 100,
  kmFinal: 110,
};

function rascunho() {
  return {
    id: "rdo-1",
    obraId: "obra-1",
    servicosExecutados: [
      {
        localId: "execucao-1",
        servicoNome: "Fresagem",
        trechoInicial: "102,000",
        trechoFinal: "104,000",
      },
      {
        localId: "execucao-2",
        servicoNome: "Imprimação",
        trechoInicial: "106,000",
        trechoFinal: "108,000",
      },
    ],
  };
}

beforeEach(() => {
  getLocalRdo.mockReset();
  getLocalRdo.mockResolvedValue({ id: "rdo-1", obraId: "obra-1" });
  rdoDraftFromLocalRecord.mockReset();
  rdoDraftFromLocalRecord.mockImplementation(() => rascunho());
  saveExistingRdoDraftAtomically.mockReset();
  saveExistingRdoDraftAtomically.mockResolvedValue({});
});

describe("kmDaCorrecao", () => {
  it("lê o quilômetro dos dois extremos arrastados", () => {
    expect(
      kmDaCorrecao(EIXO, { lat: 0, lng: 0.5 }, { lat: 0, lng: 1.5 }),
    ).toEqual({ kmInicial: "102,500", kmFinal: "107,500" });
  });

  /*
   * O km 206,822 é uma marcação real. Arredondá-lo para uma casa moveria o
   * trecho oitenta metros na rodovia.
   */
  it("escreve três casas, que é a precisão da rodovia", () => {
    const km = kmDaCorrecao(
      EIXO,
      { lat: 0, lng: 0.4321 },
      { lat: 0, lng: 0.9 },
    );
    expect(km?.kmInicial).toMatch(/^\d+,\d{3}$/);
  });
});

describe("corrigirKmPeloMapa", () => {
  const correcao = {
    obraId: "obra-1",
    rdoId: "rdo-1",
    execucaoId: "execucao-1",
    eixo: EIXO,
    inicio: { lat: 0, lng: 0.5 },
    fim: { lat: 0, lng: 1.5 },
  };

  it("escreve o quilômetro na linha de serviço, e só nela", async () => {
    await expect(corrigirKmPeloMapa(correcao)).resolves.toEqual({
      kmInicial: "102,500",
      kmFinal: "107,500",
    });

    const salvo = saveExistingRdoDraftAtomically.mock.calls.at(-1)?.[0];
    expect(salvo.servicosExecutados[0]).toMatchObject({
      localId: "execucao-1",
      trechoInicial: "102,500",
      trechoFinal: "107,500",
    });
    // A outra frente do mesmo dia não é tocada: a correção fala de uma linha.
    expect(salvo.servicosExecutados[1]).toMatchObject({
      trechoInicial: "106,000",
      trechoFinal: "108,000",
    });
  });

  /*
   * O mapa lê uma obra só, então isto não deveria acontecer. Mas escrever
   * quilômetro no RDO errado é o tipo de engano que ninguém percebe depois.
   */
  it("recusa o apontamento de outra obra", async () => {
    getLocalRdo.mockResolvedValue({ id: "rdo-1", obraId: "obra-2" });

    await expect(corrigirKmPeloMapa(correcao)).rejects.toThrow(
      /outra obra/i,
    );
    expect(saveExistingRdoDraftAtomically).not.toHaveBeenCalled();
  });

  it("recusa quando o RDO não está neste aparelho", async () => {
    getLocalRdo.mockResolvedValue(null);

    await expect(corrigirKmPeloMapa(correcao)).rejects.toThrow(
      /não está neste aparelho/i,
    );
    expect(saveExistingRdoDraftAtomically).not.toHaveBeenCalled();
  });

  it("recusa quando a linha de serviço não está no rascunho", async () => {
    await expect(
      corrigirKmPeloMapa({ ...correcao, execucaoId: "execucao-sumida" }),
    ).rejects.toThrow(/linha de serviço/i);
    expect(saveExistingRdoDraftAtomically).not.toHaveBeenCalled();
  });

  /*
   * Nada de geometria é gravado: a linha derivada não existe como registro, e
   * gravá-la recriaria a segunda cópia da posição que o eixo existe para
   * evitar.
   */
  it("não grava geometria nenhuma", async () => {
    await corrigirKmPeloMapa(correcao);

    const salvo = saveExistingRdoDraftAtomically.mock.calls.at(-1)?.[0];
    expect(salvo).not.toHaveProperty("geometry");
    expect(saveExistingRdoDraftAtomically).toHaveBeenCalledTimes(1);
  });
});
