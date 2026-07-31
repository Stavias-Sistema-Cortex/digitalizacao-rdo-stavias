import {
  useId,
  useRef,
  useState,
  type SubmitEvent,
} from "react";

import staviasTile from "../../assets/stavias-s-tile.png";
import { unlockOfflineVault } from "./offlineVault";
import type { OfflineVaultMetadata } from "./offlineVault.types";
import { initializeCortexDb } from "../../lib/db/cortexDb";
import {
  loadCollaborativeOfflineGrant,
  unlockCollaborativeOfflineGrant,
} from "./collaborativeOfflineGrant";
import {
  formatCpf,
  onlyDigits,
  validateLoginForm,
  type LoginFieldErrors,
} from "./loginValidation";

import "./OfflineUnlockPage.css";

type OfflineUnlockPageProps = {
  passkeyMetadata: OfflineVaultMetadata | null;
  hasCollaborativeCpfGrant: boolean;
  canRetryOnline: boolean;
};

export function OfflineUnlockPage({
  passkeyMetadata,
  hasCollaborativeCpfGrant,
  canRetryOnline,
}: OfflineUnlockPageProps) {
  const cpfId = useId();
  const cpfRef = useRef<HTMLInputElement>(null);
  const [cpf, setCpf] = useState("");
  const [fieldErrors, setFieldErrors] = useState<LoginFieldErrors>({});
  const [status, setStatus] = useState<
    "idle" | "cpf" | "passkey" | "unsupported" | "error"
  >("idle");
  const [error, setError] = useState("");
  const busy = status === "cpf" || status === "passkey";

  async function handleCpfUnlock(
    event: SubmitEvent<HTMLFormElement>,
  ): Promise<void> {
    event.preventDefault();
    if (busy) {
      return;
    }
    setError("");
    const nextErrors = validateLoginForm(cpf);
    setFieldErrors(nextErrors);
    if (nextErrors.cpf) {
      cpfRef.current?.focus();
      return;
    }

    const canonicalCpf = onlyDigits(cpf);
    setStatus("cpf");
    try {
      const metadata = await loadCollaborativeOfflineGrant(canonicalCpf);
      if (!metadata) {
        // O acesso offline nasce de um login online: é ali que o servidor
        // assina o grant que fica guardado neste aparelho. Sem dizer isso, a
        // pessoa fica tentando o mesmo CPF no meio da obra sem saber que o
        // que falta aconteceu antes de sair da base.
        throw new Error(
          "Este CPF ainda não tem acesso offline neste aparelho. " +
            "Faça um login com internet uma vez neste mesmo aparelho e " +
            "depois ele passa a abrir sem sinal.",
        );
      }
      await unlockCollaborativeOfflineGrant(canonicalCpf, metadata);
      await initializeCortexDb();
    } catch (cause: unknown) {
      setStatus("error");
      setError(unlockErrorMessage(cause));
      cpfRef.current?.focus();
    }
  }

  async function handlePasskeyUnlock(): Promise<void> {
    if (busy || !passkeyMetadata) {
      return;
    }
    setStatus("passkey");
    setError("");
    try {
      const result = await unlockOfflineVault(passkeyMetadata);
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
          {hasCollaborativeCpfGrant
            ? "Informe seu CPF para abrir somente os dados locais autorizados neste dispositivo."
            : "Confirme sua passkey para abrir somente os dados locais autorizados neste dispositivo."}
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

        {hasCollaborativeCpfGrant ? (
          <form
            className="offline-unlock__form"
            onSubmit={(event) => {
              void handleCpfUnlock(event);
            }}
            noValidate
          >
            <label htmlFor={cpfId}>CPF</label>
            <input
              ref={cpfRef}
              id={cpfId}
              type="text"
              inputMode="numeric"
              autoComplete="username"
              placeholder="000.000.000-00"
              maxLength={14}
              value={cpf}
              onChange={(event) => {
                setCpf(formatCpf(event.target.value));
                if (fieldErrors.cpf) {
                  setFieldErrors({});
                }
                if (error) {
                  setError("");
                  setStatus("idle");
                }
              }}
              aria-invalid={fieldErrors.cpf ? true : undefined}
              aria-describedby={
                fieldErrors.cpf ? `${cpfId}-error` : undefined
              }
              disabled={busy}
            />
            {fieldErrors.cpf ? (
              <p id={`${cpfId}-error`} role="alert">
                {fieldErrors.cpf}
              </p>
            ) : null}
            <button
              type="submit"
              className="offline-unlock__primary"
              disabled={busy}
            >
              {status === "cpf"
                ? "Verificando CPF…"
                : "Desbloquear com CPF"}
            </button>
          </form>
        ) : null}

        {passkeyMetadata ? (
          <button
            type="button"
            className={
              hasCollaborativeCpfGrant
                ? "offline-unlock__secondary"
                : "offline-unlock__primary"
            }
            disabled={busy || status === "unsupported"}
            onClick={() => {
              void handlePasskeyUnlock();
            }}
          >
            {status === "passkey"
              ? "Verificando passkey…"
              : "Usar passkey"}
          </button>
        ) : null}

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
          A identidade é confirmada localmente antes da abertura dos dados.
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
