import {
  useMemo,
  useId,
  useRef,
  useState,
  type KeyboardEvent,
} from "react";

import { DualListbox, type DualListboxItem } from "../../components/DualListbox";
import {
  addAuthorizedWorker,
  nextRosterFocusIndex,
  removeRosterMember,
  setRosterApontador,
  setRosterSelected,
} from "./rdoCreationContext";
import { createEmptyMaoObra } from "./createEmptyRdo";
import type { MaoObraDraft, RdoDraft } from "./rdo.types";
import type { RdoContextCollaborator } from "./rdoLookupApi";

import "./RdoWorkforceEditor.css";

interface RdoWorkforceEditorProps {
  draft: RdoDraft;
  collaborators: readonly RdoContextCollaborator[];
  catalogUnavailableMessage?: string;
  sourceRdoNumber: string | null;
  onChange: (draft: RdoDraft) => void;
}

export function RdoWorkforceEditor({
  draft,
  collaborators,
  catalogUnavailableMessage,
  sourceRdoNumber,
  onChange,
}: RdoWorkforceEditorProps) {
  const [newCollaboratorName, setNewCollaboratorName] = useState("");
  const newCollaboratorId = useId();
  const checkboxRefs = useRef<Array<HTMLInputElement | null>>([]);
  const existingIds = useMemo(
    () => new Set(draft.maoObra.map((row) => row.colaboradorId)),
    [draft.maoObra],
  );
  const disponiveis = useMemo<DualListboxItem[]>(
    () =>
      collaborators
        .filter((collaborator) => !existingIds.has(collaborator.id))
        .map((collaborator) => ({
          id: collaborator.id,
          titulo: collaborator.nome?.trim() ||
            collaborator.codigoColaborador?.trim() ||
            "Colaborador sem nome",
          detalhe: [
            collaborator.codigoColaborador,
            collaborator.papelNaObra,
            collaborator.nomePerfil,
          ].filter(Boolean).join(" · "),
        })),
    [collaborators, existingIds],
  );
  /*
   * O lado escolhido é indexado pelo localId, não pelo id do colaborador: quem
   * foi somado à mão não tem cadastro, e sem localId sairia de vista sem sair
   * do RDO.
   */
  const escolhidos = useMemo<DualListboxItem[]>(
    () =>
      draft.maoObra.map((row) => ({
        id: row.localId,
        titulo: row.nomeColaborador || row.colaboradorId ||
          "Colaborador sem nome",
        detalhe: row.availability === "UNAVAILABLE"
          ? "Indisponível"
          : row.cargo,
      })),
    [draft.maoObra],
  );
  const selected = draft.maoObra.filter(
    (row) =>
      row.selected &&
      row.availability !== "UNAVAILABLE" &&
      row.colaboradorId.trim(),
  );

  function updateRow(localId: string, patch: Partial<MaoObraDraft>) {
    onChange({
      ...draft,
      maoObra: draft.maoObra.map((row) =>
        row.localId === localId ? { ...row, ...patch } : row,
      ),
    });
  }

  function handleRosterKeyDown(
    event: KeyboardEvent<HTMLInputElement>,
    index: number,
  ) {
    if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) {
      return;
    }
    event.preventDefault();
    const next = nextRosterFocusIndex(event.key, index, draft.maoObra.length);
    checkboxRefs.current[next]?.focus();
  }

  /*
   * A frente inteira entra de uma vez. Dobrar as adições sobre o mesmo rascunho
   * — em vez de um onChange por pessoa — evita que o pai perca as anteriores ao
   * reagir só à última.
   */
  function addCollaborators(collaboratorIds: readonly string[]) {
    const proximo = collaboratorIds.reduce(
      (acumulado, collaboratorId) =>
        collaboratorId
          ? addAuthorizedWorker(acumulado, collaboratorId, collaborators)
          : acumulado,
      draft,
    );
    if (proximo !== draft) onChange(proximo);
  }

  function removeCollaborators(localIds: readonly string[]) {
    const proximo = localIds.reduce(
      (acumulado, localId) => removeRosterMember(acumulado, localId),
      draft,
    );
    if (proximo !== draft) onChange(proximo);
  }

  function addNewCollaborator() {
    const normalizedName = newCollaboratorName.trim().replace(/\s+/g, " ");
    if (!normalizedName) return;

    onChange({
      ...draft,
      maoObra: [
        ...draft.maoObra,
        {
          ...createEmptyMaoObra(),
          nomeColaborador: normalizedName,
          availability: "AVAILABLE",
        },
      ],
    });
    setNewCollaboratorName("");
  }

  return (
    <section
      className="form-card rdo-workforce-editor"
      aria-labelledby="rdo-workforce-title"
    >
      <div className="section-heading collection-heading">
        <div>
          <h2 id="rdo-workforce-title">Mão de obra</h2>
        </div>
        <p>
          {sourceRdoNumber
            ? `Importada do RDO ${sourceRdoNumber}`
            : "Nenhum RDO anterior elegível; adicione colaboradores autorizados."}
        </p>
      </div>

      <DualListbox
        rotulo="Colaboradores do RDO"
        disponiveis={disponiveis}
        escolhidos={escolhidos}
        onEscolher={addCollaborators}
        onRemover={removeCollaborators}
        tituloDisponiveis="Autorizados na obra"
        tituloEscolhidos="Na equipe do RDO"
        desabilitado={Boolean(catalogUnavailableMessage)}
        mensagemSemDisponiveis={
          catalogUnavailableMessage
            ? "Nenhum colaborador autorizado carregado."
            : "Todo mundo autorizado nesta obra já está na equipe."
        }
        mensagemSemEscolhidos="Nenhum trabalhador neste RDO ainda."
      />

      <div className="rdo-workforce-controls">
        <label>
          Apontador do RDO
          <select
            value={draft.apontadorColaboradorId}
            onChange={(event) =>
              onChange(setRosterApontador(draft, event.target.value))
            }
          >
            <option value="">Sem apontador</option>
            {selected.map((row) => (
              <option key={row.colaboradorId} value={row.colaboradorId}>
                {row.nomeColaborador || row.colaboradorId}
              </option>
            ))}
          </select>
        </label>
      </div>

      <form
        className="rdo-workforce-manual-add"
        onSubmit={(event) => {
          event.preventDefault();
          addNewCollaborator();
        }}
      >
        <label htmlFor={newCollaboratorId}>
          Adicionar trabalhador ao RDO
        </label>
        <input
          id={newCollaboratorId}
          maxLength={255}
          value={newCollaboratorName}
          onChange={(event) => setNewCollaboratorName(event.target.value)}
        />
        <button
          type="submit"
          className="add-button"
          disabled={!newCollaboratorName.trim()}
        >
          Adicionar trabalhador
        </button>
      </form>

      {catalogUnavailableMessage ? (
        <p className="rdo-workforce-catalog-unavailable" role="status">
          {catalogUnavailableMessage}
        </p>
      ) : null}

      {draft.maoObra.length === 0 ? (
        <p className="rdo-workforce-empty">
          Nenhum trabalhador foi carregado para este RDO.
        </p>
      ) : (
        <div className="rdo-workforce-table-region">
          <table className="rdo-workforce-table">
            <thead>
              <tr>
                <th scope="col">Incluir</th>
                <th scope="col">Colaborador</th>
                <th scope="col">Função</th>
                <th scope="col">Vínculo</th>
                <th scope="col">Qtd.</th>
                <th scope="col">Início</th>
                <th scope="col">Fim</th>
                <th scope="col">Observações</th>
                <th scope="col">Ações</th>
              </tr>
            </thead>
            <tbody>
              {draft.maoObra.map((row, index) => {
                const unavailable = row.availability === "UNAVAILABLE";
                return (
                  <tr
                    key={row.localId}
                    className={row.origin === "PREVIOUS_RDO" ? "rdo-workforce-row--carried" : undefined}
                  >
                    <td>
                      <input
                        ref={(element) => {
                          checkboxRefs.current[index] = element;
                        }}
                        type="checkbox"
                        checked={row.selected}
                        disabled={unavailable}
                        aria-label={`Selecionar ${row.nomeColaborador || row.colaboradorId}`}
                        onKeyDown={(event) => handleRosterKeyDown(event, index)}
                        onChange={(event) =>
                          onChange(
                            setRosterSelected(draft, row.localId, event.target.checked),
                          )
                        }
                      />
                    </td>
                    <th scope="row">
                      <span>{row.nomeColaborador || row.colaboradorId}</span>
                      {unavailable ? <small>Indisponível</small> : null}
                    </th>
                    <td>
                      <input
                        aria-label={`Função de ${row.nomeColaborador}`}
                        value={row.cargo}
                        disabled={unavailable}
                        onChange={(event) => updateRow(row.localId, { cargo: event.target.value })}
                      />
                    </td>
                    <td>
                      <select
                        aria-label={`Vínculo de ${row.nomeColaborador}`}
                        value={row.tipoVinculo}
                        disabled={unavailable}
                        onChange={(event) => updateRow(row.localId, { tipoVinculo: event.target.value })}
                      >
                        <option value="">Selecione</option>
                        <option value="PROPRIO">Próprio</option>
                        <option value="CONTRATADO">Contratado</option>
                        <option value="TERCEIRIZADO">Terceirizado</option>
                      </select>
                    </td>
                    <td>
                      <input
                        type="number"
                        min="0"
                        step="1"
                        aria-label={`Quantidade de ${row.nomeColaborador}`}
                        value={row.quantidade}
                        disabled={unavailable}
                        onChange={(event) =>
                          updateRow(row.localId, {
                            quantidade:
                              event.target.value === ""
                                ? ""
                                : Number(event.target.value),
                          })
                        }
                      />
                    </td>
                    <td>
                      <input
                        type="time"
                        aria-label={`Início de ${row.nomeColaborador}`}
                        value={row.horaInicio}
                        disabled={unavailable}
                        onChange={(event) => updateRow(row.localId, { horaInicio: event.target.value })}
                      />
                    </td>
                    <td>
                      <input
                        type="time"
                        aria-label={`Fim de ${row.nomeColaborador}`}
                        value={row.horaFim}
                        disabled={unavailable}
                        onChange={(event) => updateRow(row.localId, { horaFim: event.target.value })}
                      />
                    </td>
                    <td>
                      <input
                        aria-label={`Observações de ${row.nomeColaborador}`}
                        value={row.observacoes}
                        disabled={unavailable}
                        onChange={(event) => updateRow(row.localId, { observacoes: event.target.value })}
                      />
                    </td>
                    <td>
                      <button
                        type="button"
                        className="rdo-workforce-remove"
                        aria-label={`Remover ${row.nomeColaborador || row.colaboradorId} da equipe`}
                        onClick={() =>
                          onChange(removeRosterMember(draft, row.localId))
                        }
                      >
                        Remover
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
