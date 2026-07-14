import {
  useEffect,
  useId,
  useRef,
  useState,
  type SubmitEvent,
} from "react";

import canteiroBackdrop from "../../assets/login/stavias-canteiro.png";
import staviasTile from "../../assets/stavias-s-tile.png";
import {
  formatCpf,
  onlyDigits,
  validateLoginForm,
  type LoginFieldErrors,
} from "./loginValidation";
import { solicitarCodigo, verificarCodigo } from "./authService";

import "./LoginPage.css";

type LoginStep = "identify" | "verify";
type SubmitStatus = "idle" | "requesting" | "verifying" | "resending";

export function LoginPage() {
  const cpfId = useId();
  const codeId = useId();
  const cpfRef = useRef<HTMLInputElement>(null);
  const codeRef = useRef<HTMLInputElement>(null);

  const [step, setStep] = useState<LoginStep>("identify");
  const [cpf, setCpf] = useState("");
  const [code, setCode] = useState("");
  const [challengeId, setChallengeId] = useState<string | null>(null);
  const [challengeMessage, setChallengeMessage] = useState("");
  const [expiresAt, setExpiresAt] = useState<number | null>(null);
  const [secondsLeft, setSecondsLeft] = useState(0);
  const [errors, setErrors] = useState<LoginFieldErrors>({});
  const [codeError, setCodeError] = useState("");
  const [status, setStatus] = useState<SubmitStatus>("idle");
  const [authError, setAuthError] = useState("");
  const [online, setOnline] = useState(() => navigator.onLine);

  const loading = status !== "idle";

  useEffect(() => {
    function handleOnline() {
      setOnline(true);
    }
    function handleOffline() {
      setOnline(false);
    }
    window.addEventListener("online", handleOnline);
    window.addEventListener("offline", handleOffline);
    return () => {
      window.removeEventListener("online", handleOnline);
      window.removeEventListener("offline", handleOffline);
    };
  }, []);

  useEffect(() => {
    if (step === "verify" && !loading) {
      codeRef.current?.focus();
    }
  }, [step, loading]);

  useEffect(() => {
    if (expiresAt === null) {
      return;
    }
    const deadline = expiresAt;
    function updateCountdown() {
      setSecondsLeft(
        Math.max(0, Math.ceil((deadline - Date.now()) / 1_000)),
      );
    }
    const intervalId = window.setInterval(updateCountdown, 1_000);
    return () => window.clearInterval(intervalId);
  }, [expiresAt]);

  async function issueChallenge(nextStatus: SubmitStatus): Promise<void> {
    setStatus(nextStatus);
    setAuthError("");
    try {
      const challenge = await solicitarCodigo(cpf);
      setChallengeId(challenge.challengeId);
      setChallengeMessage(challenge.message);
      setExpiresAt(Date.now() + challenge.expiresInSeconds * 1_000);
      setSecondsLeft(challenge.expiresInSeconds);
      setCode("");
      setCodeError("");
      setStep("verify");
    } catch (error: unknown) {
      setAuthError(errorMessage(error));
      if (step === "identify") {
        cpfRef.current?.focus();
      }
    } finally {
      setStatus("idle");
    }
  }

  async function handleSubmit(
    event: SubmitEvent<HTMLFormElement>,
  ): Promise<void> {
    event.preventDefault();
    if (loading || !online) {
      return;
    }
    setAuthError("");

    if (step === "identify") {
      const nextErrors = validateLoginForm(cpf);
      setErrors(nextErrors);
      if (nextErrors.cpf) {
        cpfRef.current?.focus();
        return;
      }
      await issueChallenge("requesting");
      return;
    }

    const normalizedCode = onlyDigits(code);
    if (normalizedCode.length !== 6 || !challengeId) {
      setCodeError("Informe o código de 6 dígitos.");
      codeRef.current?.focus();
      return;
    }

    setStatus("verifying");
    setCodeError("");
    try {
      await verificarCodigo(challengeId, normalizedCode);
      window.location.assign("/");
    } catch (error: unknown) {
      setAuthError(errorMessage(error));
      setStatus("idle");
      codeRef.current?.focus();
    }
  }

  function handleBack(): void {
    if (loading) {
      return;
    }
    setStep("identify");
    setChallengeId(null);
    setChallengeMessage("");
    setExpiresAt(null);
    setSecondsLeft(0);
    setCode("");
    setCodeError("");
    setAuthError("");
    window.setTimeout(() => cpfRef.current?.focus(), 0);
  }

  return (
    <main className="cortex-login">
      <img className="login__backdrop" src={canteiroBackdrop} alt="" aria-hidden="true" />
      <div className="login__tint" aria-hidden="true" />

      <section className="login__stage">
        <h1 className="visually-hidden">Entrar no Stavias Córtex</h1>
        <img className="login__mark" src={staviasTile} alt="Stavias" draggable={false} />

        <div className="login__card">
          <p className="login__phrase">
            Do minério ao asfalto
            <br />
            que <em>move</em> o país.
          </p>

          <p className="login__subtitle">
            {step === "identify"
              ? "Informe seu CPF cadastrado no Academy."
              : "Informe o código enviado ao e-mail corporativo cadastrado."}
          </p>

          {!online ? (
            <p className="login__offline" role="status">
              Sem conexão — O login exige conexão com o Córtex.
            </p>
          ) : null}

          {step === "verify" && challengeMessage ? (
            <p className="login__challenge-message" role="status">
              {challengeMessage}
            </p>
          ) : null}

          <form
            className="login__form"
            onSubmit={(event) => {
              void handleSubmit(event);
            }}
            noValidate
          >
            {step === "identify" ? (
              <div className="login-field">
                <label className="login-field__label" htmlFor={cpfId}>CPF</label>
                <input
                  ref={cpfRef}
                  id={cpfId}
                  className={errors.cpf ? "login-field__input login-field__input--error" : "login-field__input"}
                  type="text"
                  inputMode="numeric"
                  autoComplete="username"
                  placeholder="000.000.000-00"
                  maxLength={14}
                  value={cpf}
                  onChange={(event) => {
                    setCpf(formatCpf(event.target.value));
                    if (errors.cpf) setErrors({});
                  }}
                  aria-invalid={errors.cpf ? true : undefined}
                  aria-describedby={errors.cpf ? `${cpfId}-error` : undefined}
                  disabled={loading}
                />
                {errors.cpf ? (
                  <p className="login-field__error" id={`${cpfId}-error`} role="alert">
                    {errors.cpf}
                  </p>
                ) : null}
              </div>
            ) : (
              <div className="login-field">
                <label className="login-field__label" htmlFor={codeId}>Código de acesso</label>
                <input
                  ref={codeRef}
                  id={codeId}
                  className={codeError ? "login-field__input login-field__input--error login-field__input--code" : "login-field__input login-field__input--code"}
                  type="text"
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  placeholder="000000"
                  maxLength={6}
                  value={code}
                  onChange={(event) => {
                    setCode(onlyDigits(event.target.value).slice(0, 6));
                    if (codeError) setCodeError("");
                  }}
                  aria-invalid={codeError ? true : undefined}
                  aria-describedby={codeError ? `${codeId}-error` : `${codeId}-expiry`}
                  disabled={loading}
                />
                {codeError ? (
                  <p className="login-field__error" id={`${codeId}-error`} role="alert">
                    {codeError}
                  </p>
                ) : (
                  <p className="login__code-expiry" id={`${codeId}-expiry`}>
                    {secondsLeft > 0
                      ? `Este código expira em ${formatCountdown(secondsLeft)}.`
                      : "Este código expirou. Solicite outro código."}
                  </p>
                )}
              </div>
            )}

            <button type="submit" className="login__submit" disabled={loading || !online}>
              {loading && status !== "resending" ? (
                <span className="login__submit-loading">
                  <span className="login__spinner" aria-hidden="true" />
                  {status === "verifying" ? "Verificando..." : "Enviando..."}
                </span>
              ) : step === "identify" ? (
                "Enviar código"
              ) : (
                "Verificar código"
              )}
            </button>

            {step === "verify" ? (
              <div className="login__secondary-actions">
                <button type="button" className="login__text-action" onClick={handleBack} disabled={loading}>
                  Alterar CPF
                </button>
                <button
                  type="button"
                  className="login__text-action"
                  onClick={() => void issueChallenge("resending")}
                  disabled={loading || !online}
                >
                  {status === "resending" ? "Reenviando..." : "Reenviar código"}
                </button>
              </div>
            ) : null}

            {authError ? <p className="login__alert" role="alert">{authError}</p> : null}
          </form>

          <p className="login__hint">
            Acesso restrito a colaboradores ativos. Problemas para entrar? Procure o RH ou o apontador da sua obra.
          </p>
        </div>
      </section>

      <p className="login__footer">© 2026 Stavias — Sistema Córtex</p>
    </main>
  );
}

function formatCountdown(seconds: number): string {
  const minutes = Math.floor(seconds / 60);
  const remainder = seconds % 60;
  return `${minutes}:${String(remainder).padStart(2, "0")}`;
}

function errorMessage(error: unknown): string {
  return error instanceof Error
    ? error.message
    : "Não foi possível autenticar agora. Verifique a conexão e tente novamente.";
}
