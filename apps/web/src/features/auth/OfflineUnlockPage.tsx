import { useState } from "react";

import staviasTile from "../../assets/stavias-s-tile.png";
import { unlockOfflineVault } from "./offlineVault";
import type { OfflineVaultMetadata } from "./offlineVault.types";
import { initializeCortexDb } from "../../lib/db/cortexDb";

import "./OfflineUnlockPage.css";

type OfflineUnlockPageProps = {
  metadata: OfflineVaultMetadata;
  canRetryOnline: boolean;
};

export function OfflineUnlockPage({
  metadata,
  canRetryOnline,
}: OfflineUnlockPageProps) {
  const [status, setStatus] = useState<
    "idle" | "unlocking" | "unsupported" | "error"
  >("idle");
  const [error, setError] = useState("");

  async function handleUnlock(): Promise<void> {
    if (status === "unlocking") {
      return;
    }
    setStatus("unlocking");
    setError("");
    try {
      const result = await unlockOfflineVault(metadata);
      if (result === "PRF_UNAVAILABLE") {
        setStatus("unsupported");
        return;
      }
      await initializeCortexDb();
    } catch (cause: unknown) {
      setStatus("error");
      setError(unlockErrorMessage(cause));
    }
  }

  return (
    <main className="offline-unlock">
      <section
        className="offline-unlock__card"
        aria-labelledby="offline-unlock-title"
      >
        <img
          className="offline-unlock__mark"
          src={staviasTile}
          alt="Stavias"
          draggable={false}
        />
        <p className="offline-unlock__eyebrow">
          Modo offline protegido
        </p>
        <h1 id="offline-unlock-title">
          Desbloquear dados deste dispositivo
        </h1>
        <p className="offline-unlock__copy">
          Confirme sua passkey para abrir somente os dados locais autorizados. Nenhuma credencial é enviada pela rede.
        </p>

        {status === "unsupported" ? (
          <p className="offline-unlock__notice" role="alert">
            Este navegador exige conexão para entrar.
          </p>
        ) : null}
        {status === "error" ? (
          <p className="offline-unlock__notice" role="alert">
            {error}
          </p>
        ) : null}

        <button
          type="button"
          className="offline-unlock__primary"
          disabled={status === "unlocking" || status === "unsupported"}
          onClick={() => {
            void handleUnlock();
          }}
        >
          {status === "unlocking"
            ? "Verificando passkey…"
            : "Usar passkey"}
        </button>

        {canRetryOnline ? (
          <button
            type="button"
            className="offline-unlock__secondary"
            onClick={() => window.location.reload()}
          >
            Tentar conexão novamente
          </button>
        ) : null}

        <p className="offline-unlock__footnote">
          Não há entrada alternativa por CPF, PIN ou código local.
        </p>
      </section>
    </main>
  );
}

function unlockErrorMessage(cause: unknown): string {
  if (cause instanceof DOMException && cause.name === "NotAllowedError") {
    return "A verificação da passkey foi cancelada ou expirou.";
  }
  return cause instanceof Error
    ? cause.message
    : "Não foi possível abrir o cofre offline.";
}
