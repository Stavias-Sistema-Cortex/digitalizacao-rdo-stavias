import {
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
} from "react";

import {
  addAuthorizedWorker,
  nextRosterFocusIndex,
  setRosterApontador,
  setRosterSelected,
} from "./rdoCreationContext";
import type { MaoObraDraft, RdoDraft } from "./rdo.types";
import type { RdoContextCollaborator } from "./rdoLookupApi";

import "./RdoWorkforceEditor.css";

interface RdoWorkforceEditorProps {
  draft: RdoDraft;
  collaborators: readonly RdoContextCollaborator[];
  sourceRdoNumber: string | null;
  onChange: (draft: RdoDraft) => void;
}

export function RdoWorkforceEditor({
  draft,
  collaborators,
  sourceRdoNumber,
  onChange,
}: RdoWorkforceEditorProps) {
  const [additionId, setAdditionId] = useState("");
  const checkboxRefs = useRef<Array<HTMLInputElement | null>>([]);
  const existingIds = useMemo(
    () => new Set(draft.maoObra.map((row) => row.colaboradorId)),
    [draft.maoObra],
  );
  const additions = collaborators.filter(
    (collaborator) => !existingIds.has(collaborator.id),
  );
  const selected = draft.maoObra.filter(
    (row) => row.selected && row.availability !== "UNAVAILABLE",
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
        <label>
          Adicionar colaborador autorizado
          <select
            value={additionId}
            onChange={(event) => setAdditionId(event.target.value)}
          >
            <option value="">Selecione</option>
            {additions.map((collaborator) => (
              <option key={collaborator.id} value={collaborator.id}>
                {collaborator.nome || collaborator.codigoColaborador}
              </option>
            ))}
          </select>
        </label>
        <button
          type="button"
          className="add-button"
          disabled={!additionId}
          onClick={() => {
            onChange(
              addAuthorizedWorker(draft, additionId, collaborators),
            );
            setAdditionId("");
          }}
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
