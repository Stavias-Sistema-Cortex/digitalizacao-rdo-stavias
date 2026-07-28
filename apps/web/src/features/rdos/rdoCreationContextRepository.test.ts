import "fake-indexeddb/auto";

import { deleteDB, openDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { clearSession, setSession } from "../auth/authSession";
import {
  closeCortexDb,
  CORTEX_DATABASE_VERSION,
  getCortexDb,
} from "../../lib/db/cortexDb";
import { databaseNameForScope } from "../../lib/db/localDataNamespace";
import {
  RDO_CONTEXT_OFFLINE_MISSING,
} from "./rdoCreationContext";
import {
  getCachedRdoCreationContext,
  listCachedAuthorizedRdoWorksites,
  putRdoCreationContext,
  replaceCachedAuthorizedRdoWorksites,
  refreshAuthorizedRdoWorksites,
  requireRdoDraftCreationContext,
  requireRdoCreationContext,
} from "./rdoCreationContextRepository";
import type {
  RdoCreationContextLookup,
  RdoLocalPendingCreationContextLookup,
} from "./rdoLookupApi";
import * as rdoLookupApi from "./rdoLookupApi";

const WORKSITE_A = "00000000-0000-4000-8000-000000000001";
const WORKSITE_B = "00000000-0000-4000-8000-000000000002";
const WORKSITE_C = "00000000-0000-4000-8000-000000000003";
const WORKSITE_D = "00000000-0000-4000-8000-000000000004";
let ownerId = "";
const databaseNames = new Set<string>();

function session(userId: string, obraIds = [WORKSITE_A]) {
  return {
    colaboradorId: userId,
    nome: "Encarregado",
    papelAcesso: "BETA" as const,
    escopoGlobal: false,
    obraIds,
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  };
}

function complete(total = 0) {
  return { status: "COMPLETE", total, returned: total, complete: true };
}

function fixture(): RdoCreationContextLookup {
  return {
    obra: {
      id: WORKSITE_A,
      codigoContrato: "CTR-A",
      codigoCw: "CW-A",
      nome: "Obra autorizada",
      cliente: null,
      cidade: "Campinas",
      uf: "SP",
      rodovia: null,
      status: "ATIVA",
      version: 3,
    },
    data: "2026-07-22",
    nextNumberSuggestion: "RDO-0012",
    previousRdo: null,
    previousWorkforce: [],
    programacoes: [],
    colaboradores: [],
    equipamentos: [],
    serviceCatalog: [],
    coverage: {
      previousWorkforce: complete(),
      programacoes: complete(),
      colaboradores: complete(),
      equipamentos: complete(),
      serviceCatalog: {
        status: "NOT_CONFIGURED",
        total: 0,
        returned: 0,
        complete: false,
      },
      priceCatalog: {
        status: "NOT_CONFIGURED",
        total: 0,
        returned: 0,
        complete: false,
      },
    },
    freshness: {
      status: "FRESH",
      sourceVersion: 8,
      generatedAt: "2026-07-22T12:00:00.000Z",
      staleAfter: "2026-07-22T12:15:00.000Z",
    },
    provenance: {
      receiptVersion: 4,
      sourceVersion: 8,
      worksiteId: WORKSITE_A,
      selectedDate: "2026-07-22",
      previousRdoId: null,
      generatedAt: "2026-07-22T12:00:00.000Z",
    },
  };
}

function localPendingFixture(): RdoLocalPendingCreationContextLookup {
  return {
    obra: {
      id: WORKSITE_A,
      codigoContrato: "CTR-A",
      codigoCw: "CW-A",
      nome: "Obra autorizada",
      cliente: null,
      cidade: "Campinas",
      uf: "SP",
      rodovia: null,
      status: "ATIVA",
    },
    data: "2026-07-22",
    previousRdo: null,
    previousWorkforce: [],
    programacoes: [],
    colaboradores: [],
    equipamentos: [],
  };
}

beforeEach(async () => {
  ownerId = crypto.randomUUID();
  setSession(session(ownerId));
  databaseNames.add(
    await databaseNameForScope(ownerId, `BETA:${WORKSITE_A}`),
  );
});

afterEach(async () => {
  await closeCortexDb();
  clearSession();
  for (const name of databaseNames) await deleteDB(name);
  databaseNames.clear();
});

describe("IndexedDB v21 para criação de RDO", () => {
  it("preserva outbox e Memória ao migrar v15 e cria o cache de contexto", async () => {
    const name = await databaseNameForScope(ownerId, `BETA:${WORKSITE_A}`);
    const legacy = await openDB(name, 15, {
      upgrade(database) {
        const outbox = database.createObjectStore("outbox_mutations", {
          keyPath: "clientMutationId",
        });
        outbox.createIndex("by-status", "status");
        outbox.createIndex("by-created-at", "criadaNoClienteEm");
        outbox.createIndex("by-entity-id", "entidadeId");
        outbox.createIndex("by-next-attempt-at", "nextAttemptAt");
        const memory = database.createObjectStore("memory_search_documents", {
          keyPath: "key",
        });
        memory.createIndex("by-user-scope", ["userId", "scopeHash"]);
        memory.createIndex("by-user-scope-commit", [
          "userId",
          "scopeHash",
          "commitSequence",
        ]);
      },
    });
    await legacy.put("outbox_mutations", {
      clientMutationId: "pending-v15",
      status: "PENDING",
      entidadeId: "rdo-v15",
      criadaNoClienteEm: "2026-07-22T10:00:00.000Z",
    });
    await legacy.put("memory_search_documents", {
      key: "memory-v15",
      userId: ownerId,
      scopeHash: "scope",
      commitSequence: 7,
    });
    legacy.close();

    const database = await getCortexDb();

    expect(CORTEX_DATABASE_VERSION).toBe(21);
    expect(database.objectStoreNames.contains("rdo_creation_contexts")).toBe(true);
    expect(await database.get("outbox_mutations", "pending-v15")).toMatchObject({
      status: "PENDING",
    });
    expect(await database.get("memory_search_documents", "memory-v15")).toMatchObject({
      commitSequence: 7,
    });
  });
});

describe("cache autorizado obra-data", () => {
  it("persiste receipt, cobertura e fonte pela chave exata", async () => {
    await putRdoCreationContext(fixture(), "2026-07-22T12:01:00.000Z");

    expect(await getCachedRdoCreationContext(WORKSITE_A, "2026-07-22"))
      .toMatchObject({
        ownerId,
        obraId: WORKSITE_A,
        selectedDate: "2026-07-22",
        sourceVersion: 8,
        cachedAt: "2026-07-22T12:01:00.000Z",
        context: { provenance: { receiptVersion: 4 } },
      });
    expect(await getCachedRdoCreationContext(WORKSITE_A, "2026-07-21"))
      .toBeUndefined();
  });

  it("não deixa outro usuário nem escopo revogado acessar o cache", async () => {
    await putRdoCreationContext(fixture());
    await closeCortexDb();

    const otherUser = crypto.randomUUID();
    setSession(session(otherUser));
    databaseNames.add(
      await databaseNameForScope(otherUser, `BETA:${WORKSITE_A}`),
    );
    expect(await getCachedRdoCreationContext(WORKSITE_A, "2026-07-22"))
      .toBeUndefined();

    await closeCortexDb();
    setSession(session(ownerId, [WORKSITE_B]));
    databaseNames.add(
      await databaseNameForScope(ownerId, `BETA:${WORKSITE_B}`),
    );
    await expect(
      getCachedRdoCreationContext(WORKSITE_A, "2026-07-22"),
    ).rejects.toThrow("Obra fora do escopo da sessão.");
  });

  it("lista somente obras reais do cache autorizadas pela sessão", async () => {
    await replaceCachedAuthorizedRdoWorksites([
      {
        id: WORKSITE_A,
        codigoContrato: "CTR-A",
        nome: "Obra A",
        cliente: null,
        cidade: "Campinas",
        uf: "SP",
        rodovia: null,
        status: "ATIVA",
        observacoes: null,
        latitude: null,
        longitude: null,
        valorContratual: null,
        atualizadoEm: "2026-07-22T10:00:00.000Z",
      },
      {
        id: WORKSITE_B,
        codigoContrato: null,
        nome: "Obra fora do escopo",
        cliente: null,
        cidade: null,
        uf: null,
        rodovia: null,
        status: null,
        observacoes: null,
        latitude: null,
        longitude: null,
        valorContratual: null,
        atualizadoEm: null,
      },
    ]);

    expect(await listCachedAuthorizedRdoWorksites()).toEqual([
      expect.objectContaining({ id: WORKSITE_A, nome: "Obra A" }),
    ]);
  });

  it("preserva lifecycle local ausente do endpoint e só remove obra sincronizada comum", async () => {
    await closeCortexDb();
    setSession(session(ownerId, [
      WORKSITE_A,
      WORKSITE_B,
      WORKSITE_C,
      WORKSITE_D,
    ]));
    databaseNames.add(
      await databaseNameForScope(
        ownerId,
        `BETA:${[
          WORKSITE_A,
          WORKSITE_B,
          WORKSITE_C,
          WORKSITE_D,
        ].sort().join(",")}`,
      ),
    );
    const database = await getCortexDb();
    const common = {
      codigoContrato: "CTR",
      nome: "Obra",
      cliente: null,
      cidade: null,
      uf: null,
      rodovia: null,
      status: "INATIVA",
      observacoes: null,
      latitude: null,
      longitude: null,
      valorContratual: null,
      versaoEntidade: 7,
      ultimoErro: null,
      updatedAt: "2026-07-22T10:00:00.000Z",
    };
    await database.put("obras", {
      ...common,
      id: WORKSITE_A,
      nome: "Arquivada sincronizada",
      arquivadoEm: "2026-07-22T09:00:00.000Z",
      syncStatus: "SYNCED",
    });
    await database.put("obras", {
      ...common,
      id: WORKSITE_B,
      nome: "Arquivamento pendente",
      arquivadoEm: "2026-07-22T09:30:00.000Z",
      syncStatus: "PENDING",
    });
    await database.put("obras", {
      ...common,
      id: WORKSITE_C,
      nome: "Restauração pendente",
      status: "ATIVA",
      arquivadoEm: null,
      syncStatus: "PENDING",
    });
    await database.put("obras", {
      ...common,
      id: WORKSITE_D,
      nome: "Sincronizada comum",
      status: "ATIVA",
      arquivadoEm: null,
      syncStatus: "SYNCED",
    });

    await replaceCachedAuthorizedRdoWorksites([
      {
        id: WORKSITE_A,
        codigoContrato: "CTR-REMOTO",
        nome: "Arquivada sincronizada remota",
        cliente: null,
        cidade: null,
        uf: null,
        rodovia: null,
        status: "INATIVA",
        observacoes: null,
        latitude: null,
        longitude: null,
        valorContratual: null,
        atualizadoEm: "2026-07-22T11:00:00.000Z",
      },
    ]);

    expect(await database.get("obras", WORKSITE_A)).toMatchObject({
      codigoContrato: "CTR-REMOTO",
      nome: "Arquivada sincronizada remota",
      arquivadoEm: "2026-07-22T09:00:00.000Z",
      versaoEntidade: 7,
      syncStatus: "SYNCED",
    });
    expect(await database.get("obras", WORKSITE_B)).toMatchObject({
      arquivadoEm: "2026-07-22T09:30:00.000Z",
      syncStatus: "PENDING",
    });
    expect(await database.get("obras", WORKSITE_C)).toMatchObject({
      arquivadoEm: null,
      syncStatus: "PENDING",
    });
    expect(await database.get("obras", WORKSITE_D)).toBeUndefined();
  });

  it("usa o contexto completo do cache offline e falha com a cópia literal sem fonte", async () => {
    await putRdoCreationContext(fixture());
    await expect(
      requireRdoCreationContext(WORKSITE_A, "2026-07-22", false),
    ).resolves.toMatchObject({ source: "CACHE", context: { data: "2026-07-22" } });
    await expect(
      requireRdoCreationContext(WORKSITE_A, "2026-07-21", false),
    ).rejects.toThrow(RDO_CONTEXT_OFFLINE_MISSING);
  });

  it.each(["LOCAL_PENDING", "SYNCED"] as const)(
    "encadeia o RDO local anterior em estado %s ao criar o seguinte offline",
    async (syncStatus) => {
    await putRdoCreationContext(fixture());
    const database = await getCortexDb();
    await database.put("rdos", {
      id: "00000000-0000-4000-8000-000000000071",
      obraId: WORKSITE_A,
      programacaoId: null,
      numeroRdo: "RDO-LOCAL-1",
      dataRdo: "2026-07-21",
      statusRdo: "RASCUNHO",
      syncStatus,
      versaoEntidade: null,
      payload: {
        maoObra: [
          {
            localId: "00000000-0000-4000-8000-000000000072",
            selected: true,
            colaboradorId: "",
            nomeColaborador: "Maria Servente",
            cargo: "Servente",
            tipoVinculo: "CONTRATADO",
            quantidade: 1,
            horaInicio: "07:00",
            horaFim: "17:00",
            observacoes: "",
          },
        ],
      },
      createdAt: "2026-07-21T18:00:00.000Z",
      updatedAt: "2026-07-21T18:00:00.000Z",
    });

    await expect(
      requireRdoDraftCreationContext(WORKSITE_A, "2026-07-22", false),
    ).resolves.toMatchObject({
      kind: "LOCAL_PENDING",
      source: "LOCAL_CHAIN",
      context: {
        previousRdo: {
          id: "00000000-0000-4000-8000-000000000071",
          numeroRdo: "RDO-LOCAL-1",
          dataRdo: "2026-07-21",
        },
        previousWorkforce: [
          {
            sourceItemId: "00000000-0000-4000-8000-000000000072",
            sourceRdoId: "00000000-0000-4000-8000-000000000071",
            collaboratorId: null,
            nameSnapshot: "Maria Servente",
            availability: "AVAILABLE",
          },
        ],
      },
    });
    },
  );

  it("retorna fallback legado como LOCAL_PENDING sem gravá-lo no cache canônico", async () => {
    const remote = async () => ({
      kind: "LOCAL_PENDING" as const,
      context: localPendingFixture(),
    });

    await expect(
      requireRdoDraftCreationContext(
        WORKSITE_A,
        "2026-07-22",
        true,
        remote,
      ),
    ).resolves.toMatchObject({
      kind: "LOCAL_PENDING",
      source: "LEGACY_SERVER",
      context: {
        obra: { id: WORKSITE_A },
        data: "2026-07-22",
      },
    });

    const database = await getCortexDb();
    expect(await database.getAll("rdo_creation_contexts")).toEqual([]);
  });

  it("não permite que fallback LOCAL_PENDING troque obra ou data da seleção", async () => {
    const mismatches: RdoLocalPendingCreationContextLookup[] = [
      {
        ...localPendingFixture(),
        obra: { ...localPendingFixture().obra, id: WORKSITE_B },
      },
      {
        ...localPendingFixture(),
        data: "2026-07-21",
      },
    ];

    for (const context of mismatches) {
      await expect(
        requireRdoDraftCreationContext(
          WORKSITE_A,
          "2026-07-22",
          true,
          async () => ({ kind: "LOCAL_PENDING", context }),
        ),
      ).rejects.toThrow("Contexto local pendente não corresponde à seleção.");
    }
  });

  it("rejeita resposta remota sem proveniência e não a coloca no cache", async () => {
    const malformed = fixture() as Partial<RdoCreationContextLookup>;
    delete malformed.provenance;

    await expect(
      requireRdoCreationContext(
        WORKSITE_A,
        "2026-07-22",
        true,
        async () => malformed as RdoCreationContextLookup,
      ),
    ).rejects.toThrow(
      "O servidor retornou um contexto de RDO incompatível. Atualize os serviços do Córtex e tente novamente.",
    );

    const database = await getCortexDb();
    expect(await database.getAll("rdo_creation_contexts")).toEqual([]);
  });

  it("rejeita resposta remota com cobertura incompatível e não a coloca no cache", async () => {
    const malformed = fixture();
    malformed.coverage = {} as RdoCreationContextLookup["coverage"];

    await expect(
      requireRdoCreationContext(
        WORKSITE_A,
        "2026-07-22",
        true,
        async () => malformed,
      ),
    ).rejects.toThrow(
      "O servidor retornou um contexto de RDO incompatível. Atualize os serviços do Córtex e tente novamente.",
    );

    const database = await getCortexDb();
    expect(await database.getAll("rdo_creation_contexts")).toEqual([]);
  });

  it("rejeita contexto legado corrompido antes de retorná-lo do cache", async () => {
    await putRdoCreationContext(fixture());
    const database = await getCortexDb();
    const stored = await database.get("rdo_creation_contexts", [
      ownerId,
      WORKSITE_A,
      "2026-07-22",
    ]);
    if (!stored) throw new Error("Cache de contexto esperado não foi encontrado.");
    await database.put("rdo_creation_contexts", {
      ...stored,
      context: { ...stored.context, coverage: {} },
    });

    await expect(
      getCachedRdoCreationContext(WORKSITE_A, "2026-07-22"),
    ).rejects.toThrow(
      "O servidor retornou um contexto de RDO incompatível. Atualize os serviços do Córtex e tente novamente.",
    );
  });

  it("rejeita e não grava obras se a sessão girar durante o refresh remoto", async () => {
    let resolveRemote!: (value: never[]) => void;
    let markStarted!: () => void;
    const started = new Promise<void>((resolve) => {
      markStarted = resolve;
    });
    vi.spyOn(
      rdoLookupApi,
      "buscarObrasAutorizadasParaRdo",
    ).mockImplementation(() => {
      markStarted();
      return new Promise<never[]>((resolve) => {
        resolveRemote = resolve;
      });
    });
    const operation = refreshAuthorizedRdoWorksites();
    await started;

    const secondOwner = crypto.randomUUID();
    databaseNames.add(
      await databaseNameForScope(secondOwner, `BETA:${WORKSITE_A}`),
    );
    setSession(session(secondOwner));
    resolveRemote([]);

    await expect(operation).rejects.toThrow(
      "A sessão mudou durante a leitura do contexto do RDO.",
    );
    expect(await listCachedAuthorizedRdoWorksites()).toEqual([]);
    await closeCortexDb();
    setSession(session(ownerId));
    expect(await listCachedAuthorizedRdoWorksites()).toEqual([]);
  });

  it("rejeita e não atribui contexto ao segundo usuário após rotação da sessão", async () => {
    let resolveRemote!: (value: RdoCreationContextLookup) => void;
    let markStarted!: () => void;
    const started = new Promise<void>((resolve) => {
      markStarted = resolve;
    });
    const remote = vi.fn(() => {
      markStarted();
      return new Promise<RdoCreationContextLookup>((resolve) => {
        resolveRemote = resolve;
      });
    });
    const operation = requireRdoCreationContext(
      WORKSITE_A,
      "2026-07-22",
      true,
      remote,
    );
    await started;

    const secondOwner = crypto.randomUUID();
    databaseNames.add(
      await databaseNameForScope(secondOwner, `BETA:${WORKSITE_A}`),
    );
    setSession(session(secondOwner));
    resolveRemote(fixture());

    await expect(operation).rejects.toThrow(
      "A sessão mudou durante a leitura do contexto do RDO.",
    );
    expect(
      await getCachedRdoCreationContext(WORKSITE_A, "2026-07-22"),
    ).toBeUndefined();
    await closeCortexDb();
    setSession(session(ownerId));
    expect(
      await getCachedRdoCreationContext(WORKSITE_A, "2026-07-22"),
    ).toBeUndefined();
  });
});
