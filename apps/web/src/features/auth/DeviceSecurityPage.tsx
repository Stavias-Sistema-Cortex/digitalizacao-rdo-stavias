import { useEffect, useState } from "react";

import { getSession, hasOnlineSession } from "./authSession";
import type { OfflineVaultMetadata } from "./offlineVault.types";
import { loadOfflineVaultMetadataForOwner } from "./offlineVaultRepository";
import {
  registerPasskey,
  renewOfflineAccess,
  type PasskeyRegistrationResult,
} from "./passkeyApi";

import "./DeviceSecurityPage.css";

type RegistrationState =
  | { kind: "idle" }
  | { kind: "registering" }
  | { kind: "ready"; result: PasskeyRegistrationResult }
  | { kind: "renewed" }
  | { kind: "unsupported" }
  | { kind: "error"; message: string };

export function DeviceSecurityPage() {
  const [vault, setVault] = useState<OfflineVaultMetadata | null>(null);
  const [vaultChecked, setVaultChecked] = useState(false);
  const [state, setState] = useState<RegistrationState>({ kind: "idle" });
  const onlineSession = hasOnlineSession();
  const ownerId = getSession()?.colaboradorId ?? null;

  useEffect(() => {
    let cancelled = false;
    if (!ownerId) {
      return;
    }
    loadOfflineVaultMetadataForOwner(ownerId)
      .then((metadata) => {
        if (!cancelled) {
          setVault(metadata);
          setVaultChecked(true);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setVault(null);
          setVaultChecked(true);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [ownerId]);

  async function handleRegistration(): Promise<void> {
    if (state.kind === "registering" || !onlineSession) {
      return;
    }
    setState({ kind: "registering" });
    try {
      if (vault) {
        const result = await renewOfflineAccess(vault);
        setState(result === "READY"
          ? { kind: "renewed" }
          : { kind: "unsupported" });
        if (result === "READY" && ownerId) {
          setVault(await loadOfflineVaultMetadataForOwner(ownerId));
        }
        return;
      }
      const result = await registerPasskey();
      setState({ kind: "ready", result });
      if (result.offlineVault === "READY") {
        setVault(ownerId
          ? await loadOfflineVaultMetadataForOwner(ownerId)
          : null);
      }
    } catch (error: unknown) {
      setState({
        kind: "error",
        message: error instanceof Error
          ? error.message
          : "Não foi possível registrar a passkey neste dispositivo.",
      });
    }
  }

  const registering = state.kind === "registering";

  return (
    <main className="page-shell device-security-page">
      <header className="topbar">
        <div>
          <p className="eyebrow">Córtex · Conta</p>
          <h1>Segurança do dispositivo</h1>
          <p className="subtitle">
            Use uma passkey para entrar com biometria ou bloqueio do dispositivo e proteger o acesso offline.
          </p>
        </div>
      </header>

      <section className="surface-card device-security-card" aria-labelledby="passkey-title">
        <div className="device-security-card__header">
          <div>
            <p className="device-security-card__kicker">Passkey</p>
            <h2 id="passkey-title">Este dispositivo</h2>
          </div>
          <span className={vault ? "device-security-status is-ready" : "device-security-status"}>
            {!vaultChecked ? "Verificando" : vault ? "Offline protegido" : "Não configurado"}
          </span>
        </div>

        <p>
          O segredo usado para abrir os dados offline permanece no autenticador do dispositivo. O Córtex não envia nem armazena esse segredo.
        </p>

        {vault ? (
          <dl className="device-security-details">
            <div>
              <dt>Proteção offline</dt>
              <dd>Ativa neste navegador</dd>
            </div>
            <div>
              <dt>Última atualização</dt>
              <dd>{formatDateTime(vault.atualizadoEm)}</dd>
            </div>
          </dl>
        ) : null}

        <button
          type="button"
          className="primary-button device-security-register"
          onClick={() => {
            void handleRegistration();
          }}
          disabled={registering || !onlineSession}
        >
          {registering
            ? "Confirmando no dispositivo..."
            : vault
              ? "Atualizar acesso offline"
              : "Registrar passkey neste dispositivo"}
        </button>

        {!onlineSession ? (
          <p className="device-security-notice" role="status">
            Entre com uma sessão online válida para registrar uma passkey.
          </p>
        ) : null}

        {state.kind === "ready" && state.result.offlineVault === "READY" ? (
          <p className="device-security-success" role="status">
            Passkey registrada. O acesso offline protegido está pronto neste navegador.
          </p>
        ) : null}

        {state.kind === "ready" && state.result.offlineVault === "PRF_UNAVAILABLE" ? (
          <p className="device-security-notice" role="status">
            A passkey foi registrada para entrada online, mas este navegador não oferece PRF. O modo offline não foi habilitado e não existe método alternativo menos seguro.
          </p>
        ) : null}

        {state.kind === "renewed" ? (
          <p className="device-security-success" role="status">
            Acesso offline atualizado com o escopo e a validade atuais.
          </p>
        ) : null}

        {state.kind === "unsupported" ? (
          <p className="device-security-notice" role="status">
            Este navegador não disponibilizou PRF para atualizar o cofre. O acesso online continua disponível e o cofre anterior foi preservado.
          </p>
        ) : null}

        {state.kind === "error" ? (
          <p className="device-security-error" role="alert">{state.message}</p>
        ) : null}
      </section>
    </main>
  );
}

function formatDateTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "Data indisponível";
  }
  return new Intl.DateTimeFormat("pt-BR", {
    dateStyle: "short",
    timeStyle: "short",
  }).format(date);
}
