import {
  useMemo,
  useId,
  useRef,
  useState,
  type KeyboardEvent,
} from "react";

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
  const [additionId, setAdditionId] = useState("");
  const [collaboratorQuery, setCollaboratorQuery] = useState("");
  const [isCatalogOpen, setIsCatalogOpen] = useState(false);
  const [activeCatalogIndex, setActiveCatalogIndex] = useState(0);
  const [newCollaboratorName, setNewCollaboratorName] = useState("");
  const catalogListId = useId();
  const newCollaboratorId = useId();
  const checkboxRefs = useRef<Array<HTMLInputElement | null>>([]);
  const existingIds = useMemo(
    () => new Set(draft.maoObra.map((row) => row.colaboradorId)),
    [draft.maoObra],
  );
  const additions = collaborators.filter(
    (collaborator) => !existingIds.has(collaborator.id),
  );
  const filteredAdditions = useMemo(() => {
    const normalizedQuery = collaboratorQuery
      .normalize("NFD")
      .replace(/\p{Diacritic}/gu, "")
      .trim()
      .toLocaleLowerCase("pt-BR");
    if (!normalizedQuery) return additions;
    return additions.filter((collaborator) =>
      [
        collaborator.nome,
        collaborator.codigoColaborador,
        collaborator.papelNaObra,
        collaborator.nomePerfil,
      ]
        .filter(Boolean)
        .join(" ")
        .normalize("NFD")
        .replace(/\p{Diacritic}/gu, "")
        .toLocaleLowerCase("pt-BR")
        .includes(normalizedQuery),
    );
  }, [additions, collaboratorQuery]);
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

  function addCollaborator(collaboratorId: string) {
    if (!collaboratorId) return;
    onChange(addAuthorizedWorker(draft, collaboratorId, collaborators));
    setAdditionId("");
    setCollaboratorQuery("");
    setIsCatalogOpen(false);
    setActiveCatalogIndex(0);
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

  function handleCatalogKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      if (filteredAdditions.length === 0) return;
      setIsCatalogOpen(true);
      setActiveCatalogIndex((current) => {
        const direction = event.key === "ArrowDown" ? 1 : -1;
        return (current + direction + filteredAdditions.length) %
          filteredAdditions.length;
      });
      return;
    }
    if (event.key === "Enter" && isCatalogOpen) {
      const collaborator = filteredAdditions[activeCatalogIndex];
      if (!collaborator) return;
      event.preventDefault();
      addCollaborator(collaborator.id);
      return;
    }
    if (event.key === "Escape") {
      setIsCatalogOpen(false);
    }
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

      <div className="rdo-workforce-controls">
        <div className="rdo-workforce-catalog-picker">
          <label htmlFor={`${catalogListId}-input`}>
            Buscar colaborador autorizado
          </label>
          <input
            id={`${catalogListId}-input`}
            role="combobox"
            aria-autocomplete="list"
            aria-expanded={isCatalogOpen}
            aria-controls={catalogListId}
            aria-activedescendant={
              isCatalogOpen && filteredAdditions[activeCatalogIndex]
                ? `${catalogListId}-${filteredAdditions[activeCatalogIndex].id}`
                : undefined
            }
            value={collaboratorQuery}
            placeholder="Nome, código ou papel na obra"
            disabled={Boolean(catalogUnavailableMessage)}
            onFocus={() => setIsCatalogOpen(true)}
            onChange={(event) => {
              setCollaboratorQuery(event.target.value);
              setAdditionId("");
              setActiveCatalogIndex(0);
              setIsCatalogOpen(true);
            }}
            onKeyDown={handleCatalogKeyDown}
          />
          {isCatalogOpen ? (
            <div
              id={catalogListId}
              className="rdo-workforce-catalog-results"
              role="listbox"
              aria-label="Colaboradores encontrados"
            >
              {filteredAdditions.length === 0 ? (
                <p>Nenhum colaborador autorizado encontrado.</p>
              ) : (
                filteredAdditions.map((collaborator, index) => {
                  const title = collaborator.nome?.trim() ||
                    collaborator.codigoColaborador?.trim() ||
                    "Colaborador sem nome";
                  const details = [
                    collaborator.codigoColaborador,
                    collaborator.papelNaObra,
                    collaborator.nomePerfil,
                  ].filter(Boolean).join(" · ");
                  return (
                    <button
                      key={collaborator.id}
                      id={`${catalogListId}-${collaborator.id}`}
                      type="button"
                      role="option"
                      aria-selected={additionId === collaborator.id}
                      className={
                        index === activeCatalogIndex
                          ? "rdo-workforce-catalog-option is-active"
                          : "rdo-workforce-catalog-option"
                      }
                      onMouseDown={(event) => event.preventDefault()}
                      onClick={() => {
                        setAdditionId(collaborator.id);
                        setCollaboratorQuery(title);
                        setIsCatalogOpen(false);
                      }}
                    >
                      <strong>{title}</strong>
                      <span>{details}</span>
                    </button>
                  );
                })
              )}
            </div>
          ) : null}
        </div>
        <button
          type="button"
          className="add-button"
          disabled={!additionId || Boolean(catalogUnavailableMessage)}
          onClick={() => addCollaborator(additionId)}
        >
          Adicionar à equipe
        </button>
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
          Adicionar
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
