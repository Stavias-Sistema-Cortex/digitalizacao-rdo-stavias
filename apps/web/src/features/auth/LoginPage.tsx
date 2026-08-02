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
import { despertarApi } from "./despertarApi";
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
  const [subindo, setSubindo] = useState(false);

  const loading = status !== "idle";

  /*
   * A primeira entrada do dia pode encontrar a API fria e demorar.
   *
   * Sem dizer isso, a espera parece travamento: quem está em campo clica de
   * novo, abandona a aba ou conclui que o sistema caiu — bem no momento em que
   * o servidor estava subindo.
   */
  useEffect(() => {
    if (status !== "cpf") {
      return;
    }
    const id = window.setTimeout(() => setSubindo(true), 8_000);
    return () => {
      window.clearTimeout(id);
      setSubindo(false);
    };
  }, [status]);

  /*
   * A subida do servidor começa aqui, e não no envio do CPF.
   *
   * Quem abre a tela ainda vai procurar o campo e digitar onze dígitos. Esse
   * tempo era desperdiçado com a API parada; usado, ele sai inteiro da espera
   * que aparecia depois. Volta a tocar quando a aba é reexibida, porque uma
   * tela esquecida aberta encontra o serviço parado de novo.
   */
  useEffect(() => {
    despertarApi();

    function aoVoltarAoFoco() {
      if (document.visibilityState === "visible") {
        despertarApi();
      }
    }
    document.addEventListener("visibilitychange", aoVoltarAoFoco);
    return () => {
      document.removeEventListener("visibilitychange", aoVoltarAoFoco);
    };
  }, []);

  useEffect(() => {
    function handleOnline() {
      despertarApi();
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
        aria-labelledby="login-title"
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
        </div>

        <div className="login__card">
          <header className="login__card-header">
            <p className="login__eyebrow">Área restrita</p>
            <h1 id="login-title">Entrar no sistema</h1>
            <p className="login__subtitle">
              Use seu CPF ou uma passkey para entrar.
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

            {subindo && status === "cpf" ? (
              <p className="login__aguardando" role="status">
                O Córtex está subindo — pode levar um minuto. Não clique de
                novo.
              </p>
            ) : null}

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
            Apenas colaboradores autorizados. Ações vinculadas à sua identidade.
          </p>
        </div>
      </section>
    </main>
  );
}

function errorMessage(error: unknown): string {
  return error instanceof Error
    ? error.message
    : "Não foi possível autenticar agora. Verifique a conexão e tente novamente.";
}
