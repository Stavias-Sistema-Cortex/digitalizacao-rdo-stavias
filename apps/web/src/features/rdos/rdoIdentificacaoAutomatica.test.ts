import { describe, expect, it } from "vitest";

import { dataDeHojeLocal } from "./dataDeHojeLocal";
import { validPreviousRdo } from "./rdoCreationContext";
import { extensionMeters, medidasDoServico } from "./rdoCalculations";

describe("extensão entre dois km", () => {
  /*
   * A conta devolvia "em branco" sempre que o km final fosse menor que o
   * inicial — e é assim que se aponta a pista Sul, cuja quilometragem é
   * decrescente. O trecho existia, tinha dois quilômetros, e a tela dizia que
   * não havia extensão nenhuma.
   */
  it("mede a pista Sul, que vai do km maior para o menor", () => {
    expect(extensionMeters("400", "398")).toBe(2000);
  });

  it("mede igual nos dois sentidos, porque distância não tem sinal", () => {
    expect(extensionMeters("398", "400")).toBe(
      extensionMeters("400", "398"),
    );
  });

  it("aceita vírgula, que é como se digita em campo", () => {
    expect(extensionMeters("400,5", "400")).toBe(500);
  });

  it("continua em branco quando falta um dos km", () => {
    expect(extensionMeters("", "400")).toBeNull();
    expect(extensionMeters("400", "")).toBeNull();
    expect(extensionMeters("abc", "400")).toBeNull();
  });

  it("trata trecho de comprimento zero como zero, não como ausente", () => {
    expect(extensionMeters("400", "400")).toBe(0);
  });
});

describe("data sugerida do RDO", () => {
  /*
   * Local e não UTC: às 21h em Pirassununga o dia UTC já virou, e o apontador
   * receberia amanhã como sugestão de hoje.
   */
  it("usa o dia do fuso do aparelho, não o de UTC", () => {
    const noiteNoBrasil = new Date(2026, 7, 5, 21, 30);
    expect(dataDeHojeLocal(noiteNoBrasil)).toBe("2026-08-05");
  });

  it("preenche mês e dia com dois dígitos", () => {
    expect(dataDeHojeLocal(new Date(2026, 0, 9))).toBe("2026-01-09");
  });
});

describe("medidas do serviço executado", () => {
  const base = {
    trechoInicial: "400",
    trechoFinal: "398",
    larguraM: 7 as const,
    espessuraCm: 5 as const,
  };

  it("fecha comprimento, área e volume a partir das parcelas", () => {
    expect(medidasDoServico(base)).toEqual({
      comprimentoM: 2000,
      areaM2: 14000,
      volumeM3: 700,
    });
  });

  /*
   * Medida ausente não é medida zero. Mostrar zero onde ninguém mediu faria o
   * relatório somar produção que não aconteceu.
   */
  it("deixa área e volume em branco quando falta a parcela", () => {
    expect(medidasDoServico({ ...base, larguraM: "" })).toEqual({
      comprimentoM: 2000,
      areaM2: null,
      volumeM3: null,
    });
    expect(medidasDoServico({ ...base, espessuraCm: "" })).toEqual({
      comprimentoM: 2000,
      areaM2: 14000,
      volumeM3: null,
    });
  });

  it("herda o sentido decrescente do comprimento", () => {
    expect(medidasDoServico({ ...base, trechoInicial: "398", trechoFinal: "400" }))
      .toEqual(medidasDoServico(base));
  });

  it("fica tudo em branco sem os km", () => {
    expect(medidasDoServico({ ...base, trechoInicial: "" })).toEqual({
      comprimentoM: null,
      areaM2: null,
      volumeM3: null,
    });
  });
});

describe("RDO anterior do mesmo dia", () => {
  const contexto = {
    obra: { id: "obra-1" },
    data: "2026-05-08",
    previousRdo: { id: "rdo-0002", dataRdo: "2026-05-08" },
    provenance: { previousRdoId: "rdo-0002" },
  } as never;

  /*
   * Quem monta o rascunho aceitava anterior de data igual; quem validava a
   * gravação exigia data estritamente menor. Bastava existir um RDO do mesmo
   * dia na mesma obra para o rascunho nascer apontando para ele e a gravação
   * exigir que não apontasse para nada — nenhum segundo RDO do dia podia ser
   * criado, e a recusa culpava "os dados de cabeçalho".
   */
  it("é reconhecido como anterior, e não descartado por empate de data", () => {
    expect(validPreviousRdo(contexto)?.id).toBe("rdo-0002");
  });

  it("continua descartando o que é posterior à data do RDO", () => {
    expect(
      validPreviousRdo({
        ...contexto,
        previousRdo: { id: "rdo-0003", dataRdo: "2026-05-09" },
        provenance: { previousRdoId: "rdo-0003" },
      } as never),
    ).toBeNull();
  });

  it("continua exigindo que o servidor confirme quem é o anterior", () => {
    expect(
      validPreviousRdo({
        ...contexto,
        provenance: { previousRdoId: "outro" },
      } as never),
    ).toBeNull();
  });

  it("não deixa o RDO ser anterior de si mesmo", () => {
    expect(validPreviousRdo(contexto, "rdo-0002")).toBeNull();
  });
});
