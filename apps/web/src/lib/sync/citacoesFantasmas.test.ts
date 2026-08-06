import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { clearSession, setSession } from "../../features/auth/authSession";
import { closeCortexDb, getCortexDb } from "../db/cortexDb";
import { databaseNameForScope } from "../db/localDataNamespace";
import { desamarrarCitacoesFantasmas } from "./syncStorage";

const COLABORADOR_ID = "00000000-0000-4000-8000-0000000009f1";
const OBRA_ID = "00000000-0000-4000-8000-0000000009f2";

let databaseName = "";

async function semear(
  id: string,
  status: string,
  extras: Record<string, unknown> = {},
) {
  const database = await getCortexDb();
  await database.put("outbox_mutations", {
    clientMutationId: id,
    entidadeTipo: "EQUIPE",
    entidadeId: `entidade-${id}`,
    obraId: OBRA_ID,
    operacao: "ADICIONAR_MEMBRO_EQUIPE",
    status,
    baseVersao: 1,
    tentativas: 0,
    retryAttempt: 0,
    nextAttemptAt: null,
    blockedReason: null,
    createdAt: "2026-08-05T12:00:00.000Z",
    updatedAt: "2026-08-05T12:00:00.000Z",
    payload: {},
    ...extras,
  } as never);
}

async function carregar(id: string) {
  return (await getCortexDb()).get("outbox_mutations", id) as Promise<
    Record<string, unknown>
  >;
}

describe("citações a mutações que já não existem", () => {
  beforeEach(async () => {
    setSession({
      colaboradorId: COLABORADOR_ID,
      nome: "Beta",
      papelAcesso: "BETA",
      escopoGlobal: false,
      obraIds: [OBRA_ID],
      expiraEm: "2099-01-01T00:00:00.000Z",
    });
    databaseName = await databaseNameForScope(
      COLABORADOR_ID,
      `BETA:${OBRA_ID}`,
    );
    await (await getCortexDb()).clear("outbox_mutations");
  });

  afterEach(async () => {
    await closeCortexDb();
    if (databaseName) await deleteDB(databaseName);
    clearSession();
  });

  /*
   * O caso real: uma adição de membro citava uma mutação que um descarte levou
   * embora e chegou a 164 tentativas — o servidor recusa citação a algo que
   * nunca vai ser aplicado, e recusa para sempre. As tentativas voltam a zero
   * porque a mutação reparada não é mais a que falhou 164 vezes; herdar o
   * backoff dela adiaria o primeiro envio que tem chance real.
   */
  it("tira a citação morta e zera a insistência acumulada", async () => {
    await semear("presa", "PENDING", {
      dependsOnMutationIds: ["fantasma-descartado"],
      tentativas: 164,
      ultimoErro:
        "Uma ou mais dependências causais ainda não foram aplicadas.",
      lastSafeCode: "SERVER_TRANSIENT",
    });

    expect(await desamarrarCitacoesFantasmas()).toBe(1);

    const reparada = await carregar("presa");
    expect(reparada.dependsOnMutationIds).toEqual([]);
    expect(reparada.tentativas).toBe(0);
    expect(reparada.ultimoErro).toBeNull();
    expect(reparada.lastSafeCode).toBeNull();
  });

  /* Enquanto o citado está na fila, ele existe — a citação fica. */
  it("preserva citação a quem está na fila e ainda pode ser aplicado", async () => {
    await semear("aplicada-citada", "SYNCED");
    await semear("pendente-citada", "PENDING");
    await semear("dependente", "PENDING", {
      dependsOnMutationIds: ["aplicada-citada", "pendente-citada"],
      tentativas: 3,
    });

    expect(await desamarrarCitacoesFantasmas()).toBe(0);

    const intacta = await carregar("dependente");
    expect(intacta.dependsOnMutationIds).toEqual([
      "aplicada-citada",
      "pendente-citada",
    ]);
    expect(intacta.tentativas).toBe(3);
  });

  it("num misto, só o fantasma sai", async () => {
    await semear("viva", "PENDING");
    await semear("mista", "PENDING", {
      dependsOnMutationIds: ["viva", "fantasma"],
    });

    expect(await desamarrarCitacoesFantasmas()).toBe(1);

    expect((await carregar("mista")).dependsOnMutationIds).toEqual(["viva"]);
  });

  /*
   * O segundo tipo de fantasma, e o que sobrou preso depois do primeiro
   * conserto: o citado continua na fila, mas morto. O servidor nunca vai
   * marcá-lo como aplicado, então a citação condena o dependente ao mesmo
   * reenvio eterno de uma citação a quem já sumiu.
   */
  it("tira a citação a quem continua na fila, mas morto", async () => {
    await semear("recusada", "REJECTED");
    await semear("conflituosa", "CONFLICT");
    await semear("presa-em-morto", "PENDING", {
      dependsOnMutationIds: ["recusada", "conflituosa"],
      tentativas: 87,
    });

    expect(await desamarrarCitacoesFantasmas()).toBe(1);

    const solta = await carregar("presa-em-morto");
    expect(solta.dependsOnMutationIds).toEqual([]);
    expect(solta.tentativas).toBe(0);
  });

  /*
   * As citações de uma mutação morta são a memória de quem a substituiu.
   * Reescrevê-las mudaria o veredito de quem pergunta se ela foi superada.
   */
  it("não mexe nas citações de quem já morreu", async () => {
    await semear("recusada", "REJECTED", {
      dependsOnMutationIds: ["fantasma"],
    });
    await semear("em-conflito", "CONFLICT", {
      dependsOnMutationIds: ["fantasma"],
    });

    expect(await desamarrarCitacoesFantasmas()).toBe(0);

    expect((await carregar("recusada")).dependsOnMutationIds)
      .toEqual(["fantasma"]);
    expect((await carregar("em-conflito")).dependsOnMutationIds)
      .toEqual(["fantasma"]);
  });

  /*
   * A cadeia da captura real: seis pendentes, a cabeça citando o fantasma e as
   * demais citando umas às outras. Só a cabeça é reparada — o resto da
   * corrente está são e continua esperando na ordem certa.
   */
  it("numa corrente, repara só o elo que cita o fantasma", async () => {
    await semear("cabeca", "PENDING", {
      dependsOnMutationIds: ["fantasma"],
      tentativas: 164,
    });
    for (let i = 1; i <= 5; i += 1) {
      await semear(`elo-${i}`, "PENDING", {
        dependsOnMutationIds: [i === 1 ? "cabeca" : `elo-${i - 1}`],
      });
    }

    expect(await desamarrarCitacoesFantasmas()).toBe(1);

    expect((await carregar("cabeca")).dependsOnMutationIds).toEqual([]);
    expect((await carregar("elo-1")).dependsOnMutationIds)
      .toEqual(["cabeca"]);
    expect((await carregar("elo-5")).dependsOnMutationIds).toEqual(["elo-4"]);
  });
});
