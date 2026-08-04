import { describe, expect, it } from "vitest";

import { acharRdoDoDia } from "./rdoDoTrechoDesenhado";
import type { LocalRdoRecord } from "../../../lib/db/db.types";

function rdo(values: Partial<LocalRdoRecord>): LocalRdoRecord {
  return {
    id: "rdo-1",
    obraId: "obra-1",
    programacaoId: null,
    numeroRdo: "RDO-0001",
    dataRdo: "2026-08-04",
    statusRdo: "RASCUNHO",
    syncStatus: "SYNCED",
    versaoEntidade: 1,
    payload: {},
    createdAt: "2026-08-04T10:00:00.000Z",
    updatedAt: "2026-08-04T10:00:00.000Z",
    ...values,
  };
}

/**
 * A que apontamento um desenho pertence.
 *
 * O trecho desenhado nascia sem dono: `objetoTipo: "TRECHO"` apontando para a
 * própria obra, uma auto-referência sem entidade do outro lado. Ele não sabia
 * de que dia falava, então sobrevivia ao RDO que representava — foi assim que
 * um trecho continuou na tela depois de o apontamento ser apagado.
 */
describe("RDO do trecho desenhado", () => {
  it("encontra o apontamento do mesmo dia", () => {
    const encontrado = acharRdoDoDia(
      [
        rdo({ id: "de-ontem", dataRdo: "2026-08-03" }),
        rdo({ id: "de-hoje", dataRdo: "2026-08-04" }),
      ],
      "2026-08-04",
    );

    expect(encontrado?.id).toBe("de-hoje");
  });

  it("não devolve nada quando o dia ainda não tem apontamento", () => {
    expect(
      acharRdoDoDia([rdo({ dataRdo: "2026-08-03" })], "2026-08-04"),
    ).toBeNull();
  });

  /**
   * Um RDO apagado não recebe desenho novo: o trecho iria para um apontamento
   * que já saiu de todos os números, e sumiria da tela no mesmo instante.
   */
  it("ignora o apontamento cancelado", () => {
    expect(
      acharRdoDoDia(
        [rdo({ id: "cancelado", statusRdo: "CANCELADA" })],
        "2026-08-04",
      ),
    ).toBeNull();
    expect(
      acharRdoDoDia(
        [rdo({ id: "apagado", canceladoEm: "2026-08-04T11:00:00.000Z" })],
        "2026-08-04",
      ),
    ).toBeNull();
  });

  /**
   * Turnos e frentes distintas podem abrir mais de um RDO no mesmo dia. O
   * desenho vai para o que está sendo mexido agora; mandá-lo para o mais
   * antigo o colocaria num apontamento que a pessoa já fechou.
   */
  it("prefere o apontamento mexido mais recentemente", () => {
    const encontrado = acharRdoDoDia(
      [
        rdo({ id: "manha", updatedAt: "2026-08-04T08:00:00.000Z" }),
        rdo({ id: "tarde", updatedAt: "2026-08-04T15:00:00.000Z" }),
      ],
      "2026-08-04",
    );

    expect(encontrado?.id).toBe("tarde");
  });
});
