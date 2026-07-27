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
import { autenticarPorCpf } from "./authService";
import { queueOfflineGrantUnavailableNotice } from "./authNotice";
import { authenticateWithPasskey } from "./passkeyApi";

import "./LoginPage.css";

type SubmitStatus = "idle" | "cpf" | "passkey";

export function LoginPage() {
  const cpfId = useId();
  const cpfRef = useRef<HTMLInputElement>(null);

  const [cpf, setCpf] = useState("");
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

  async function authenticateCpf(
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

    setStatus("cpf");
    try {
      const result = await autenticarPorCpf(onlyDigits(cpf));
      if (result.offlineGrant === "UNAVAILABLE") {
        queueOfflineGrantUnavailableNotice();
      }
      setStatus("idle");
      globalThis.location.assign("/");
    } catch (error: unknown) {
      setStatus("idle");
      setAuthError(errorMessage(error));
      cpfRef.current?.focus();
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
              Informe seu CPF para autenticar seu vínculo ativo de colaborador.
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
              void authenticateCpf(event);
            }}
            noValidate
          >
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
                  if (authError) {
                    setAuthError("");
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

            <div className="login__actions">
              <button
                type="submit"
                className="login__submit"
                disabled={loading || !online}
              >
                {status === "cpf" ? (
                  <span className="login__submit-loading">
                    <span className="login__spinner" aria-hidden="true" />
                    Verificando acesso...
                  </span>
                ) : (
                  "Entrar"
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
            </div>

            {authError ? (
              <p
                className="login__alert"
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
