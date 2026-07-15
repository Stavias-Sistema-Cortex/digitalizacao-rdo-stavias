import { useState, type FormEvent } from "react";

import {
  criarObra,
  validarNovaObra,
  type NovaObraInput,
  type ObraAdminApi,
} from "./gestaoObrasApi";
import "./NovaObraForm.css";

const EMPTY_WORKSITE: NovaObraInput = {
  codigoContrato: "",
  nome: "",
  cliente: "",
  cidade: "",
  uf: "",
  rodovia: "",
};

export function NovaObraForm({
  onCreated,
  onCancel,
}: {
  onCreated: (worksite: ObraAdminApi) => void | Promise<void>;
  onCancel?: () => void;
}) {
  const [draft, setDraft] = useState<NovaObraInput>(EMPTY_WORKSITE);
  const [errors, setErrors] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);

  function patch(values: Partial<NovaObraInput>) {
    setDraft((current) => ({ ...current, ...values }));
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const validation = validarNovaObra(draft);
    setErrors(validation);
    if (validation.length > 0) return;
    setSaving(true);
    try {
      const created = await criarObra(draft);
      setDraft(EMPTY_WORKSITE);
      await onCreated(created);
    } catch (reason: unknown) {
      setErrors([reason instanceof Error
        ? reason.message
        : "Não foi possível criar a obra."]);
    } finally {
      setSaving(false);
    }
  }

  return (
    <form className="nova-obra-form" onSubmit={submit}>
      <div className="nova-obra-form__grid">
        <label>
          Código do contrato*
          <input
            autoFocus
            value={draft.codigoContrato}
            onChange={(event) => patch({ codigoContrato: event.target.value })}
          />
        </label>
        <label>
          Nome*
          <input value={draft.nome} onChange={(event) => patch({ nome: event.target.value })} />
        </label>
        <label className="is-wide">
          Cliente
          <input value={draft.cliente ?? ""} onChange={(event) => patch({ cliente: event.target.value })} />
        </label>
        <label>
          Cidade
          <input value={draft.cidade ?? ""} onChange={(event) => patch({ cidade: event.target.value })} />
        </label>
        <label>
          UF
          <input
            maxLength={2}
            value={draft.uf ?? ""}
            onChange={(event) => patch({ uf: event.target.value.toUpperCase() })}
          />
        </label>
        <label className="is-wide">
          Rodovia
          <input value={draft.rodovia ?? ""} onChange={(event) => patch({ rodovia: event.target.value })} />
        </label>
      </div>
      {errors.length > 0 ? (
        <ul className="nova-obra-form__errors" role="alert">
          {errors.map((error) => <li key={error}>{error}</li>)}
        </ul>
      ) : null}
      <footer>
        {onCancel ? <button type="button" onClick={onCancel}>Cancelar</button> : null}
        <button type="submit" className="nova-obra-form__submit" disabled={saving}>
          {saving ? "Criando…" : "Criar obra"}
        </button>
      </footer>
    </form>
  );
}
