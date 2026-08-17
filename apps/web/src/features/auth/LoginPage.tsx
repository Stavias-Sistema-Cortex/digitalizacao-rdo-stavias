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
import { completePasswordSetup } from "./authApi";
import { despertarApi } from "./despertarApi";
import { queueOfflineGrantUnavailableNotice } from "./authNotice";
import { authenticateWithPasskey } from "./passkeyApi";

import "./LoginPage.css";

type SubmitStatus = "idle" | "cpf" | "passkey" | "setup";
type LoginMode = "login" | "setup";

export function LoginPage() {
  const cpfId = useId();
  const passwordId = useId();
  const passwordHelpId = useId();
  const newPasswordId = useId();
  const cpfRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);

  const [cpf, setCpf] = useState("");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [mode, setMode] = useState<LoginMode>("login");
  const [errors, setErrors] = useState<LoginFieldErrors>({});
  const [status, setStatus] = useState<SubmitStatus>("idle");
  const [authError, setAuthError] = useState("");
  const [online, setOnline] = useState(() => navigator.onLine);
  const [subindo, setSubindo] = useState(false);
  const [setupSuccess, setSetupSuccess] = useState("");
  const [passwordHelpOpen, setPasswordHelpOpen] = useState(false);

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
    const nextErrors = validateLoginForm(cpf, password);
    setErrors(nextErrors);
    if (nextErrors.cpf) {
      cpfRef.current?.focus();
      return;
    }
    if (nextErrors.password) {
      passwordRef.current?.focus();
      return;
    }

    if (/^[0-9]{8}$/.test(password)) {
      setCode(password);
      setPassword("");
      setNewPassword("");
      setPasswordHelpOpen(false);
      setMode("setup");
      return;
    }

    setStatus("cpf");
    try {
      const result = await autenticarPorCpf(onlyDigits(cpf), password);
      if (result.offlineGrant === "UNAVAILABLE") {
        queueOfflineGrantUnavailableNotice();
      }
      setStatus("idle");
    } catch (error: unknown) {
      setStatus("idle");
      setAuthError(errorMessage(error));
      passwordRef.current?.focus();
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

  async function definePassword(
    event: SubmitEvent<HTMLFormElement>,
  ): Promise<void> {
    event.preventDefault();
    if (loading || !online) {
      return;
    }
    setAuthError("");
    setSetupSuccess("");
    const nextErrors = validateLoginForm(cpf);
    setErrors(nextErrors);
    if (nextErrors.cpf) {
      cpfRef.current?.focus();
      return;
    }
    if (!/^[0-9]{8}$/.test(code)) {
      setAuthError("Informe o código temporário de 8 dígitos.");
      return;
    }
    if (newPassword.length < 12 || newPassword.length > 128) {
      setAuthError("A nova senha deve ter de 12 a 128 caracteres.");
      return;
    }

    setStatus("setup");
    try {
      await completePasswordSetup(onlyDigits(cpf), code, newPassword);
      setCode("");
      setNewPassword("");
      setPassword("");
      setPasswordHelpOpen(false);
      setMode("login");
      setSetupSuccess("Senha definida. Agora entre com sua nova senha.");
      setStatus("idle");
    } catch (error: unknown) {
      setStatus("idle");
      setAuthError(errorMessage(error));
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
            <h1 id="login-title">
              {mode === "login" ? "Entrar no sistema" : "Definir sua senha"}
            </h1>
            <p className="login__subtitle">
              {mode === "login"
                ? "Use seu CPF e sua senha para entrar."
                : "Código temporário informado. Agora defina sua nova senha."}
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
              if (mode === "login") {
                void authenticateCpf(event);
              } else {
                void definePassword(event);
              }
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
                disabled={loading || mode === "setup"}
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

            {mode === "login" ? (
              <div className="login-field">
                <div className="login-field__label-row">
                  <label className="login-field__label" htmlFor={passwordId}>
                    Senha
                  </label>
                  <span
                    className="login-field__info"
                    data-open={passwordHelpOpen ? "true" : "false"}
                    onMouseEnter={() => setPasswordHelpOpen(true)}
                    onMouseLeave={() => setPasswordHelpOpen(false)}
                  >
                    <button
                      type="button"
                      className="login-field__info-trigger"
                      aria-label="Informação sobre primeiro acesso"
                      aria-describedby={passwordHelpId}
                      aria-expanded={passwordHelpOpen}
                      onClick={() => setPasswordHelpOpen(true)}
                      onBlur={() => setPasswordHelpOpen(false)}
                      onKeyDown={(event) => {
                        if (event.key === "Escape") {
                          setPasswordHelpOpen(false);
                        }
                      }}
                    >
                      i
                    </button>
                    <span
                      id={passwordHelpId}
                      className="login-field__info-tooltip"
                      role="tooltip"
                    >
                      Se este é seu primeiro login, adicione o código de acesso.
                    </span>
                  </span>
                </div>
                <input
                  ref={passwordRef}
                  id={passwordId}
                  className={
                    errors.password
                      ? "login-field__input login-field__input--error"
                      : "login-field__input"
                  }
                  type="password"
                  autoComplete="current-password"
                  value={password}
                  maxLength={128}
                  onChange={(event) => {
                    setPassword(event.target.value);
                    if (errors.password) {
                      setErrors({ ...errors, password: undefined });
                    }
                    setAuthError("");
                  }}
                  aria-invalid={errors.password ? true : undefined}
                  aria-describedby={
                    errors.password ? `${passwordId}-error` : undefined
                  }
                  disabled={loading}
                />
                {errors.password ? (
                  <p
                    className="login-field__error"
                    id={`${passwordId}-error`}
                    role="alert"
                  >
                    {errors.password}
                  </p>
                ) : null}
              </div>
            ) : (
              <div className="login-field">
                <label
                  className="login-field__label"
                  htmlFor={newPasswordId}
                >
                  Nova senha
                </label>
                <input
                  id={newPasswordId}
                  className="login-field__input"
                  type="password"
                  autoComplete="new-password"
                  value={newPassword}
                  maxLength={128}
                  onChange={(event) => {
                    setNewPassword(event.target.value);
                    setAuthError("");
                  }}
                  disabled={loading}
                />
              </div>
            )}

            <div className="login__actions">
              <button
                type="submit"
                className="login__submit"
                disabled={loading || !online}
              >
                {status === "cpf" || status === "setup" ? (
                  <span className="login__submit-loading">
                    <span className="login__spinner" aria-hidden="true" />
                    {status === "setup"
                      ? "Definindo senha..."
                      : "Verificando acesso..."}
                  </span>
                ) : (
                  mode === "login" ? "Entrar" : "Definir senha"
                )}
              </button>

              {mode === "login" ? (
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
              ) : (
                <button
                  type="button"
                  className="login__submit login__submit-secondary"
                  disabled={loading}
                  onClick={() => {
                    setCode("");
                    setNewPassword("");
                    setPasswordHelpOpen(false);
                    setMode("login");
                    setErrors({});
                    setAuthError("");
                    setSetupSuccess("");
                  }}
                >
                  Voltar para entrar com senha
                </button>
              )}
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
            {setupSuccess ? (
              <p className="login__success" role="status">
                {setupSuccess}
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
