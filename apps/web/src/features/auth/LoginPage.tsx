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
  validateLoginForm,
  type LoginFieldErrors,
} from "./loginValidation";
import { autenticarPorCpf } from "./authService";
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

  async function handleSubmit(
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
      await autenticarPorCpf(cpf);
      window.location.assign("/");
    } catch (error: unknown) {
      setStatus("idle");
      setAuthError(errorMessage(error));
      cpfRef.current?.focus();
    }
  }

  async function handlePasskeyLogin(): Promise<void> {
    if (loading || !online) {
      return;
    }
    setStatus("passkey");
    setAuthError("");
    try {
      await authenticateWithPasskey();
      window.location.assign("/");
    } catch (error: unknown) {
      setAuthError(errorMessage(error));
      setStatus("idle");
    }
  }

  return (
    <main className="cortex-login">
      <img
        className="login__backdrop"
        src={canteiroBackdrop}
        alt=""
        aria-hidden="true"
      />
      <div className="login__tint" aria-hidden="true" />

      <section className="login__stage">
        <h1 className="visually-hidden">Entrar no Stavias Córtex</h1>
        <img
          className="login__mark"
          src={staviasTile}
          alt="Stavias"
          draggable={false}
        />

        <div className="login__card">
          <p className="login__phrase">
            Do minério ao asfalto
            <br />
            que <em>move</em> o país.
          </p>

          <p className="login__subtitle">
            Informe seu CPF cadastrado no Academy.
          </p>

          {!online ? (
            <p className="login__offline" role="status">
              Sem conexão — O login exige conexão com o Córtex.
            </p>
          ) : null}

          <form
            className="login__form"
            onSubmit={(event) => {
              void handleSubmit(event);
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

            <button
              type="submit"
              className="login__submit"
              disabled={loading || !online}
            >
              {status === "cpf" ? (
                <span className="login__submit-loading">
                  <span className="login__spinner" aria-hidden="true" />
                  Entrando...
                </span>
              ) : (
                "Entrar"
              )}
            </button>

            <button
              type="button"
              className="login__passkey"
              onClick={() => {
                void handlePasskeyLogin();
              }}
              disabled={loading || !online}
            >
              {status === "passkey" ? "Confirmando..." : "Usar passkey"}
            </button>

            {authError ? (
              <p className="login__alert" role="alert">
                {authError}
              </p>
            ) : null}
          </form>

          <p className="login__hint">
            Acesso restrito a colaboradores ativos. Problemas para entrar?
            Procure o RH ou o apontador da sua obra.
          </p>
        </div>
      </section>

      <p className="login__footer">© 2026 Stavias — Sistema Córtex</p>
    </main>
  );
}

function errorMessage(error: unknown): string {
  return error instanceof Error
    ? error.message
    : "Não foi possível autenticar agora. Verifique a conexão e tente novamente.";
}
