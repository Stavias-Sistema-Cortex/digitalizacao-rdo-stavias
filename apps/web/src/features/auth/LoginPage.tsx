import {
  useEffect,
  useId,
  useRef,
  useState,
  type FormEvent,
} from "react";

import constructionHero from "../../assets/login/construction-hero.jpg";
import cortexLogo from "../../assets/login/cortex-logo.png";
import topographic from "../../assets/login/topographic.png";

import {
  formatCpf,
  validateLoginForm,
  type LoginFieldErrors,
} from "./loginValidation";
import { autenticar, sincronizarFiltroOffline } from "./authService";

import "./LoginPage.css";

type SubmitStatus = "idle" | "loading";

const eyeIcon = (
  <svg
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
    aria-hidden="true"
  >
    <path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7-10-7-10-7Z" />
    <circle cx="12" cy="12" r="3" />
  </svg>
);

const eyeOffIcon = (
  <svg
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
    aria-hidden="true"
  >
    <path d="M10.7 6.2A9.9 9.9 0 0 1 12 6c6.4 0 10 7 10 7a18.4 18.4 0 0 1-3 3.9M6.3 6.3A18.3 18.3 0 0 0 2 13s3.6 7 10 7a9.9 9.9 0 0 0 4.3-1M9.9 9.9a3 3 0 0 0 4.2 4.2" />
    <path d="m3 3 18 18" />
  </svg>
);

export function LoginPage() {
  const cpfId = useId();
  const senhaId = useId();
  const cpfRef = useRef<HTMLInputElement>(null);
  const senhaRef = useRef<HTMLInputElement>(null);

  const [cpf, setCpf] = useState("");
  const [senha, setSenha] = useState("");
  const [errors, setErrors] = useState<LoginFieldErrors>({});
  const [showSenha, setShowSenha] = useState(false);
  const [status, setStatus] = useState<SubmitStatus>("idle");
  const [authError, setAuthError] = useState("");

  const loading = status === "loading";

  useEffect(() => {
    // Pré-carrega o filtro de Bloom para habilitar o login offline.
    void sincronizarFiltroOffline();
  }, []);

  async function handleSubmit(
    event: FormEvent<HTMLFormElement>,
  ): Promise<void> {
    event.preventDefault();

    if (loading) {
      return;
    }

    setAuthError("");
    const nextErrors = validateLoginForm(cpf, senha);
    setErrors(nextErrors);

    if (nextErrors.cpf) {
      cpfRef.current?.focus();
      return;
    }

    if (nextErrors.senha) {
      senhaRef.current?.focus();
      return;
    }

    setStatus("loading");
    const outcome = await autenticar(cpf);

    if (outcome.ok) {
      // Sessão criada: recarrega na raiz, onde o App exibe o workspace.
      window.location.assign("/");
      return;
    }

    setStatus("idle");
    setAuthError(outcome.message);
    cpfRef.current?.focus();
  }

  return (
    <main className="cortex-login">
      <section className="cortex-login__media">
        <img
          className="login__media-img"
          src={constructionHero}
          alt="Canteiro de obras ao entardecer com gruas e estruturas em construção."
        />
        <div className="login__media-scrim" aria-hidden="true" />
        <div className="login__media-caption" aria-hidden="true">
          <span className="login__media-tick" />
        </div>
      </section>

      <section className="cortex-login__panel">
        <img
          className="login__topo"
          src={topographic}
          alt=""
          aria-hidden="true"
        />
        <div className="login__grain" aria-hidden="true" />

        <div className="login__content">
          <div className="login__brand" role="img" aria-label="Stavias Córtex">
            <img
              className="login__brand-img login__brand-img--left"
              src={cortexLogo}
              alt=""
              aria-hidden="true"
            />
            <img
              className="login__brand-img login__brand-img--right"
              src={cortexLogo}
              alt=""
              aria-hidden="true"
            />
          </div>

          <h1 className="login__title">Entrar no Sistema Córtex</h1>
          <p className="login__subtitle">
            Acesse o ambiente operacional da Stavias
          </p>

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
              <div className="login-field__control">
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
                      setErrors((previous) => ({
                        ...previous,
                        cpf: undefined,
                      }));
                    }
                  }}
                  aria-invalid={errors.cpf ? true : undefined}
                  aria-describedby={
                    errors.cpf ? `${cpfId}-error` : undefined
                  }
                  disabled={loading}
                />
              </div>
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

            <div className="login-field">
              <label className="login-field__label" htmlFor={senhaId}>
                Senha
              </label>
              <div className="login-field__control">
                <input
                  ref={senhaRef}
                  id={senhaId}
                  className={
                    errors.senha
                      ? "login-field__input login-field__input--password login-field__input--error"
                      : "login-field__input login-field__input--password"
                  }
                  type={showSenha ? "text" : "password"}
                  autoComplete="current-password"
                  placeholder="Coloque seu CPF"
                  value={senha}
                  onChange={(event) => {
                    setSenha(event.target.value);
                    if (errors.senha) {
                      setErrors((previous) => ({
                        ...previous,
                        senha: undefined,
                      }));
                    }
                  }}
                  aria-invalid={errors.senha ? true : undefined}
                  aria-describedby={
                    errors.senha ? `${senhaId}-error` : undefined
                  }
                  disabled={loading}
                />
                <button
                  type="button"
                  className="login-field__toggle"
                  onClick={() => setShowSenha((value) => !value)}
                  aria-label={showSenha ? "Ocultar senha" : "Mostrar senha"}
                  aria-pressed={showSenha}
                  disabled={loading}
                >
                  {showSenha ? eyeOffIcon : eyeIcon}
                </button>
              </div>
              {errors.senha ? (
                <p
                  className="login-field__error"
                  id={`${senhaId}-error`}
                  role="alert"
                >
                  {errors.senha}
                </p>
              ) : null}
            </div>

        

            <button
              type="submit"
              className="login__submit"
              disabled={loading}
            >
              {loading ? (
                <span className="login__submit-loading">
                  <span className="login__spinner" aria-hidden="true" />
                  Entrando...
                </span>
              ) : (
                "Entrar"
              )}
            </button>

            {authError ? (
              <p className="login__alert" role="alert">
                {authError}
              </p>
            ) : null}
          </form>
        </div>

        <p className="login__footer">© 2026 Stavias — Sistema Córtex</p>
      </section>
    </main>
  );
}
