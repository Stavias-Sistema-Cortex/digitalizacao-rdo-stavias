import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { clearSession, setSession } from "../auth/authSession";
import { closeCortexDb, getCortexDb } from "../../lib/db/cortexDb";
import { databaseNameForScope } from "../../lib/db/localDataNamespace";
import type { ServiceCatalogPage } from "./servicePriceApi";
import {
  applyPushResultAtomically,
  markMutationAsSyncing,
} from "../../lib/sync/syncStorage";
import {
  cacheFinanceCapabilities,
  getCachedFinanceCapabilities,
  hydrateServiceCatalog,
  listLocalServiceCatalog,
  queueCreatePrice,
  queueCreateService,
} from "./servicePriceRepository";

const OBRA_ID = "00000000-0000-4000-8000-000000000101";
const USER_ID = "00000000-0000-4000-8000-000000000201";
const SESSION_EXPIRY = "2099-07-22T12:00:00.000Z";

let databaseName = "";

function session(expiraEm = SESSION_EXPIRY) {
  return {
    colaboradorId: USER_ID,
    nome: "Encarregado financeiro",
    papelAcesso: "BETA" as const,
    escopoGlobal: false,
    obraIds: [OBRA_ID],
    expiraEm,
  };
}

function remoteCatalog(serviceId: string): ServiceCatalogPage {
  return {
    items: [{
      service: {
        id: serviceId,
        code: "PAV.CBUQ",
        name: "Pavimentação CBUQ",
        description: null,
        status: "ACTIVE",
        createdAt: "2026-07-22T12:00:00.000Z",
      },
      priceVersions: [{
        id: "00000000-0000-4000-8000-000000000401",
        obraId: OBRA_ID,
        serviceId,
        unit: "M2",
        currency: "BRL",
        version: 1,
        unitPrice: "125.5000",
        contractedQuantity: "800.000",
        validFrom: "2026-07-22",
        validTo: null,
        supersedesId: null,
        status: "ACTIVE",
        effectiveValidTo: null,
        createdAt: "2026-07-22T12:00:00.000Z",
        source: "CONTRATO_MEDIDO",
        entityVersion: 2,
      }],
    }],
    nextCursor: null,
    authorizedItemCount: 1,
    authorizedPriceVersionCount: 1,
    authorizedCancellationCount: 0,
    returnedItemCount: 1,
    returnedPriceVersionCount: 1,
    returnedCancellationCount: 0,
    coverage: "COMPLETE",
    highWaterMark: 7,
  };
}

beforeEach(async () => {
  vi.stubGlobal("window", new EventTarget());
  setSession(session());
  databaseName = await databaseNameForScope(USER_ID, `BETA:${OBRA_ID}`);
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) await deleteDB(databaseName);
  clearSession();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("offline service and price catalog", () => {
  it("queues canonical service and dependent price mutations while remaining readable offline", async () => {
    const service = await queueCreateService(OBRA_ID, {
      code: "pav.cbuq",
      name: "Pavimentação CBUQ",
    });
    const price = await queueCreatePrice(OBRA_ID, service.entityId, {
      unit: "m2",
      currency: "brl",
      unitPrice: "125,50",
      contractedQuantity: "800,000",
      validFrom: "2026-07-22",
      source: "contrato_medido",
    });

    const database = await getCortexDb();
    const serviceMutation = await database.get(
      "outbox_mutations",
      service.clientMutationId,
    );
    const priceMutation = await database.get(
      "outbox_mutations",
      price.clientMutationId,
    );
    const rows = await listLocalServiceCatalog(OBRA_ID);

    expect(serviceMutation).toMatchObject({
      schemaVersion: 13,
      entidadeTipo: "SERVICE",
      entidadeId: service.entityId,
      operacao: "CRIAR_SERVICO_CATALOGO",
      status: "PENDING",
    });
    expect(priceMutation).toMatchObject({
      schemaVersion: 13,
      entidadeTipo: "SERVICE_PRICE_VERSION",
      entidadeId: price.entityId,
      operacao: "CRIAR_PRECO_SERVICO",
      status: "PENDING",
      dependsOnMutationIds: [service.clientMutationId],
    });
    expect(rows).toHaveLength(1);
    expect(rows[0].service).toMatchObject({
      code: "PAV.CBUQ",
      syncStatus: "PENDING_SYNC",
    });
    expect(rows[0].priceVersions[0]).toMatchObject({
      unitPrice: "125.50",
      contractedQuantity: "800.000",
      syncStatus: "PENDING_SYNC",
      entityVersion: 0,
    });
  });

  it("rejects a non-BRL price before creating local or outbox records", async () => {
    const service = await queueCreateService(OBRA_ID, {
      code: "PAV.CBUQ",
      name: "Pavimentação CBUQ",
    });
    const database = await getCortexDb();
    await database.clear("outbox_mutations");

    await expect(queueCreatePrice(OBRA_ID, service.entityId, {
      unit: "M2",
      currency: "USD",
      unitPrice: "125.50",
      contractedQuantity: "800.000",
      validFrom: "2026-07-22",
      source: "CONTRATO_MEDIDO",
    })).rejects.toThrow(/BRL/i);

    await expect(database.getAll("service_price_versions")).resolves.toEqual([]);
    await expect(database.getAll("outbox_mutations")).resolves.toEqual([]);
  });

  it("rejects an invalid source before creating local or outbox records", async () => {
    const service = await queueCreateService(OBRA_ID, {
      code: "PAV.CBUQ",
      name: "Pavimentação CBUQ",
    });
    const database = await getCortexDb();
    await database.clear("outbox_mutations");

    await expect(queueCreatePrice(OBRA_ID, service.entityId, {
      unit: "M2",
      currency: "BRL",
      unitPrice: "125.50",
      contractedQuantity: "800.000",
      validFrom: "2026-07-22",
      source: "Contrato, medição ou aditivo",
    })).rejects.toThrow(/fonte/i);

    await expect(database.getAll("service_price_versions")).resolves.toEqual([]);
    await expect(database.getAll("outbox_mutations")).resolves.toEqual([]);
  });

  it("rejects a non-positive contracted quantity before queueing", async () => {
    const service = await queueCreateService(OBRA_ID, {
      code: "PAV.CBUQ",
      name: "Pavimentação CBUQ",
    });
    const database = await getCortexDb();
    await database.clear("outbox_mutations");

    await expect(queueCreatePrice(OBRA_ID, service.entityId, {
      unit: "M2",
      currency: "BRL",
      unitPrice: "125.50",
      contractedQuantity: "0",
      validFrom: "2026-07-22",
      source: "CONTRATO_MEDIDO",
    })).rejects.toThrow(/quantidade contratada/i);

    await expect(database.getAll("service_price_versions")).resolves.toEqual([]);
    await expect(database.getAll("outbox_mutations")).resolves.toEqual([]);
  });

  it("hydrates authoritative rows and never invents an entity version", async () => {
    const serviceId = "00000000-0000-4000-8000-000000000301";
    await hydrateServiceCatalog(OBRA_ID, remoteCatalog(serviceId), {
      replaceCompleteSnapshot: true,
    });

    const rows = await listLocalServiceCatalog(OBRA_ID, "cbuq");

    expect(rows).toHaveLength(1);
    expect(rows[0].service.syncStatus).toBe("SYNCED");
    expect(rows[0].priceVersions[0]).toMatchObject({
      contractedQuantity: "800.000",
      source: "CONTRATO_MEDIDO",
      entityVersion: 2,
      syncStatus: "SYNCED",
    });
  });

  it("applies automatic sync status and canonical version to the local price", async () => {
    const service = await queueCreateService(OBRA_ID, {
      code: "PAV.CBUQ",
      name: "Pavimentação CBUQ",
    });
    const price = await queueCreatePrice(OBRA_ID, service.entityId, {
      unit: "M2",
      currency: "BRL",
      unitPrice: "125.50",
      contractedQuantity: "800.000",
      validFrom: "2026-07-22",
      source: "CONTRATO_MEDIDO",
    });
    const database = await getCortexDb();
    const mutation = await database.get("outbox_mutations", price.clientMutationId);
    expect(mutation).toBeDefined();

    await markMutationAsSyncing(mutation!);
    await applyPushResultAtomically({
      clientMutationId: price.clientMutationId,
      status: "APLICADA",
      resultado: { versaoEntidade: 1 },
      eventoServidorCommitSeq: 9,
    });

    expect(await database.get("service_price_versions", price.entityId))
      .toMatchObject({
        syncStatus: "SYNCED",
        entityVersion: 1,
        lastError: null,
      });
  });

  it("reuses permissions only for the exact owner, worksite and active session", async () => {
    await cacheFinanceCapabilities({
      obraId: OBRA_ID,
      permissoes: ["FINANCEIRO_VISUALIZAR", "FINANCEIRO_ADMINISTRAR"],
    });

    await expect(getCachedFinanceCapabilities(OBRA_ID)).resolves.toEqual({
      obraId: OBRA_ID,
      permissoes: ["FINANCEIRO_ADMINISTRAR", "FINANCEIRO_VISUALIZAR"],
    });

    setSession(session("2099-07-23T12:00:00.000Z"));
    await expect(getCachedFinanceCapabilities(OBRA_ID)).resolves.toBeNull();
  });
});
