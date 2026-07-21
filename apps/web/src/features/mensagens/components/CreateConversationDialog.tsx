import { useEffect, useState, type ChangeEvent, type FormEvent } from "react";

import type { ConversaTipo, ObraLocalRecord } from "../../../lib/db/db.types";
import {
  buscarColaboradores,
  buscarColaboradoresDaObra,
  type ColaboradorDaObra,
  type ColaboradorLookup,
} from "../../rdos/rdoLookupApi";
import { createConversationApi } from "../mensagensApi";
import { messageFrom } from "../mensagensFormat";

type DirectoryPerson = {
  id: string;
  nome: string;
  detalhe: string;
};

const CREATE_TYPES: { value: ConversaTipo; label: string }[] = [
  { value: "DIRETA", label: "Direta" },
  { value: "GRUPO", label: "Grupo" },
  { value: "OBRA", label: "Obra" },
];

export function CreateConversationDialog(props: {
  obrasPromise: Promise<ObraLocalRecord[]>;
  alfa: boolean;
  onClose: () => void;
  onCreated: (conversation: Awaited<ReturnType<typeof createConversationApi>>) => Promise<void>;
}) {
  const [type, setType] = useState<ConversaTipo>("DIRETA");
  const [title, setTitle] = useState("");
  const [obras, setObras] = useState<ObraLocalRecord[]>([]);
  const [obraId, setObraId] = useState("");
  const [query, setQuery] = useState("");
  const [people, setPeople] = useState<DirectoryPerson[]>([]);
  const [selectedPeople, setSelectedPeople] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    void props.obrasPromise.then(setObras).catch((cause) => setError(messageFrom(cause)));
  }, [props.obrasPromise]);

  async function searchPeople() {
    setBusy(true);
    setError("");
    try {
      if (!props.alfa && !obraId) {
        throw new Error("Selecione uma obra para consultar participantes autorizados.");
      }
      const found = obraId
        ? mapWorksitePeople(await buscarColaboradoresDaObra(obraId))
        : mapGlobalPeople(await buscarColaboradores(query));
      setPeople(
        found.filter((person) =>
          person.nome.toLocaleLowerCase("pt-BR").includes(query.trim().toLocaleLowerCase("pt-BR")),
        ),
      );
    } catch (cause: unknown) {
      setError(messageFrom(cause));
    } finally {
      setBusy(false);
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      if (type === "DIRETA" && selectedPeople.length !== 1) {
        throw new Error("Selecione uma pessoa para a conversa direta.");
      }
      if (type === "GRUPO" && (!title.trim() || selectedPeople.length < 1)) {
        throw new Error("Informe o nome do grupo e selecione ao menos uma pessoa.");
      }
      if (type === "OBRA" && !obraId) {
        throw new Error("Selecione a obra da conversa.");
      }
      const created = await createConversationApi({
        tipo: type,
        titulo: type === "DIRETA" ? null : title,
        obraId: type === "OBRA" ? obraId : null,
        participanteIds: selectedPeople,
      });
      await props.onCreated(created);
    } catch (cause: unknown) {
      setError(messageFrom(cause));
      setBusy(false);
    }
  }

  return (
    <div className="mensagens-dialog-backdrop" role="presentation">
      <section
        className="mensagens-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="nova-conversa-title"
      >
        <header>
          <div>
            <p className="eyebrow">Participantes autorizados</p>
            <h2 id="nova-conversa-title">Nova conversa</h2>
          </div>
          <button type="button" onClick={props.onClose} aria-label="Fechar">×</button>
        </header>
        <form onSubmit={submit}>
          <label>
            Tipo
            <select
              value={type}
              onChange={(event: ChangeEvent<HTMLSelectElement>) => {
                setType(event.target.value as ConversaTipo);
                setSelectedPeople([]);
              }}
            >
              {CREATE_TYPES.map((option) => (
                <option key={option.value} value={option.value}>{option.label}</option>
              ))}
            </select>
          </label>
          {type !== "DIRETA" ? (
            <label>
              Nome da conversa
              <input value={title} onChange={(event) => setTitle(event.target.value)} />
            </label>
          ) : null}
          <label>
            {type === "OBRA" ? "Obra" : "Obra usada para consultar pessoas"}
            <select value={obraId} onChange={(event) => setObraId(event.target.value)}>
              <option value="">{props.alfa ? "Catálogo global" : "Selecione"}</option>
              {obras.map((obra) => (
                <option key={obra.id} value={obra.id}>{obra.nome}</option>
              ))}
            </select>
          </label>
          <div className="mensagens-directory-search">
            <label>
              Buscar participante
              <input
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="Nome"
              />
            </label>
            <button type="button" onClick={() => void searchPeople()} disabled={busy}>
              Consultar
            </button>
          </div>
          <fieldset>
            <legend>Participantes</legend>
            {people.length === 0 ? (
              <p>Consulte o diretório autorizado para selecionar pessoas.</p>
            ) : (
              people.map((person) => (
                <label key={person.id} className="mensagens-person">
                  <input
                    type={type === "DIRETA" ? "radio" : "checkbox"}
                    name="participantes"
                    checked={selectedPeople.includes(person.id)}
                    onChange={() =>
                      setSelectedPeople((current) =>
                        type === "DIRETA"
                          ? [person.id]
                          : current.includes(person.id)
                            ? current.filter((id) => id !== person.id)
                            : [...current, person.id],
                      )
                    }
                  />
                  <span><strong>{person.nome}</strong><small>{person.detalhe}</small></span>
                </label>
              ))
            )}
          </fieldset>
          {error ? <p className="mensagens-form-error" role="alert">{error}</p> : null}
          <footer>
            <button type="button" onClick={props.onClose}>Cancelar</button>
            <button type="submit" className="mensagens-primary" disabled={busy}>
              {busy ? "Criando…" : "Criar conversa"}
            </button>
          </footer>
        </form>
      </section>
    </div>
  );
}

function mapWorksitePeople(values: ColaboradorDaObra[]): DirectoryPerson[] {
  return values.map((person) => ({
    id: person.id,
    nome: person.nome || "Colaborador",
    detalhe: person.nomePerfil || person.nomeGrupo || "Vínculo ativo",
  }));
}

function mapGlobalPeople(values: ColaboradorLookup[]): DirectoryPerson[] {
  return values
    .filter((person) => person.ativo)
    .map((person) => ({
      id: person.id,
      nome: person.nome || "Colaborador",
      detalhe: person.nomePerfil || person.nomeGrupo || "Ativo",
    }));
}
