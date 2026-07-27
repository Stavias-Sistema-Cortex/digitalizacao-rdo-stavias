import {
  useEffect,
  useId,
  useRef,
  useState,
  type SubmitEvent,
} from "react";

import cortexLogo from "../../assets/login/cortex-logo.png";
import {
  formatCpf,
  onlyDigits,
  validateLoginForm,
  type LoginFieldErrors,
} from "./loginValidation";
import { setSession } from "./authSession";
import {
  requestCpfOtpChallenge,
  verifyEmailOtpChallenge,
} from "./emailOtpApi";
import { authenticateWithPasskey } from "./passkeyApi";

import "./LoginPage.css";

type LoginStep = "cpf" | "code";
type SubmitStatus = "idle" | "passkey" | "request" | "verify";

export function LoginPage() {
  const cpfId = useId();
  const codeId = useId();
  const cpfRef = useRef<HTMLInputElement>(null);
  const codeRef = useRef<HTMLInputElement>(null);

  const [cpf, setCpf] = useState("");
  const [code, setCode] = useState("");
  const [challengeId, setChallengeId] = useState<string | null>(null);
  const [step, setStep] = useState<LoginStep>("cpf");
  const [errors, setErrors] = useState<LoginFieldErrors>({});
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
    if (step === "code") {
      codeRef.current?.focus();
    }
  }, [step]);

  async function requestCode(
    event: SubmitEvent<HTMLFormElement>,
  ): Promise<void> {
    event.preventDefault();
    if (loading || !online) {
      return;
    }

    setAuthError("");
    const nextErrors = validateLoginForm(cpf);
    setErrors(nextErrors);
    if (nextErrors.cpf) {
      cpfRef.current?.focus();
      return;
    }

    setStatus("request");
    try {
      const challenge = await requestCpfOtpChallenge(onlyDigits(cpf));
      setChallengeId(challenge.challengeId);
      setCode("");
      setStep("code");
      setStatus("idle");
    } catch (error: unknown) {
      setStatus("idle");
      setAuthError(errorMessage(error));
      cpfRef.current?.focus();
    }
  }

  async function confirmCode(
    event: SubmitEvent<HTMLFormElement>,
  ): Promise<void> {
    event.preventDefault();
    if (loading || !online || !challengeId) {
      return;
    }
    if (!/^\d{6}$/.test(code)) {
      setAuthError("Informe os seis dígitos recebidos por e-mail.");
      codeRef.current?.focus();
      return;
    }

    setStatus("verify");
    setAuthError("");
    try {
      const profile = await verifyEmailOtpChallenge(challengeId, code);
      setCode("");
      setSession(profile);
      globalThis.location.assign("/");
    } catch (error: unknown) {
      setStatus("idle");
      setAuthError(errorMessage(error));
      codeRef.current?.focus();
    }
  }

  async function authenticatePasskey(): Promise<void> {
    if (loading || !online) {
      return;
    }

    setAuthError("");
    const nextErrors = validateLoginForm(cpf);
    setErrors(nextErrors);
    if (nextErrors.cpf) {
      cpfRef.current?.focus();
      return;
    }

    setStatus("passkey");
    try {
      await authenticateWithPasskey(cpf);
      globalThis.location.assign("/");
    } catch (error: unknown) {
      setStatus("idle");
      setAuthError(errorMessage(error));
      cpfRef.current?.focus();
    }
  }

  function returnToCpf(): void {
    if (loading) {
      return;
    }
    setChallengeId(null);
    setCode("");
    setAuthError("");
    setStep("cpf");
    window.setTimeout(() => cpfRef.current?.focus(), 0);
  }

  return (
    <main className="cortex-login">
      <section
        className="login__stage"
        aria-labelledby="login-system-title"
      >
        <div className="login__identity">
          <div className="login__brand">
            <img
              className="login__brand-lockup"
              src={cortexLogo}
              alt="Stavias Córtex"
              draggable={false}
            />
          </div>

          <div className="login__identity-copy">
            <p className="login__classification">Sistema Córtex</p>
            <h1 id="login-system-title">Acesso institucional</h1>
            <p>
              Ambiente operacional para gestão rastreável de obras,
              registros de campo e decisões de infraestrutura.
            </p>
          </div>

          <p className="login__security-note">
            <span aria-hidden="true" />
            Ambiente monitorado. Use somente suas credenciais individuais.
          </p>
        </div>

        <div className="login__card">
          <header className="login__card-header">
            <p className="login__eyebrow">Área restrita</p>
            <h2>Entrar no sistema</h2>
            <p className="login__subtitle">
              {step === "cpf"
                ? "Informe seu CPF para receber um código no e-mail institucional cadastrado."
                : "Digite o código de seis dígitos enviado ao e-mail institucional cadastrado."}
            </p>
          </header>

          {!online ? (
            <p className="login__offline" role="status">
              Sem conexão — O login exige conexão com o Córtex.
            </p>
          ) : null}

          <form
            className="login__form"
            onSubmit={(event) => {
              void (step === "cpf" ? requestCode(event) : confirmCode(event));
            }}
            noValidate
          >
            {step === "cpf" ? (
              <div className="login-field">
                <label className="login-field__label" htmlFor={cpfId}>
                  CPF
                </label>
                <input
                  ref={cpfRef}
                  id={cpfId}
                  className={
                    errors.cpf
                      ? "login-field__input login-field__input--error"
                      : "login-field__input"
                  }
                  type="text"
                  inputMode="numeric"
                  autoComplete="username"
                  placeholder="000.000.000-00"
                  maxLength={14}
                  value={cpf}
                  onChange={(event) => {
                    setCpf(formatCpf(event.target.value));
                    if (errors.cpf) {
                      setErrors({});
                    }
                  }}
                  aria-invalid={errors.cpf ? true : undefined}
                  aria-describedby={errors.cpf ? `${cpfId}-error` : undefined}
                  disabled={loading}
                />
                {errors.cpf ? (
                  <p
                    className="login-field__error"
                    id={`${cpfId}-error`}
                    role="alert"
                  >
                    {errors.cpf}
                  </p>
                ) : null}
              </div>
            ) : (
              <div className="login-field">
                <label className="login-field__label" htmlFor={codeId}>
                  Código de acesso
                </label>
                <input
                  ref={codeRef}
                  id={codeId}
                  className="login-field__input"
                  type="text"
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  placeholder="000000"
                  pattern="[0-9]{6}"
                  maxLength={6}
                  value={code}
                  onChange={(event) => {
                    setCode(event.target.value.replace(/\D/g, "").slice(0, 6));
                    if (authError) {
                      setAuthError("");
                    }
                  }}
                  aria-invalid={authError ? true : undefined}
                  aria-describedby={authError ? `${codeId}-error` : undefined}
                  disabled={loading}
                />
              </div>
            )}

            <div className="login__actions">
              {step === "cpf" ? (
                <>
                  <button
                    type="submit"
                    className="login__submit"
                    disabled={loading || !online}
                  >
                    {status === "request" ? (
                      <span className="login__submit-loading">
                        <span className="login__spinner" aria-hidden="true" />
                        Enviando código...
                      </span>
                    ) : (
                      "Enviar código"
                    )}
                  </button>

                  <button
                    type="button"
                    className="login__submit login__submit-secondary"
                    disabled={loading || !online}
                    onClick={() => {
                      void authenticatePasskey();
                    }}
                  >
                    {status === "passkey" ? (
                      <span className="login__submit-loading">
                        <span className="login__spinner" aria-hidden="true" />
                        Validando passkey...
                      </span>
                    ) : (
                      "Entrar com passkey"
                    )}
                  </button>
                </>
              ) : (
                <>
                  <button
                    type="submit"
                    className="login__submit"
                    disabled={loading || !online}
                  >
                    {status === "verify" ? (
                      <span className="login__submit-loading">
                        <span className="login__spinner" aria-hidden="true" />
                        Confirmando acesso...
                      </span>
                    ) : (
                      "Confirmar acesso"
                    )}
                  </button>
                  <button
                    type="button"
                    className="login__submit login__submit-secondary"
                    disabled={loading}
                    onClick={returnToCpf}
                  >
                    Usar outro CPF
                  </button>
                </>
              )}
            </div>

            {authError ? (
              <p
                className="login__alert"
                id={step === "code" ? `${codeId}-error` : undefined}
                role="alert"
              >
                {authError}
              </p>
            ) : null}
          </form>

          <p className="login__hint">
            Acesso destinado a colaboradores autorizados. As ações realizadas
            no sistema são vinculadas à identidade autenticada.
          </p>
        </div>
      </section>

      <p className="login__footer">
        © 2026 Stavias · Sistema Córtex · Ambiente operacional restrito
      </p>
    </main>
  );
}

function errorMessage(error: unknown): string {
  return error instanceof Error
    ? error.message
    : "Não foi possível autenticar agora. Verifique a conexão e tente novamente.";
}
