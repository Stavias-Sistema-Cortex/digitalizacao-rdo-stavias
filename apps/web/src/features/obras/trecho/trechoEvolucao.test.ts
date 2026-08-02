import { describe, expect, it } from "vitest";

import {
  LARGURA_DO_DIA_PX,
  acumuladoAteODia,
  indiceDoDiaNaRolagem,
  rolagemDoIndice,
} from "./trechoEvolucao";
import type { DiaExecutado } from "./trechoGeometry";

function dia(data: string, extensaoM: number, totalRdos = 1): DiaExecutado {
  return {
    data,
    totalSegmentos: 1,
    totalRdos,
    extensaoM,
    massaTonelada: extensaoM / 10,
    kmMin: 100,
    kmMax: 101,
  };
}

describe("rolagem da rodovia no tempo", () => {
  it("converte a posição de rolagem no dia sob a agulha, com prisão nas pontas", () => {
    expect(indiceDoDiaNaRolagem(5, 0)).toBe(0);
    expect(indiceDoDiaNaRolagem(5, LARGURA_DO_DIA_PX)).toBe(1);
    // Meio do caminho arredonda para o dia mais próximo.
    expect(indiceDoDiaNaRolagem(5, LARGURA_DO_DIA_PX * 2.4)).toBe(2);
    expect(indiceDoDiaNaRolagem(5, LARGURA_DO_DIA_PX * 2.6)).toBe(3);
    // Rolagem além das pontas não inventa dia.
    expect(indiceDoDiaNaRolagem(5, -50)).toBe(0);
    expect(indiceDoDiaNaRolagem(5, LARGURA_DO_DIA_PX * 40)).toBe(4);
    expect(indiceDoDiaNaRolagem(0, 100)).toBe(-1);
  });

  it("a ida e a volta da conversão são coerentes", () => {
    for (const indice of [0, 1, 2, 7]) {
      expect(indiceDoDiaNaRolagem(8, rolagemDoIndice(indice))).toBe(indice);
    }
  });

  it("acumula produção até o dia, inclusive, sem inventar dias vazios", () => {
    const dias = [
      dia("2026-07-28", 300, 1),
      dia("2026-07-30", 500, 2),
      dia("2026-08-01", 200, 1),
    ];

    expect(acumuladoAteODia(dias, "2026-07-30")).toEqual({
      dias: 2,
      totalRdos: 3,
      extensaoM: 800,
      massaTonelada: 80,
    });
    expect(acumuladoAteODia(dias, "2026-07-29").dias).toBe(1);
    expect(acumuladoAteODia(dias, "2026-08-01").extensaoM).toBe(1000);
    expect(acumuladoAteODia([], "2026-08-01").dias).toBe(0);
  });
});
