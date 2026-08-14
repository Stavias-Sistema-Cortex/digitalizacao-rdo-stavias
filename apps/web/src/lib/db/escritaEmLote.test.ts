import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { clearSession, setSession } from "../../features/auth/authSession";
import { closeCortexDb, getCortexDb } from "./cortexDb";
import type {
  ObraLocalRecord,
  PrevisaoSnapshotRecord,
} from "./db.types";
import { databaseNameForScope } from "./localDataNamespace";
import { listObrasLocais, mergeObrasLocais } from "./obraLocalRepository";
import {
  listSnapshotsByObra,
  putPrevisaoSnapshots,
} from "./previsaoSnapshotRepository";
import { captureOnlineSyncSession } from "../sync/syncSession";

const OBRA_ID = "00000000-0000-4000-8000-000000000a01";
const NOW = "2026-08-14T01:00:00.000Z";

let databaseName = "";
let userId = "";

function obra(indice: number): ObraLocalRecord {
  return {
    id: `obra-${String(indice).padStart(3, "0")}`,
    codigoContrato: `CT-${indice}`,
    nome: `Trecho ${indice}`,
    cliente: null,
    cidade: null,
    uf: "MS",
    rodovia: "BR-262",
    status: "ATIVA",
    observacoes: null,
    latitude: null,
    longitude: null,
    valorContratual: null,
    arquivadoEm: null,
    versaoEntidade: indice,
    updatedAt: NOW,
  };
}

function snapshot(indice: number): PrevisaoSnapshotRecord {
  const dia = String((indice % 28) + 1).padStart(2, "0");
  return {
    id: `snap-${String(indice).padStart(3, "0")}`,
    obraId: OBRA_ID,
    dataReferencia: `2026-06-${dia}`,
    statusExecucao: "CALCULADO",
    producaoPlanejada: 100,
    producaoRealizada: 80,
    producaoApontada: 90,
    custoRealizado: null,
    custoPrevistoFinal: null,
    receitaPrevistaFinal: 1000,
    updatedAt: NOW,
  };
}

/**
 * Conta transações abertas sobre um repositório, sem tocar no código medido.
 *
 * <p>Envolve `IDBDatabase.prototype.transaction`, que é por onde toda escrita
 * passa — inclusive a que o `idb` embrulha. Devolve a contagem e a forma de
 * desfazer.
 */
function contarTransacoes(store: string): {
  total: () => number;
  restaurar: () => void;
} {
  const original = IDBDatabase.prototype.transaction;
  let total = 0;
  IDBDatabase.prototype.transaction = function patched(
    this: IDBDatabase,
    ...args: Parameters<IDBDatabase["transaction"]>
  ) {
    const nomes = args[0];
    const alvo = typeof nomes === "string" ? [nomes] : Array.from(nomes);
    if (alvo.includes(store)) total += 1;
    return original.apply(this, args);
  } as IDBDatabase["transaction"];
  return {
    total: () => total,
    restaurar: () => {
      IDBDatabase.prototype.transaction = original;
    },
  };
}

beforeEach(async () => {
  vi.stubGlobal("window", new EventTarget());
  userId = crypto.randomUUID();
  setSession({
    colaboradorId: userId,
    nome: "Encarregada de campo",
    papelAcesso: "BETA",
    escopoGlobal: false,
    obraIds: [OBRA_ID],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  });
  databaseName = await databaseNameForScope(userId, `BETA:${OBRA_ID}`);
  await getCortexDb();
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) await deleteDB(databaseName);
  clearSession();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

/**
 * O custo de abrir a aba de Obras não pode crescer com o tamanho da carteira.
 *
 * <p>A hidratação gravava obra a obra, cada uma na própria transação, e o caro
 * não é ler nem escrever — é esperar o commit antes de a próxima começar. Com
 * quarenta obras eram quarenta esperas enfileiradas, e cada transação guardada
 * ainda registrava e removia um ouvinte de `window`.
 *
 * <p>O teste conta transações em vez de medir tempo, porque tempo em máquina de
 * integração contínua é ruidoso e a propriedade que interessa é estrutural: uma
 * remessa, uma transação. Se alguém voltar a gravar dentro de um laço, isto cai
 * — que é o ponto.
 */
describe("hidratação grava em lote, não registro a registro", () => {
  it("funde quarenta obras numa transação só", async () => {
    const registros = Array.from({ length: 40 }, (_, i) => obra(i));
    const contador = contarTransacoes("obras");

    try {
      await mergeObrasLocais(registros, captureOnlineSyncSession());
      expect(contador.total()).toBe(1);
    } finally {
      contador.restaurar();
    }

    const gravadas = await listObrasLocais();
    expect(gravadas).toHaveLength(40);
  });

  it("grava um histórico de cem snapshots numa transação só", async () => {
    const registros = Array.from({ length: 100 }, (_, i) => snapshot(i));
    const contador = contarTransacoes("previsao_snapshots");

    try {
      await putPrevisaoSnapshots(registros, captureOnlineSyncSession());
      expect(contador.total()).toBe(1);
    } finally {
      contador.restaurar();
    }

    expect(await listSnapshotsByObra(OBRA_ID)).toHaveLength(100);
  });

  /*
   * Remessa vazia não abre transação nenhuma. Sem isso, um recorte sem obra
   * arquivada pagaria uma transação para não gravar nada — e a Lixeira vazia é
   * o caso comum, não a exceção.
   */
  it("não abre transação para remessa vazia", async () => {
    const contador = contarTransacoes("obras");
    try {
      await mergeObrasLocais([], captureOnlineSyncSession());
      expect(contador.total()).toBe(0);
    } finally {
      contador.restaurar();
    }
  });

  /*
   * As duas guardas da fusão precisam sobreviver à mudança de forma, e elas são
   * o motivo de a escrita ser fusão e não sobrescrita: uma remessa do servidor
   * não pode fazer o aparelho regredir de versão nem apagar o que a pessoa
   * editou e ainda não subiu. Ler dentro da transação, e não antes dela, é o
   * que mantém isso valendo — por isso o lote lê registro a registro em vez de
   * carregar tudo de uma vez e gravar por cima.
   */
  it("não deixa versão antiga da remessa sobrescrever a gravada", async () => {
    const guard = captureOnlineSyncSession();
    await mergeObrasLocais(
      [{ ...obra(1), nome: "Versão nova", versaoEntidade: 9 }],
      guard,
    );
    await mergeObrasLocais(
      [{ ...obra(1), nome: "Versão velha", versaoEntidade: 4 }],
      guard,
    );

    const [gravada] = await listObrasLocais();
    expect(gravada.nome).toBe("Versão nova");
    expect(gravada.versaoEntidade).toBe(9);
  });

  it("preserva a obra editada localmente e ainda não sincronizada", async () => {
    const database = await getCortexDb();
    await database.put("obras", {
      ...obra(1),
      nome: "Nome que a pessoa digitou",
      syncStatus: "PENDING_SYNC",
    });

    await mergeObrasLocais(
      [{ ...obra(1), nome: "Nome do servidor", versaoEntidade: 99 }],
      captureOnlineSyncSession(),
    );

    const [gravada] = await listObrasLocais();
    expect(gravada.nome).toBe("Nome que a pessoa digitou");
    expect(gravada.syncStatus).toBe("PENDING_SYNC");
  });
});
