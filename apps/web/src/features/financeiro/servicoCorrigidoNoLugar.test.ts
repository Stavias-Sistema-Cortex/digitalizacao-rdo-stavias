import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { clearSession, setSession } from "../auth/authSession";
import { closeCortexDb, getCortexDb } from "../../lib/db/cortexDb";
import { databaseNameForScope } from "../../lib/db/localDataNamespace";
import type {
  ServiceCatalogLocalRecord,
  ServicePriceVersionLocalRecord,
} from "../../lib/db/db.types";
import {
  queueCreatePrice,
  queueUpdatePrice,
  queueUpdateService,
} from "./servicePriceRepository";

/**
 * Corrigir o que foi digitado errado, sem inventar um registro novo.
 *
 * <p>O catálogo só sabia acrescentar. Um serviço cadastrado com o nome trocado
 * não tinha conserto — sobrava excluir e cadastrar de novo, o que troca o
 * identificador que os RDOs já citam —, e um preço com um zero a mais só podia
 * ser trocado por substituição, que grava no histórico uma revisão contratual
 * que nunca aconteceu.
 *
 * <p>Estes testes prendem os dois lados da regra: a identidade não muda, e a
 * correção do preço reescreve a versão em vez de criar outra.
 */

const OBRA_ID = "00000000-0000-4000-8000-000000000701";
const SERVICO_ID = "00000000-0000-4000-8000-000000000702";
const PRECO_ID = "00000000-0000-4000-8000-000000000703";
const OCCURRED_AT = "2026-08-10T13:00:00.000Z";

let databaseName = "";
let userId = "";

function servico(
  overrides: Partial<ServiceCatalogLocalRecord> = {},
): ServiceCatalogLocalRecord {
  return {
    id: SERVICO_ID,
    code: "FREASGEM",
    name: "Freasgem",
    description: null,
    status: "ACTIVE",
    syncStatus: "SYNCED",
    createdAt: OCCURRED_AT,
    updatedAt: OCCURRED_AT,
    lastError: null,
    ...overrides,
  };
}

function preco(
  overrides: Partial<ServicePriceVersionLocalRecord> = {},
): ServicePriceVersionLocalRecord {
  return {
    id: PRECO_ID,
    obraId: OBRA_ID,
    serviceId: SERVICO_ID,
    unit: "M²",
    currency: "BRL",
    version: 1,
    unitPrice: "5000.0000",
    contractedQuantity: "1200.000",
    validFrom: "2026-08-07",
    validTo: null,
    source: "CONTRATO_MEDIDO",
    supersedesId: null,
    status: "ACTIVE",
    effectiveValidTo: null,
    entityVersion: 1,
    syncStatus: "SYNCED",
    createdAt: OCCURRED_AT,
    updatedAt: OCCURRED_AT,
    lastError: null,
    ...overrides,
  };
}

async function semear(
  registro: ServiceCatalogLocalRecord = servico(),
  versao: ServicePriceVersionLocalRecord = preco(),
): Promise<void> {
  const database = await getCortexDb();
  await database.put("service_catalog", registro);
  await database.put("service_price_versions", versao);
}

beforeEach(async () => {
  userId = crypto.randomUUID();
  setSession({
    colaboradorId: userId,
    nome: "Alfa",
    papelAcesso: "ALFA",
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

describe("serviço corrigido no cadastro", () => {
  it("reescreve nome, código e descrição mantendo o identificador", async () => {
    await semear();

    const resultado = await queueUpdateService(OBRA_ID, SERVICO_ID, {
      code: "fresagem",
      name: "Fresagem",
      description: "Fresagem de revestimento asfáltico",
    });

    expect(resultado.entityId).toBe(SERVICO_ID);
    const database = await getCortexDb();
    expect(await database.get("service_catalog", SERVICO_ID)).toMatchObject({
      id: SERVICO_ID,
      code: "FRESAGEM",
      name: "Fresagem",
      description: "Fresagem de revestimento asfáltico",
      syncStatus: "PENDING_SYNC",
    });
    const fila = await database.getAllFromIndex(
      "outbox_mutations",
      "by-entity-id",
      SERVICO_ID,
    );
    expect(fila.map((item) => item.operacao)).toContain(
      "ATUALIZAR_SERVICO_CATALOGO",
    );
  });

  /*
   * O preço que o serviço já tinha não é tocado: corrigir o nome não é
   * republicar o que ele custa.
   */
  it("deixa intacta a versão de preço do serviço", async () => {
    await semear();

    await queueUpdateService(OBRA_ID, SERVICO_ID, {
      code: "FRESAGEM",
      name: "Fresagem",
    });

    const database = await getCortexDb();
    expect(await database.get("service_price_versions", PRECO_ID)).toMatchObject({
      status: "ACTIVE",
      unitPrice: "5000.0000",
      syncStatus: "SYNCED",
    });
  });

  /*
   * Abrir a correção, olhar e fechar sem trocar nada não deveria gerar revisão
   * de catálogo nenhuma — nem uma linha na fila.
   */
  it("não enfileira nada quando o texto continua o mesmo", async () => {
    await semear();

    await queueUpdateService(OBRA_ID, SERVICO_ID, {
      code: "FREASGEM",
      name: "Freasgem",
      description: "",
    });

    const database = await getCortexDb();
    expect(
      await database.getAllFromIndex(
        "outbox_mutations",
        "by-entity-id",
        SERVICO_ID,
      ),
    ).toEqual([]);
  });

  it("recusa o serviço que este aparelho não conhece", async () => {
    await expect(
      queueUpdateService(OBRA_ID, SERVICO_ID, {
        code: "FRESAGEM",
        name: "Fresagem",
      }),
    ).rejects.toThrow(/não encontrado/i);
  });
});

describe("preço corrigido no lugar", () => {
  it("reescreve a versão sem criar outra", async () => {
    await semear();

    await queueUpdatePrice(OBRA_ID, PRECO_ID, {
      unit: "M²",
      unitPrice: "50,00",
      contractedQuantity: "1.200",
      validFrom: "2026-08-07",
      validTo: "",
      source: "CONTRATO_MEDIDO",
    });

    const database = await getCortexDb();
    const versoes = await database.getAllFromIndex(
      "service_price_versions",
      "by-worksite-service",
      [OBRA_ID, SERVICO_ID],
    );
    expect(versoes).toHaveLength(1);
    expect(versoes[0]).toMatchObject({
      id: PRECO_ID,
      version: 1,
      unitPrice: "50.00",
      contractedQuantity: "1200.000",
      supersedesId: null,
      syncStatus: "PENDING_SYNC",
    });
    const fila = await database.getAllFromIndex(
      "outbox_mutations",
      "by-entity-id",
      PRECO_ID,
    );
    expect(fila.map((item) => item.operacao)).toContain(
      "ATUALIZAR_PRECO_SERVICO",
    );
  });

  /* A moeda é BRL e continua fora da correção; a ausência de quantidade
     contratada continua sendo ausência, não um zero inventado. */
  it("preserva a moeda e aceita ficar sem quantidade contratada", async () => {
    await semear();

    await queueUpdatePrice(OBRA_ID, PRECO_ID, {
      unit: "M²",
      unitPrice: "50,00",
      contractedQuantity: "",
      validFrom: "2026-08-07",
      validTo: null,
      source: "CONTRATO_MEDIDO",
    });

    const database = await getCortexDb();
    expect(await database.get("service_price_versions", PRECO_ID)).toMatchObject({
      unit: "M²",
      currency: "BRL",
      contractedQuantity: null,
    });
  });

  /*
   * O conserto que motivou tudo isto: antes de o cadastro aceitar expoente,
   * todo serviço medido em área entrava como metro linear, porque "M" era o
   * único que passava. A unidade muda na própria versão, sem criar outra.
   */
  it("corrige a unidade que entrou como metro linear", async () => {
    await semear(servico(), preco({ unit: "M" }));

    await queueUpdatePrice(OBRA_ID, PRECO_ID, {
      unit: "m2",
      unitPrice: "50,00",
      contractedQuantity: "1200",
      validFrom: "2026-08-07",
      validTo: null,
      source: "CONTRATO_MEDIDO",
    });

    const database = await getCortexDb();
    const versoes = await database.getAllFromIndex(
      "service_price_versions",
      "by-worksite-service",
      [OBRA_ID, SERVICO_ID],
    );
    expect(versoes).toHaveLength(1);
    expect(versoes[0]).toMatchObject({
      id: PRECO_ID,
      unit: "M²",
      currency: "BRL",
      syncStatus: "PENDING_SYNC",
    });
  });

  /*
   * A correção é para o erro de digitação, não para o histórico. Uma versão já
   * encerrada tem sucessor ou cancelamento apontando para ela, e reescrevê-la
   * deslocaria a vigência de quem veio depois.
   */
  it("recusa a versão que já foi substituída ou cancelada", async () => {
    await semear(servico(), preco({ status: "SUPERSEDED" }));

    await expect(
      queueUpdatePrice(OBRA_ID, PRECO_ID, {
        unit: "M²",
        unitPrice: "50,00",
        contractedQuantity: "",
        validFrom: "2026-08-07",
        validTo: null,
        source: "CONTRATO_MEDIDO",
      }),
    ).rejects.toThrow(/substituída ou cancelada/i);
  });

  it("recusa a versão de preço de outra obra", async () => {
    await semear(
      servico(),
      preco({ obraId: "00000000-0000-4000-8000-0000000007ff" }),
    );

    await expect(
      queueUpdatePrice(OBRA_ID, PRECO_ID, {
        unit: "M²",
        unitPrice: "50,00",
        contractedQuantity: "",
        validFrom: "2026-08-07",
        validTo: null,
        source: "CONTRATO_MEDIDO",
      }),
    ).rejects.toThrow(/não pertence a esta obra/i);
  });

  /*
   * O erro costuma ser notado antes da rede voltar. A correção de um preço que
   * ainda está na fila precisa sair atrás da criação dele: corrigir o que o
   * servidor não conhece não tem o que encontrar do outro lado.
   */
  it("espera a criação pendente do próprio preço", async () => {
    await semear();
    const criado = await queueCreatePrice(OBRA_ID, SERVICO_ID, {
      unit: "M²",
      currency: "BRL",
      unitPrice: "5000,00",
      contractedQuantity: "1200",
      validFrom: "2027-01-04",
      validTo: null,
      source: "CONTRATO_MEDIDO",
    });

    await queueUpdatePrice(OBRA_ID, criado.entityId, {
      unit: "M²",
      unitPrice: "50,00",
      contractedQuantity: "1200",
      validFrom: "2027-01-04",
      validTo: null,
      source: "CONTRATO_MEDIDO",
    });

    const fila = await (await getCortexDb()).getAllFromIndex(
      "outbox_mutations",
      "by-entity-id",
      criado.entityId,
    );
    const correcao = fila.find(
      (item) => item.operacao === "ATUALIZAR_PRECO_SERVICO",
    );
    expect(correcao?.dependsOnMutationIds).toEqual([criado.clientMutationId]);
  });
});
