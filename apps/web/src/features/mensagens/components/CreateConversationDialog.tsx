import { useEffect, useState, type ChangeEvent, type FormEvent } from "react";

import type {
  ConversaLocalRecord,
  ConversaTipo,
  ObraLocalRecord,
} from "../../../lib/db/db.types";
import { filterOperationalObras } from "../../../lib/db/obraSelectors";
import {
  buscarColaboradoresDaObra,
  type ColaboradorDaObra,
} from "../../rdos/rdoLookupApi";
import {
  buscarDiretorioDeMensagens,
  type DiretorioPessoa,
} from "../mensagensApi";
import { queueConversation } from "../mensagensRepository";
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
  onCreated: (conversation: ConversaLocalRecord) => Promise<void>;
}) {
  const [type, setType] = useState<ConversaTipo>("DIRETA");
  const [title, setTitle] = useState("");
  const [obras, setObras] = useState<ObraLocalRecord[]>([]);
  const [obraId, setObraId] = useState("");
  const [query, setQuery] = useState("");
  const [people, setPeople] = useState<DirectoryPerson[]>([]);
  /*
   * A escolha guarda o nome junto, e não só o id.
   *
   * O diretório é refeito a cada busca: quem foi escolhido numa consulta some
   * da lista na consulta seguinte, e procurar o nome ali na hora de gravar
   * devolvia vazio. A conversa nascia com participante sem nome até o servidor
   * confirmá-la.
   */
  const [selectedPeople, setSelectedPeople] = useState<
    { colaboradorId: string; nome: string }[]
  >([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    void props.obrasPromise
      .then(filterOperationalObras)
      .then(setObras)
      .catch((cause) => setError(messageFrom(cause)));
  }, [props.obrasPromise]);

  async function searchPeople() {
    setBusy(true);
    setError("");
    try {
      // A obra restringe a busca quando alguém quer justamente a turma dela;
      // sem obra escolhida, procura-se na empresa inteira. Antes, quem não era
      // Alfa era obrigado a escolher uma obra e só encontrava quem estivesse
      // vinculado a ela — duas pessoas da mesma empresa em frentes diferentes
      // não se achavam, e mensagem é o que menos deveria depender disso.
      const found = obraId
        ? mapWorksitePeople(await buscarColaboradoresDaObra(obraId))
        : mapDirectoryPeople(await buscarDiretorioDeMensagens(query));
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
      // A conversa nasce no aparelho e sobe depois, com ou sem rede: as
      // exigências de cada tipo são conferidas na própria montagem.
      const created = await queueConversation({
        tipo: type,
        titulo: type === "DIRETA" ? null : title,
        obraId: type === "OBRA" ? obraId : null,
        equipeId: null,
        participantes: selectedPeople,
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
            {type === "OBRA" ? "Obra" : "Filtrar pessoas por obra (opcional)"}
            <select value={obraId} onChange={(event) => setObraId(event.target.value)}>
              <option value="">Todas as pessoas</option>
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
                    checked={selectedPeople.some(
                      (escolhida) => escolhida.colaboradorId === person.id,
                    )}
                    onChange={() =>
                      setSelectedPeople((current) => {
                        const escolhida = {
                          colaboradorId: person.id,
                          nome: person.nome,
                        };
                        if (type === "DIRETA") return [escolhida];
                        return current.some(
                          (item) => item.colaboradorId === person.id,
                        )
                          ? current.filter(
                            (item) => item.colaboradorId !== person.id,
                          )
                          : [...current, escolhida];
                      })
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

function mapDirectoryPeople(values: DiretorioPessoa[]): DirectoryPerson[] {
  return values.map((person) => ({
    id: person.id,
    nome: person.nome || "Colaborador",
    detalhe: person.nomePerfil || "Ativo",
  }));
}
