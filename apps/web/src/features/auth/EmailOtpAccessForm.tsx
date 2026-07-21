import {
  useEffect,
  useId,
  useRef,
  useState,
  type FormEvent,
} from "react";

import type { AuthProfile } from "./authSession";
import {
  requestEmailOtpChallenge,
  verifyEmailOtpChallenge,
} from "./emailOtpApi";

import "./EmailOtpAccessForm.css";

type EmailOtpAccessFormProps = {
  onVerified: (profile: AuthProfile) => void | Promise<void>;
  purpose: "activation" | "runtime";
};

type FormPhase = "email" | "code";
type PendingAction = "idle" | "request" | "verify";

export function EmailOtpAccessForm({
  onVerified,
  purpose,
}: EmailOtpAccessFormProps) {
  const emailId = useId();
  const codeId = useId();
  const emailRef = useRef<HTMLInputElement>(null);
  const codeRef = useRef<HTMLInputElement>(null);
  const [phase, setPhase] = useState<FormPhase>("email");
  const [pending, setPending] = useState<PendingAction>("idle");
  const [challengeId, setChallengeId] = useState<string | null>(null);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");

  const busy = pending !== "idle";

  useEffect(() => {
    if (phase === "code") {
      codeRef.current?.focus();
    }
  }, [phase]);

  async function requestCode(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) {
      return;
    }

    const normalizedEmail = emailRef.current?.value.trim() ?? "";
    if (!isEmail(normalizedEmail)) {
      setError("Informe um e-mail institucional válido.");
      setNotice("");
      emailRef.current?.focus();
      return;
    }

    setPending("request");
    setError("");
    setNotice("");
    try {
      const challenge = await requestEmailOtpChallenge(normalizedEmail);
      // The address is transient DOM input, never retained in React state.
      if (emailRef.current) {
        emailRef.current.value = "";
      }
      setChallengeId(challenge.challengeId);
      if (codeRef.current) {
        codeRef.current.value = "";
      }
      setPhase("code");
      setNotice(
        "Se os dados estiverem aptos, enviaremos um código para o e-mail cadastrado.",
      );
    } catch {
      setError(
        "Não foi possível solicitar o código agora. Verifique a conexão e tente novamente.",
      );
      emailRef.current?.focus();
    } finally {
      setPending("idle");
    }
  }

  async function verifyCode(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy || !challengeId) {
      setPhase("email");
      return;
    }
    const submittedCode = codeRef.current?.value ?? "";
    if (!/^\d{6}$/.test(submittedCode)) {
      setError("Informe os seis dígitos recebidos por e-mail.");
      setNotice("");
      codeRef.current?.focus();
      return;
    }

    setPending("verify");
    setError("");
    setNotice("");
    try {
      const profile = await verifyEmailOtpChallenge(
        challengeId,
        submittedCode,
      );
      if (codeRef.current) {
        codeRef.current.value = "";
      }
      await onVerified(profile);
    } catch {
      setError(
        "Não foi possível confirmar o código. Solicite um novo código e tente novamente.",
      );
      codeRef.current?.focus();
    } finally {
      setPending("idle");
    }
  }

  function returnToEmail() {
    if (busy) {
      return;
    }
    setChallengeId(null);
    if (codeRef.current) {
      codeRef.current.value = "";
    }
    setNotice("");
    setError("");
    setPhase("email");
    window.setTimeout(() => emailRef.current?.focus(), 0);
  }

  const activation = purpose === "activation";

  return (
    <section className="email-otp-access" aria-label="Verificação por e-mail">
      <header className="email-otp-access__header">
        <p className="email-otp-access__eyebrow">
          {activation ? "Prova de identidade" : "Acesso por e-mail"}
        </p>
        <h2>
          {phase === "email"
            ? "Confirmar acesso"
            : "Validar código de acesso"}
        </h2>
        <p>
          {phase === "email"
            ? "Use o e-mail institucional vinculado à sua identidade Córtex."
            : "Digite o código de seis dígitos enviado ao e-mail cadastrado."}
        </p>
      </header>

      {phase === "email" ? (
        <form className="email-otp-access__form" onSubmit={(event) => void requestCode(event)} noValidate>
          <label htmlFor={emailId}>E-mail institucional</label>
          <input
            ref={emailRef}
            id={emailId}
            name="email"
            type="email"
            inputMode="email"
            autoComplete="email"
            onChange={() => {
              if (error) {
                setError("");
              }
            }}
            disabled={busy}
            aria-invalid={error ? true : undefined}
            aria-describedby={error ? `${emailId}-message` : undefined}
          />
          <button type="submit" disabled={busy}>
            {pending === "request" ? "Solicitando código…" : "Enviar código"}
          </button>
        </form>
      ) : (
        <form className="email-otp-access__form" onSubmit={(event) => void verifyCode(event)} noValidate>
          <label htmlFor={codeId}>Código de acesso</label>
          <input
            ref={codeRef}
            id={codeId}
            name="code"
            type="text"
            inputMode="numeric"
            autoComplete="one-time-code"
            pattern="[0-9]{6}"
            maxLength={6}
            onChange={(event) => {
              event.currentTarget.value = event.currentTarget.value
                .replace(/\D/g, "")
                .slice(0, 6);
              if (error) {
                setError("");
              }
            }}
            disabled={busy}
            aria-invalid={error ? true : undefined}
            aria-describedby={error ? `${codeId}-message` : undefined}
          />
          <button type="submit" disabled={busy}>
            {pending === "verify" ? "Confirmando código…" : "Confirmar acesso"}
          </button>
          <button
            className="email-otp-access__secondary"
            type="button"
            disabled={busy}
            onClick={returnToEmail}
          >
            Usar outro e-mail
          </button>
        </form>
      )}

      <div className="email-otp-access__messages" aria-live="polite" aria-atomic="true">
        {notice ? <p className="email-otp-access__notice">{notice}</p> : null}
        {error ? (
          <p className="email-otp-access__error" role="alert" id={phase === "email" ? `${emailId}-message` : `${codeId}-message`}>
            {error}
          </p>
        ) : null}
      </div>
    </section>
  );
}

function isEmail(value: string): boolean {
  return value.length <= 320 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value);
}
