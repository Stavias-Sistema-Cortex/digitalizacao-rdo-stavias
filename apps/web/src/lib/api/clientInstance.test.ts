import { describe, expect, it } from "vitest";

import {
  createClientInstanceController,
  releaseClientInstanceForPageHide,
} from "./clientInstance";

const CLIENT_INSTANCE_STORAGE_KEY = "cortex.auth.client-instance.v1";

type MemoryStorage = {
  storage: Storage;
  values: Map<string, string>;
};

function createMemoryStorage(): MemoryStorage {
  const values = new Map<string, string>();
  const storage: Storage = {
    get length() {
      return values.size;
    },
    clear: () => values.clear(),
    getItem: (key) => values.get(key) ?? null,
    key: (index) => Array.from(values.keys())[index] ?? null,
    removeItem: (key) => values.delete(key),
    setItem: (key, value) => values.set(key, value),
  };
  return { storage, values };
}

class FakeLockManager {
  private readonly held = new Set<string>();

  request(
    name: string,
    options: { ifAvailable?: boolean },
    callback: (lock: Lock | null) => Promise<void> | void,
  ): Promise<void> {
    if (options.ifAvailable && this.held.has(name)) {
      return Promise.resolve(callback(null));
    }
    this.held.add(name);
    return Promise.resolve(callback({ name, mode: "exclusive" } as Lock))
      .finally(() => this.held.delete(name));
  }
}

class RejectingLockManager {
  request(): Promise<void> {
    return Promise.reject(new Error("Web Locks indisponível"));
  }
}

function runtime(
  tab: MemoryStorage,
  locks: LockManager | null,
  options: { openerPresent?: boolean; byte?: number } = {},
) {
  return {
    sessionStorage: tab.storage,
    locks,
    openerPresent: options.openerPresent ?? false,
    randomValues: (bytes: Uint8Array) => {
      bytes.fill(options.byte ?? 7);
      return bytes;
    },
  };
}

describe("client instance lease", () => {
  it("rotates cloned sessionStorage before a second document can use the original proof", async () => {
    const locks = new FakeLockManager() as unknown as LockManager;
    const sourceTab = createMemoryStorage();
    const source = createClientInstanceController(
      runtime(sourceTab, locks, { byte: 7 }),
    );
    const sourceLease = await source.acquire();
    expect(sourceLease).not.toBeNull();

    const clonedTab = createMemoryStorage();
    clonedTab.values.set(
      CLIENT_INSTANCE_STORAGE_KEY,
      sourceLease!.value,
    );
    const clone = createClientInstanceController(
      runtime(clonedTab, locks, { byte: 8 }),
    );
    const cloneLease = await clone.acquire();

    expect(cloneLease).toMatchObject({
      requiresFreshAuthentication: true,
    });
    expect(cloneLease?.value).not.toBe(sourceLease?.value);
    expect(clonedTab.values.get(CLIENT_INSTANCE_STORAGE_KEY)).toBe(
      cloneLease?.value,
    );

    await source.dispose();
    await clone.dispose();
  });

  it("resolves a simultaneous cloned-tab race to two distinct proofs before either caller can fetch", async () => {
    const seedLocks = new FakeLockManager() as unknown as LockManager;
    const seedStorage = createMemoryStorage();
    const seed = createClientInstanceController(
      runtime(seedStorage, seedLocks, { byte: 6 }),
    );
    const seededLease = await seed.acquire();
    await seed.dispose();

    const firstTab = createMemoryStorage();
    const secondTab = createMemoryStorage();
    firstTab.values.set(CLIENT_INSTANCE_STORAGE_KEY, seededLease!.value);
    secondTab.values.set(CLIENT_INSTANCE_STORAGE_KEY, seededLease!.value);
    const sharedLocks = new FakeLockManager() as unknown as LockManager;
    const first = createClientInstanceController(
      runtime(firstTab, sharedLocks, { byte: 7 }),
    );
    const second = createClientInstanceController(
      runtime(secondTab, sharedLocks, { byte: 8 }),
    );

    const [firstLease, secondLease] = await Promise.all([
      first.acquire(),
      second.acquire(),
    ]);

    expect(new Set([firstLease?.value, secondLease?.value]).size).toBe(2);
    expect(
      [firstLease, secondLease].filter(
        (lease) => lease?.requiresFreshAuthentication,
      ),
    ).toHaveLength(1);

    await first.dispose();
    await second.dispose();
  });

  it("keeps a stored proof only after the prior document released its lease", async () => {
    const locks = new FakeLockManager() as unknown as LockManager;
    const tab = createMemoryStorage();
    const first = createClientInstanceController(runtime(tab, locks));
    const firstLease = await first.acquire();

    await first.dispose();

    const reloaded = createClientInstanceController(runtime(tab, locks));
    const reloadLease = await reloaded.acquire();

    expect(reloadLease).toMatchObject({
      value: firstLease?.value,
      requiresFreshAuthentication: false,
    });

    await reloaded.dispose();
  });

  it("requires a fresh authentication until the newly minted proof is accepted", async () => {
    const locks = new FakeLockManager() as unknown as LockManager;
    const tab = createMemoryStorage();
    const controller = createClientInstanceController(runtime(tab, locks));

    const lease = await controller.acquire();

    expect(lease).toMatchObject({ requiresFreshAuthentication: true });
    expect(controller.confirmFreshAuthentication(lease!.value)).toBe(true);
    await expect(controller.acquire()).resolves.toMatchObject({
      value: lease?.value,
      requiresFreshAuthentication: false,
    });

    await controller.dispose();
  });

  it("preserves the same-tab proof when a non-bfcache pagehide releases its lock", async () => {
    const locks = new FakeLockManager() as unknown as LockManager;
    const tab = createMemoryStorage();
    const first = createClientInstanceController(runtime(tab, locks));
    const firstLease = await first.acquire();

    await releaseClientInstanceForPageHide(first, false);

    expect(tab.values.get(CLIENT_INSTANCE_STORAGE_KEY)).toBe(
      firstLease?.value,
    );
    const reloaded = createClientInstanceController(runtime(tab, locks));
    await expect(reloaded.acquire()).resolves.toMatchObject({
      value: firstLease?.value,
      requiresFreshAuthentication: false,
    });

    await reloaded.dispose();
  });

  it("keeps a bfcache document lease so a copied proof is still rotated", async () => {
    const locks = new FakeLockManager() as unknown as LockManager;
    const sourceTab = createMemoryStorage();
    const source = createClientInstanceController(
      runtime(sourceTab, locks, { byte: 2 }),
    );
    const sourceLease = await source.acquire();

    await releaseClientInstanceForPageHide(source, true);

    const cloneTab = createMemoryStorage();
    cloneTab.values.set(CLIENT_INSTANCE_STORAGE_KEY, sourceLease!.value);
    const clone = createClientInstanceController(
      runtime(cloneTab, locks, { byte: 3 }),
    );
    await expect(clone.acquire()).resolves.toMatchObject({
      requiresFreshAuthentication: true,
    });

    await source.dispose();
    await clone.dispose();
  });

  it("uses a memory-only proof and requires fresh authentication without Web Locks", async () => {
    const tab = createMemoryStorage();
    const initial = createClientInstanceController(
      runtime(tab, new FakeLockManager() as unknown as LockManager, {
        byte: 4,
      }),
    );
    const persisted = await initial.acquire();
    await initial.dispose();

    const unsupported = createClientInstanceController(
      runtime(tab, null, { byte: 5 }),
    );
    const fallback = await unsupported.acquire();

    expect(fallback).toMatchObject({ requiresFreshAuthentication: true });
    expect(fallback?.value).not.toBe(persisted?.value);
    expect(tab.values.get(CLIENT_INSTANCE_STORAGE_KEY)).toBeUndefined();
  });

  it("fails closed instead of hanging when Web Locks rejects a lease request", async () => {
    const controller = createClientInstanceController(runtime(
      createMemoryStorage(),
      new RejectingLockManager() as unknown as LockManager,
    ));

    const result = await Promise.race([
      controller.acquire(),
      new Promise<"timed out">((resolve) => {
        setTimeout(() => resolve("timed out"), 25);
      }),
    ]);

    expect(result).toBeNull();
  });

  it("rotates an opener-derived stored proof before attempting a lease", async () => {
    const locks = new FakeLockManager() as unknown as LockManager;
    const tab = createMemoryStorage();
    const first = createClientInstanceController(runtime(tab, locks, { byte: 2 }));
    const persisted = await first.acquire();
    await first.dispose();

    const opened = createClientInstanceController(runtime(tab, locks, {
      openerPresent: true,
      byte: 3,
    }));
    const openedLease = await opened.acquire();

    expect(openedLease).toMatchObject({ requiresFreshAuthentication: true });
    expect(openedLease?.value).not.toBe(persisted?.value);

    await opened.dispose();
  });
});
