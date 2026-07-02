import {
  useEffect,
  useId,
  useRef,
  useState,
  type SubmitEvent,
} from "react";

import constructionHero from "../../assets/login/construction-hero.jpg";
import cortexLogo from "../../assets/login/cortex-logo.png";

import {
  formatCpf,
  validateLoginForm,
  type LoginFieldErrors,
} from "./loginValidation";
import { autenticar, sincronizarFiltroOffline } from "./authService";
import { getCachedFilter } from "./cpfFilter";

import "./LoginPage.css";

type SubmitStatus = "idle" | "loading";

export function LoginPage() {
  const cpfId = useId();
  const cpfRef = useRef<HTMLInputElement>(null);

  const [cpf, setCpf] = useState("");
  const [errors, setErrors] = useState<LoginFieldErrors>({});
  const [status, setStatus] = useState<SubmitStatus>("idle");
  const [authError, setAuthError] = useState("");
  const [online, setOnline] = useState(() => navigator.onLine);
  const [filtroOfflinePronto, setFiltroOfflinePronto] = useState(
    () => getCachedFilter() !== null,
  );

  const loading = status === "loading";

  useEffect(() => {
    // Pré-carrega o filtro de Bloom para habilitar o login offline.
    void sincronizarFiltroOffline().then(() => {
      setFiltroOfflinePronto(getCachedFilter() !== null);
    });

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

    if (loading) {
      return;
    }

    setAuthError("");
    const nextErrors = validateLoginForm(cpf);
    setErrors(nextErrors);

    if (nextErrors.cpf) {
      cpfRef.current?.focus();
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
        <figure className="login__media-caption">
          <span className="login__media-tick" aria-hidden="true" />
          <figcaption>
            <p className="login__media-phrase">
              Do canteiro ao relatório: o diário de obra digital da Stavias.
            </p>
          </figcaption>
        </figure>
      </section>

      <section className="cortex-login__panel">
        <div className="login__content">
          <div className="login__brand" role="img" aria-label="Stavias Córtex">
            <img className="login__brand-img" src={cortexLogo} alt="" />
          </div>

          <h1 className="login__title">Entrar no Córtex</h1>
          <p className="login__subtitle">
            Use o CPF cadastrado no Academy para acessar o ambiente
            operacional.
          </p>

          {!online ? (
            <p className="login__offline" role="status">
              {filtroOfflinePronto
                ? "Sem conexão — o login offline está habilitado neste dispositivo."
                : "Sem conexão — conecte-se uma vez para habilitar o login offline."}
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

          <p className="login__hint">
            Acesso restrito a colaboradores ativos. Problemas para
            entrar? Procure o RH ou o apontador da sua obra.
          </p>
        </div>

        <p className="login__footer">© 2026 Stavias — Sistema Córtex</p>
      </section>
    </main>
  );
}
