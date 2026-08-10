import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { clearSession, setSession } from "../auth/authSession";
import { closeCortexDb, getCortexDb } from "../../lib/db/cortexDb";
import { databaseNameForScope } from "../../lib/db/localDataNamespace";
import type { ServiceCatalogLocalRecord } from "../../lib/db/db.types";
import {
  queueExcluirServico,
  queueRestaurarServico,
} from "./servicePriceRepository";

/**
 * Excluir um serviço tira-o de circulação sem apagar o que ele produziu.
 *
 * <p>O catálogo não tinha estado de saída: o CHECK do banco só admitia
 * ACTIVE, então "apagar" só poderia ser DELETE — e um DELETE levaria junto o
 * histórico de preços e a referência dos RDOs que já o executaram. Estes
 * testes prendem a regra pelo lado do aparelho: a transição entra na fila, o
 * preço fica onde está, e repetir o gesto não vira uma segunda exclusão.
 */

const OBRA_ID = "00000000-0000-4000-8000-000000000601";
const SERVICO_ID = "00000000-0000-4000-8000-000000000602";
const PRECO_ID = "00000000-0000-4000-8000-000000000603";
const OCCURRED_AT = "2026-08-10T13:00:00.000Z";

let databaseName = "";
let userId = "";

function servico(
  overrides: Partial<ServiceCatalogLocalRecord> = {},
): ServiceCatalogLocalRecord {
  return {
    id: SERVICO_ID,
    code: "FRESAGEM-FUNCIONAL",
    name: "Fresagem Funcional",
    description: null,
    status: "ACTIVE",
    syncStatus: "SYNCED",
    createdAt: OCCURRED_AT,
    updatedAt: OCCURRED_AT,
    lastError: null,
    ...overrides,
  };
}

async function semear(
  registro: ServiceCatalogLocalRecord = servico(),
): Promise<void> {
  const database = await getCortexDb();
  await database.put("service_catalog", registro);
  await database.put("service_price_versions", {
    id: PRECO_ID,
    obraId: OBRA_ID,
    serviceId: SERVICO_ID,
    version: 1,
    unitPrice: 500,
    currency: "BRL",
    unit: "M",
    validFrom: "2026-08-07",
    validTo: null,
    status: "ACTIVE",
    syncStatus: "SYNCED",
    createdAt: OCCURRED_AT,
    updatedAt: OCCURRED_AT,
    lastError: null,
  } as never);
}

beforeEach(async () => {
  userId = crypto.randomUUID();
  setSession({
    colaboradorId: userId,
    nome: "Alfa",
    papelAcesso: "ALFA",
    // Alfa é escopo global, e escopo global não enumera obras: a sessão
    // canônica recusa as duas coisas juntas.
    escopoGlobal: true,
    obraIds: [],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  });
  databaseName = await databaseNameForScope(userId, "ALFA");
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) await deleteDB(databaseName);
  clearSession();
});

describe("serviço excluído do catálogo", () => {
  it("muda o estado e enfileira a transição, sem apagar a linha", async () => {
    await semear();

    await queueExcluirServico(OBRA_ID, SERVICO_ID);

    const database = await getCortexDb();
    const guardado = await database.get("service_catalog", SERVICO_ID);
    expect(guardado).toMatchObject({
      status: "EXCLUIDO",
      syncStatus: "PENDING_SYNC",
    });
    const fila = await database.getAllFromIndex(
      "outbox_mutations",
      "by-entity-id",
      SERVICO_ID,
    );
    expect(fila.map((item) => item.operacao)).toContain(
      "EXCLUIR_SERVICO_CATALOGO",
    );
  });

  /*
   * O ponto central do pedido: o que o serviço já produziu não é tocado.
   */
  it("deixa intacto o preço que o serviço já tinha", async () => {
    await semear();

    await queueExcluirServico(OBRA_ID, SERVICO_ID);

    const database = await getCortexDb();
    const preco = await database.get("service_price_versions", PRECO_ID);
    expect(preco).toMatchObject({ status: "ACTIVE", serviceId: SERVICO_ID });
  });

  it("traz de volta com o mesmo gesto no sentido contrário", async () => {
    await semear(servico({ status: "EXCLUIDO" }));

    await queueRestaurarServico(OBRA_ID, SERVICO_ID);

    const database = await getCortexDb();
    expect(await database.get("service_catalog", SERVICO_ID)).toMatchObject({
      status: "ACTIVE",
    });
    const fila = await database.getAllFromIndex(
      "outbox_mutations",
      "by-entity-id",
      SERVICO_ID,
    );
    expect(fila.map((item) => item.operacao)).toContain(
      "RESTAURAR_SERVICO_CATALOGO",
    );
  });

  /*
   * Excluir duas vezes é excluir uma. O clique repetido — e o reenvio da fila
   * — não podem virar uma segunda mutação por cima de uma restauração.
   */
  it("não enfileira nada quando o serviço já está no estado pedido", async () => {
    await semear(servico({ status: "EXCLUIDO" }));

    await queueExcluirServico(OBRA_ID, SERVICO_ID);

    const database = await getCortexDb();
    const fila = await database.getAllFromIndex(
      "outbox_mutations",
      "by-entity-id",
      SERVICO_ID,
    );
    expect(fila).toEqual([]);
  });

  it("recusa o serviço que este aparelho não conhece", async () => {
    await expect(
      queueExcluirServico(OBRA_ID, SERVICO_ID),
    ).rejects.toThrow(/não encontrado/i);
  });
});
