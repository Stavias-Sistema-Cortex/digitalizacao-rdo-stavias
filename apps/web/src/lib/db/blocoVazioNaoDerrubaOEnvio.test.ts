import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { clearSession, setSession } from "../../features/auth/authSession";
import { closeCortexDb, getCortexDb } from "./cortexDb";
import { databaseNameForScope } from "./localDataNamespace";
import {
  createEmptyRdo,
  createEmptyServicoExecutado,
} from "../../features/rdos/createEmptyRdo";
import { saveLocalPendingRdoDraftAtomically } from "./localRdoService";
import type { RdoDraft } from "../../features/rdos/rdo.types";

/**
 * Um bloco pela metade não pode custar o dia inteiro de apontamento.
 *
 * <p>A linha de serviço sem quantidade era descartada em silêncio antes de
 * subir: quem apontava o serviço e não media a quantidade via a linha sumir do
 * envio sem nada dizer. E o trecho desenhado no mapa nascia exatamente assim —
 * com nome e sem número — então ele nunca chegava ao servidor.
 *
 * <p>Do outro lado, a linha que passava pelo filtro e chegava incompleta
 * levava o RDO junto: a recusa do servidor é 400, a fila não reenvia 400, e o
 * apontamento inteiro ficava parado sem que a tela dissesse qual campo era o
 * culpado.
 */

const OBRA_ID = "00000000-0000-4000-8000-000000000701";
const RDO_ID = "00000000-0000-4000-8000-000000000702";

let databaseName = "";
let userId = "";

function rascunho(
  servicos: RdoDraft["servicosExecutados"],
): RdoDraft {
  return {
    ...createEmptyRdo(),
    id: RDO_ID,
    obraId: OBRA_ID,
    dataRdo: "2026-08-10",
    numeroRdo: "RDO-0042",
    servicosExecutados: servicos,
  };
}

async function servicosEnviados(): Promise<Record<string, unknown>[]> {
  const database = await getCortexDb();
  const fila = await database.getAllFromIndex(
    "outbox_mutations",
    "by-entity-id",
    RDO_ID,
  );
  const payload = fila.at(-1)?.payload as Record<string, unknown>;
  return (payload?.servicosExecutados ?? []) as Record<string, unknown>[];
}

beforeEach(async () => {
  userId = crypto.randomUUID();
  setSession({
    colaboradorId: userId,
    nome: "Encarregado",
    papelAcesso: "BETA",
    escopoGlobal: false,
    obraIds: [OBRA_ID],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  });
  databaseName = await databaseNameForScope(userId, `BETA:${OBRA_ID}`);
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) await deleteDB(databaseName);
  clearSession();
});

describe("bloco de serviço incompleto", () => {
  it("sobe a linha que tem serviço e não tem quantidade", async () => {
    await saveLocalPendingRdoDraftAtomically(
      rascunho([
        {
          ...createEmptyServicoExecutado(),
          serviceId: "srv-1",
          servicoNome: "Fresagem funcional",
          quantidadeExecutada: "",
        },
      ]),
    );

    const enviados = await servicosEnviados();
    expect(enviados).toHaveLength(1);
    expect(enviados[0]).toMatchObject({
      servicoNome: "Fresagem funcional",
    });
  });

  /*
   * O envelope canônico não transporta `undefined`, e "" não é número. Null é
   * o que o servidor lê como "não medido" — e o que ele aceita.
   */
  it("manda a quantidade ausente como nula, não como texto vazio", async () => {
    await saveLocalPendingRdoDraftAtomically(
      rascunho([
        {
          ...createEmptyServicoExecutado(),
          serviceId: "srv-1",
          servicoNome: "Fresagem funcional",
          quantidadeExecutada: "",
        },
      ]),
    );

    const enviados = await servicosEnviados();
    expect(enviados[0].quantidadeExecutada).toBeNull();
  });

  it("continua deixando para trás a linha que não diz nada", async () => {
    await saveLocalPendingRdoDraftAtomically(
      rascunho([createEmptyServicoExecutado()]),
    );

    expect(await servicosEnviados()).toEqual([]);
  });

  it("sobe a linha sem unidade, que a tabela passou a aceitar", async () => {
    await saveLocalPendingRdoDraftAtomically(
      rascunho([
        {
          ...createEmptyServicoExecutado(),
          serviceId: "srv-1",
          servicoNome: "Fresagem funcional",
          trechoInicial: "206,822",
          trechoFinal: "207,100",
          quantidadeExecutada: "",
          unidade: "",
        },
      ]),
    );

    const enviados = await servicosEnviados();
    expect(enviados).toHaveLength(1);
    expect(enviados[0]).toMatchObject({
      trechoInicial: "206,822",
      unidade: null,
    });
  });

  /*
   * A linha sem serviço do catálogo fica no aparelho, e isso é deliberado.
   *
   * A porta sem identidade de catálogo é reservada à importação histórica, que
   * passa por controle de procedência; o servidor recusa a porta normal com
   * 400, que é terminal e custaria o RDO inteiro. É o caso do trecho desenhado
   * no mapa, que nomeia o serviço mas não o identifica: ele espera alguém
   * escolher o serviço, que é a única coisa que o completa.
   */
  it("preserva no envelope a linha que ainda não escolheu o serviço", async () => {
    await saveLocalPendingRdoDraftAtomically(
      rascunho([
        {
          ...createEmptyServicoExecutado(),
          servicoNome: "Serviço a identificar",
          trechoInicial: "206,822",
          trechoFinal: "207,100",
        },
      ]),
    );

    expect(await servicosEnviados()).toEqual([
      expect.objectContaining({
        servicoNome: "Serviço a identificar",
        serviceId: null,
      }),
    ]);
  });

  it("não mexe na linha que veio completa", async () => {
    await saveLocalPendingRdoDraftAtomically(
      rascunho([
        {
          ...createEmptyServicoExecutado(),
          serviceId: "srv-1",
          servicoNome: "Fresagem funcional",
          quantidadeExecutada: 1250.5,
          unidade: "M²",
        },
      ]),
    );

    const enviados = await servicosEnviados();
    expect(enviados[0]).toMatchObject({
      quantidadeExecutada: 1250.5,
      unidade: "M²",
    });
  });
});
