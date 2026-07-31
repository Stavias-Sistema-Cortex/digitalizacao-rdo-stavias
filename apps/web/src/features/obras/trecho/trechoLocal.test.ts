import { describe, expect, it } from "vitest";

import type {
  LocalRdoControleGeometricoRecord,
  LocalRdoRecord,
} from "../../../lib/db/db.types";
import {
  quilometroDeTexto,
  rodoviaDosRdosLocais,
  segmentosDoRdoLocal,
} from "./trechoLocal";

function rdo(overrides: Partial<LocalRdoRecord> = {}): LocalRdoRecord {
  return {
    id: "rdo-local-1",
    obraId: "obra-1",
    programacaoId: null,
    numeroRdo: "RDO-118",
    dataRdo: "2026-03-09",
    statusRdo: "RASCUNHO",
    syncStatus: "PENDING_SYNC",
    versaoEntidade: null,
    payload: {},
    createdAt: "2026-03-09T11:00:00.000Z",
    updatedAt: "2026-03-09T11:00:00.000Z",
    ...overrides,
  };
}

function controle(
  payload: Record<string, unknown>,
  id = "ctrl-local-1",
): LocalRdoControleGeometricoRecord {
  return {
    id,
    rdoId: "rdo-local-1",
    localId: id,
    syncStatus: "PENDING_SYNC",
    payload,
    createdAt: "2026-03-09T11:00:00.000Z",
    updatedAt: "2026-03-09T11:00:00.000Z",
  };
}

describe("quilometroDeTexto", () => {
  it("aceita os formatos que o campo realmente digita", () => {
    expect(quilometroDeTexto("309.04")).toBe(309.04);
    expect(quilometroDeTexto("309,2")).toBe(309.2);
    expect(quilometroDeTexto("km 172")).toBe(172);
    expect(quilometroDeTexto("KM172")).toBe(172);
    // Notação rodoviária: quilômetro mais metros.
    expect(quilometroDeTexto("309+400")).toBe(309.4);
  });

  it("recusa texto irreconhecível sem inventar zero", () => {
    expect(quilometroDeTexto("")).toBeNull();
    expect(quilometroDeTexto("   ")).toBeNull();
    expect(quilometroDeTexto("trecho urbano")).toBeNull();
    expect(quilometroDeTexto(null)).toBeNull();
    expect(quilometroDeTexto(undefined)).toBeNull();
  });

  it("preserva o quilômetro zero, que é uma marcação válida", () => {
    expect(quilometroDeTexto("0")).toBe(0);
  });
});

describe("segmentosDoRdoLocal", () => {
  it("desenha o controle geométrico lançado no aparelho", () => {
    const segmentos = segmentosDoRdoLocal(rdo(), [
      controle({
        subtrecho: "Subtrecho 2",
        kmInicial: "174",
        kmFinal: "172,5",
        pista: "SUL",
        faixa: "1",
        comprimentoM: 1500,
        larguraM: 3.5,
        estacaInicial: "E-100",
        estacaFinal: "E-175",
        atividadeObservacoes: "Recapeamento",
      }),
    ]);

    expect(segmentos).toHaveLength(1);
    expect(segmentos[0]).toMatchObject({
      origem: "RDO_CONTROLE",
      procedencia: "DISPOSITIVO",
      rdoId: "rdo-local-1",
      numeroRdo: "RDO-118",
      data: "2026-03-09",
      servicoNome: "Recapeamento",
      subtrecho: "Subtrecho 2",
      pista: "SUL",
      faixa: "1",
      kmInicial: 174,
      kmFinal: 172.5,
      extensaoM: 1500,
      larguraM: 3.5,
      rdoStatus: "RASCUNHO",
    });
  });

  it("deriva a extensão dos quilômetros só quando ela não foi medida", () => {
    const [medido] = segmentosDoRdoLocal(rdo(), [
      controle({ kmInicial: "174", kmFinal: "172", comprimentoM: 1800 }),
    ]);
    expect(medido.extensaoM).toBe(1800);

    const [derivado] = segmentosDoRdoLocal(rdo(), [
      controle({ kmInicial: "174", kmFinal: "172" }),
    ]);
    expect(derivado.extensaoM).toBe(2000);
  });

  it("não posiciona lançamento sem marcação quilométrica utilizável", () => {
    const [segmento] = segmentosDoRdoLocal(rdo(), [
      controle({ kmInicial: "", kmFinal: "sem medição", comprimentoM: 900 }),
    ]);
    expect(segmento.kmInicial).toBeNull();
    expect(segmento.kmFinal).toBeNull();
    // A extensão medida permanece: ela existe, só não é posicionável.
    expect(segmento.extensaoM).toBe(900);
  });

  it("lê também os serviços executados apontados no RDO", () => {
    const segmentos = segmentosDoRdoLocal(
      rdo({
        payload: {
          servicosExecutados: [
            {
              id: "serv-1",
              servicoNome: "Fresagem",
              trechoInicial: "174",
              trechoFinal: "173",
              localizacao: "Pista sul",
              statusValidacao: "PENDENTE",
            },
          ],
        },
      }),
      [],
    );

    expect(segmentos).toHaveLength(1);
    expect(segmentos[0]).toMatchObject({
      origem: "EXECUCAO_SERVICO",
      procedencia: "DISPOSITIVO",
      servicoNome: "Fresagem",
      subtrecho: "Pista sul",
      kmInicial: 174,
      kmFinal: 173,
      status: "PENDENTE",
    });
  });

  it("dá identificador estável e distinto a cada lançamento", () => {
    const segmentos = segmentosDoRdoLocal(
      rdo({
        payload: {
          servicosExecutados: [{ id: "serv-1", servicoNome: "Fresagem" }],
        },
      }),
      [controle({ kmInicial: "174", kmFinal: "173" }, "ctrl-a")],
    );

    expect(segmentos.map((item) => item.id)).toEqual([
      "local:rdo-local-1:controle:ctrl-a",
      "local:rdo-local-1:servico:serv-1",
    ]);
  });

  it("tolera RDO sem nenhum lançamento de trecho", () => {
    expect(segmentosDoRdoLocal(rdo(), [])).toEqual([]);
  });
});

describe("rodoviaDosRdosLocais", () => {
  it("usa a rodovia declarada no RDO mais recente que a informou", () => {
    const rodovia = rodoviaDosRdosLocais([
      rdo({ id: "a", dataRdo: "2026-03-01", payload: { rodovia: "SP-310" } }),
      rdo({ id: "b", dataRdo: "2026-03-09", payload: { rodovia: "SP-225" } }),
    ]);
    expect(rodovia).toBe("SP-225");
  });

  it("devolve nulo quando nenhum RDO declara rodovia", () => {
    expect(rodoviaDosRdosLocais([rdo({ payload: {} })])).toBeNull();
  });
});
