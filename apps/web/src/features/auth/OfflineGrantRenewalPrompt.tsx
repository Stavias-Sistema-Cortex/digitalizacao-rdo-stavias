import {
  useId,
  useRef,
  useState,
  type SubmitEvent,
} from "react";

import { autenticarPorCpf } from "./authService";
import {
  formatCpf,
  onlyDigits,
  validateLoginForm,
  type LoginFieldErrors,
} from "./loginValidation";

type OfflineGrantRenewalPromptProps = {
  onSuccess: () => void;
  onCancel: () => void;
};

export function OfflineGrantRenewalPrompt({
  onSuccess,
  onCancel,
}: OfflineGrantRenewalPromptProps) {
  const cpfId = useId();
  const passwordId = useId();
  const cpfRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);
  const [cpf, setCpf] = useState("");
  const [password, setPassword] = useState("");
  const [errors, setErrors] = useState<LoginFieldErrors>({});
  const [submitting, setSubmitting] = useState(false);
  const [failure, setFailure] = useState("");

  async function submit(event: SubmitEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    if (submitting) {
      return;
    }
    const nextErrors = validateLoginForm(cpf, password);
    setErrors(nextErrors);
    setFailure("");
    if (nextErrors.cpf) {
      cpfRef.current?.focus();
      return;
    }
    if (nextErrors.password) {
      passwordRef.current?.focus();
      return;
    }

    setSubmitting(true);
    try {
      const result = await autenticarPorCpf(onlyDigits(cpf), password);
      if (result.offlineGrant !== "READY") {
        throw new Error("Cofre offline não atualizado.");
      }
      onSuccess();
    } catch {
      setFailure(
        "Não foi possível renovar o acesso offline deste aparelho.",
      );
      passwordRef.current?.focus();
    } finally {
      setPassword("");
      setSubmitting(false);
    }
  }

  return (
    <aside
      className="auth-renewal-prompt"
      aria-labelledby="auth-renewal-prompt-title"
    >
      <div>
        <h2 id="auth-renewal-prompt-title">Renovar acesso offline</h2>
        <p>
          Confirme sua senha enquanto há conexão para manter este aparelho
          preparado para trabalhar sem internet.
        </p>
        {failure ? <p role="alert">{failure}</p> : null}
        <form onSubmit={(event) => void submit(event)} noValidate>
          <label htmlFor={cpfId}>CPF para renovar</label>
          <input
            ref={cpfRef}
            id={cpfId}
            type="text"
            inputMode="numeric"
            autoComplete="username"
            value={cpf}
            onChange={(event) => {
              setCpf(formatCpf(event.target.value));
              setErrors({});
              setFailure("");
            }}
            aria-invalid={errors.cpf ? true : undefined}
            disabled={submitting}
          />
          {errors.cpf ? <p role="alert">{errors.cpf}</p> : null}
          <label htmlFor={passwordId}>Senha para renovar</label>
          <input
            ref={passwordRef}
            id={passwordId}
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(event) => {
              setPassword(event.target.value);
              setErrors({});
              setFailure("");
            }}
            aria-invalid={errors.password ? true : undefined}
            disabled={submitting}
          />
          {errors.password ? <p role="alert">{errors.password}</p> : null}
          <div>
            <button type="submit" disabled={submitting}>
              {submitting ? "Renovando…" : "Renovar acesso offline"}
            </button>
            <button
              type="button"
              disabled={submitting}
              onClick={onCancel}
            >
              Agora não
            </button>
          </div>
        </form>
      </div>
    </aside>
  );
}
