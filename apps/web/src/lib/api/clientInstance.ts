export const CLIENT_INSTANCE_HEADER = "X-Cortex-Client-Instance";

const CLIENT_INSTANCE_STORAGE_KEY = "cortex.auth.client-instance.v1";
const CLIENT_INSTANCE_PATTERN = /^[A-Za-z0-9_-]{43}$/;
const CLIENT_INSTANCE_BYTES = 32;
const CLIENT_INSTANCE_LOCK_PREFIX = "cortex.client-instance:";

export type ClientInstanceLease = {
  value: string;
  /**
   * The proof was rotated because this document cannot safely inherit the
   * browser state of an earlier document. Callers may start a fresh CPF or
   * passkey flow, but a server session tied to the old proof will be denied.
   */
  requiresFreshAuthentication: boolean;
};

type ClientInstanceRuntime = {
  sessionStorage: Storage | null;
  locks: LockManager | null;
  openerPresent: boolean;
  randomValues: (bytes: Uint8Array) => void;
};

type LockClaim = "HELD" | "CONTENDED" | "UNAVAILABLE";

export type ClientInstanceController = {
  acquire: () => Promise<ClientInstanceLease | null>;
  confirmFreshAuthentication: (value: string) => boolean;
  dispose: () => Promise<void>;
};

/**
 * Obtains a proof that is exclusive to this live document before any request
 * can carry it. `sessionStorage` is normally scoped by tab, but a related or
 * duplicated tab may begin with a copied value. A document-lifetime Web Lock
 * makes that copy unusable: the second document rotates to a fresh proof
 * before it can attach a header to a request.
 */
export function createClientInstanceController(
  runtime: ClientInstanceRuntime,
): ClientInstanceController {
  let acquisition: Promise<ClientInstanceLease | null> | null = null;
  let acquiredLease: ClientInstanceLease | null = null;
  let releaseHeldLock: (() => void) | null = null;
  let heldLockRequest: Promise<unknown> | null = null;

  async function acquire(): Promise<ClientInstanceLease | null> {
    if (acquisition === null) {
      acquisition = acquireLease().then((lease) => {
        acquiredLease = lease;
        return lease;
      });
    }
    return acquisition;
  }

  function confirmFreshAuthentication(value: string): boolean {
    if (acquiredLease === null || acquiredLease.value !== value) {
      return false;
    }
    if (!acquiredLease.requiresFreshAuthentication) {
      return true;
    }
    acquiredLease = { ...acquiredLease, requiresFreshAuthentication: false };
    acquisition = Promise.resolve(acquiredLease);
    return true;
  }

  async function acquireLease(): Promise<ClientInstanceLease | null> {
    const storage = runtime.sessionStorage;
    const storedValue = readStored(storage);
    const stored = runtime.openerPresent ? null : storedValue;

    // Without origin-wide exclusive locking, a copied sessionStorage value
    // cannot prove which top-level document owns it. Keep the proof only in
    // this JavaScript realm and deliberately require a fresh online session
    // after every document load.
    if (runtime.locks === null) {
      removeStored(storage);
      const value = generateClientInstance(storedValue);
      return value === null
        ? null
        : { value, requiresFreshAuthentication: true };
    }

    let candidate = runtime.openerPresent
      ? generateClientInstance(storedValue)
      : stored ?? generateClientInstance(null);
    if (candidate === null) {
      return null;
    }

    let requiresFreshAuthentication = runtime.openerPresent || stored === null;
    let claim = await claimExclusive(candidate);
    if (claim === "UNAVAILABLE") {
      removeStored(storage);
      const value = generateClientInstance(candidate);
      return value === null
        ? null
        : { value, requiresFreshAuthentication: true };
    }

    // A clone cannot use the copied proof. Rotate and claim a new one before
    // returning it to a fetch caller; bounded retries only cover an extremely
    // unlikely random collision with another live document.
    for (let attempt = 0; claim === "CONTENDED" && attempt < 3; attempt += 1) {
      candidate = generateClientInstance(candidate);
      if (candidate === null) {
        return null;
      }
      requiresFreshAuthentication = true;
      claim = await claimExclusive(candidate);
    }
    if (claim !== "HELD") {
      removeStored(storage);
      return null;
    }

    if (!writeStored(storage, candidate)) {
      // The lock still prevents a live clone from reusing this proof, but the
      // next document must not assume it can restore the server session.
      requiresFreshAuthentication = true;
    }
    return { value: candidate, requiresFreshAuthentication };
  }

  async function claimExclusive(value: string): Promise<LockClaim> {
    const manager = runtime.locks;
    if (manager === null) {
      return "UNAVAILABLE";
    }

    let resolveClaim: (result: LockClaim) => void = () => undefined;
    const claimed = new Promise<LockClaim>((resolve) => {
      resolveClaim = resolve;
    });
    let release: (() => void) | null = null;
    try {
      const request = manager.request(
        `${CLIENT_INSTANCE_LOCK_PREFIX}${value}`,
        { ifAvailable: true, mode: "exclusive" },
        async (lock) => {
          if (lock === null) {
            resolveClaim("CONTENDED");
            return;
          }
          release = awaitDocumentRelease();
          resolveClaim("HELD");
          await documentReleasePromise;
        },
      );
      heldLockRequest = Promise.resolve(request).catch(() => {
        // A rejected Web Locks request never invokes the callback. Resolve the
        // pending claim explicitly so credentialed fetches fail closed instead
        // of waiting forever before their own network timeout is installed.
        resolveClaim("UNAVAILABLE");
      });
    } catch {
      return "UNAVAILABLE";
    }

    const result = await claimed;
    if (result === "HELD" && release !== null) {
      releaseHeldLock = release;
    }
    return result;
  }

  let resolveDocumentRelease: (() => void) | null = null;
  let documentReleasePromise: Promise<void> = new Promise((resolve) => {
    resolveDocumentRelease = resolve;
  });

  function awaitDocumentRelease(): () => void {
    return () => {
      const resolve = resolveDocumentRelease;
      resolveDocumentRelease = null;
      if (resolve) resolve();
    };
  }

  async function dispose(): Promise<void> {
    const release = releaseHeldLock;
    releaseHeldLock = null;
    if (release) release();
    const request = heldLockRequest;
    heldLockRequest = null;
    if (request) await request;
    documentReleasePromise = Promise.resolve();
  }

  function generateClientInstance(exclude: string | null): string | null {
    const bytes = new Uint8Array(CLIENT_INSTANCE_BYTES);
    try {
      runtime.randomValues(bytes);
      const value = encodeBase64Url(bytes);
      if (!value || !isCanonicalClientInstance(value) || value === exclude) {
        return null;
      }
      return value;
    } catch {
      return null;
    } finally {
      bytes.fill(0);
    }
  }

  return { acquire, confirmFreshAuthentication, dispose };
}

let browserController: ClientInstanceController | null = null;
let lifecycleController: ClientInstanceController | null = null;

/**
 * Returns an exclusive proof for the current document. Callers must await it
 * before issuing any credentialed or fresh-authentication fetch.
 */
export async function getOrCreateClientInstance(): Promise<
  ClientInstanceLease | null
> {
  const controller = browserController ?? createBrowserController();
  browserController = controller;
  registerLifecycleRelease(controller);
  return controller.acquire();
}

/**
 * A session-creating CPF, passkey or OTP response has bound the active cookie
 * to this document's exclusive proof. Until this acknowledgement, ordinary
 * cookie-bearing requests are rejected locally rather than sent with a proof
 * that may have come from a copied tab.
 */
export function markClientInstanceAuthenticated(value: string): boolean {
  return browserController?.confirmFreshAuthentication(value) ?? false;
}

function createBrowserController(): ClientInstanceController {
  return createClientInstanceController({
    sessionStorage: currentTabStorage(),
    locks: currentLockManager(),
    openerPresent: hasOpener(),
    randomValues: (bytes) => {
      const crypto = globalThis.crypto;
      if (!crypto || typeof crypto.getRandomValues !== "function") {
        throw new Error("Criptografia indisponível.");
      }
      crypto.getRandomValues(bytes as Uint8Array<ArrayBuffer>);
    },
  });
}

function registerLifecycleRelease(controller: ClientInstanceController): void {
  if (
    lifecycleController === controller ||
    typeof window === "undefined" ||
    typeof window.addEventListener !== "function"
  ) {
    return;
  }
  lifecycleController = controller;
  const release = (event: PageTransitionEvent) => {
    if (event.persisted) {
      // A document retained in bfcache must retain its exclusive lock. A
      // copied tab must not acquire this proof while the original can return.
      lifecycleController = null;
      window.addEventListener(
        "pageshow",
        () => registerLifecycleRelease(controller),
        { once: true },
      );
      return;
    }
    void releaseClientInstanceForPageHide(controller, false);
    if (browserController === controller) {
      browserController = null;
    }
    lifecycleController = null;
  };
  window.addEventListener("pagehide", release, { once: true });
}

/**
 * Releasing a lock for a document that is actually unloading deliberately
 * preserves sessionStorage: the reloaded document needs the same proof to
 * resume its already-bound server session. A bfcache document keeps its lock.
 */
export async function releaseClientInstanceForPageHide(
  controller: ClientInstanceController,
  persisted: boolean,
): Promise<void> {
  if (!persisted) {
    await controller.dispose();
  }
}

function currentTabStorage(): Storage | null {
  if (typeof window === "undefined") {
    return null;
  }
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}

function currentLockManager(): LockManager | null {
  if (typeof navigator === "undefined") {
    return null;
  }
  try {
    const candidate = navigator as Navigator & { locks?: LockManager };
    return candidate.locks && typeof candidate.locks.request === "function"
      ? candidate.locks
      : null;
  } catch {
    return null;
  }
}

function hasOpener(): boolean {
  if (typeof window === "undefined") {
    return false;
  }
  try {
    return window.opener !== null;
  } catch {
    // A cross-origin opener cannot safely inherit a stored proof either.
    return true;
  }
}

function readStored(storage: Storage | null): string | null {
  if (!storage) return null;
  try {
    const value = storage.getItem(CLIENT_INSTANCE_STORAGE_KEY);
    return value !== null && isCanonicalClientInstance(value) ? value : null;
  } catch {
    return null;
  }
}

function writeStored(storage: Storage | null, value: string): boolean {
  if (!storage) return false;
  try {
    storage.setItem(CLIENT_INSTANCE_STORAGE_KEY, value);
    return storage.getItem(CLIENT_INSTANCE_STORAGE_KEY) === value;
  } catch {
    return false;
  }
}

function removeStored(storage: Storage | null): void {
  try {
    storage?.removeItem(CLIENT_INSTANCE_STORAGE_KEY);
  } catch {
    // A memory-only proof still prevents the stored value from being trusted.
  }
}

function isCanonicalClientInstance(value: string): boolean {
  if (!CLIENT_INSTANCE_PATTERN.test(value)) {
    return false;
  }
  const decoded = decodeBase64Url(value);
  if (!decoded) {
    return false;
  }
  try {
    return decoded.byteLength === CLIENT_INSTANCE_BYTES &&
      encodeBase64Url(decoded) === value;
  } finally {
    decoded.fill(0);
  }
}

function encodeBase64Url(bytes: Uint8Array): string | null {
  if (typeof btoa !== "function") {
    return null;
  }
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  try {
    return btoa(binary)
      .replaceAll("+", "-")
      .replaceAll("/", "_")
      .replace(/=+$/, "");
  } catch {
    return null;
  }
}

function decodeBase64Url(value: string): Uint8Array | null {
  if (typeof atob !== "function") {
    return null;
  }
  try {
    const base64 = value.replaceAll("-", "+").replaceAll("_", "/") + "=";
    const binary = atob(base64);
    const bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index += 1) {
      bytes[index] = binary.charCodeAt(index);
    }
    return bytes;
  } catch {
    return null;
  }
}
