import { useMemo, useState } from "react";

import { getLocalRdo } from "../../lib/db/rdoRepository";
import {
  createEmptyControleGeometrico,
  createEmptyEquipamento,
  createEmptyMaoObra,
  createEmptyMaterial,
  createEmptyRdo,
} from "./createEmptyRdo";
import type {
  ControleGeometricoDraft,
  EquipamentoDraft,
  MaoObraDraft,
  MaterialDraft,
  NumericInput,
  RdoDraft,
} from "./rdo.types";
import { useRdoLocalPersistence } from "./useRdoLocalPersistence";

interface RdoCreatePageProps {
  initialDraft: RdoDraft;
  isExisting: boolean;
  onBackToList: () => void;
  onSaved: () => void;
}

function parseNumericInput(value: string): NumericInput {
  return value === "" ? "" : Number(value);
}

function removeLocalId<T extends { localId: string }>(
  item: T,
): Omit<T, "localId"> {
  const { localId: _localId, ...payload } = item;

  return payload;
}

function buildPayload(draft: RdoDraft) {
  return {
    id: draft.id,
    obraId: draft.obraId,
    programacaoId: draft.programacaoId || null,
    numeroRdo: draft.numeroRdo,
    dataRdo: draft.dataRdo,
    turno: draft.turno,
    horaInicio: draft.horaInicio || null,
    horaFim: draft.horaFim || null,
    condicaoManha: draft.condicaoManha || null,
    condicaoTarde: draft.condicaoTarde || null,
    condicaoNoite: draft.condicaoNoite || null,
    pluviometriaMm:
      draft.pluviometriaMm === ""
        ? null
        : draft.pluviometriaMm,
    observacoes: draft.observacoes,
    maoObra: draft.maoObra.map(removeLocalId),
    equipamentos:
      draft.equipamentos.map(removeLocalId),
    materiais: draft.materiais.map(removeLocalId),
    controlesGeometricos:
      draft.controlesGeometricos.map(removeLocalId),
  };
}

export function RdoCreatePage({
  initialDraft,
  isExisting,
  onBackToList,
  onSaved,
}: RdoCreatePageProps) {
  const [draft, setDraft] = useState<RdoDraft>(
    () => initialDraft,
  );

  const [showJson, setShowJson] = useState(false);
  const [notice, setNotice] = useState("");

  const {
    isSaving,
    isSyncing,
    message: persistenceMessage,
    error: persistenceError,
    saveLocally,
    synchronize,
  } = useRdoLocalPersistence();

  const payload = useMemo(
    () => buildPayload(draft),
    [draft],
  );

  function updateField<K extends keyof RdoDraft>(
    field: K,
    value: RdoDraft[K],
  ) {
    setDraft((current) => ({
      ...current,
      [field]: value,
    }));

    setNotice("");
  }

  function updateMaoObra(
    localId: string,
    patch: Partial<MaoObraDraft>,
  ) {
    setDraft((current) => ({
      ...current,
      maoObra: current.maoObra.map((item) =>
        item.localId === localId
          ? { ...item, ...patch }
          : item,
      ),
    }));
  }

  function updateEquipamento(
    localId: string,
    patch: Partial<EquipamentoDraft>,
  ) {
    setDraft((current) => ({
      ...current,
      equipamentos: current.equipamentos.map((item) =>
        item.localId === localId
          ? { ...item, ...patch }
          : item,
      ),
    }));
  }

  function updateMaterial(
    localId: string,
    patch: Partial<MaterialDraft>,
  ) {
    setDraft((current) => ({
      ...current,
      materiais: current.materiais.map((item) =>
        item.localId === localId
          ? { ...item, ...patch }
          : item,
      ),
    }));
  }

  function updateControle(
    localId: string,
    patch: Partial<ControleGeometricoDraft>,
  ) {
    setDraft((current) => ({
      ...current,
      controlesGeometricos:
        current.controlesGeometricos.map((item) =>
          item.localId === localId
            ? { ...item, ...patch }
            : item,
        ),
    }));
  }

  function removeCollectionItem(
    collection:
      | "maoObra"
      | "equipamentos"
      | "materiais"
      | "controlesGeometricos",
    localId: string,
  ) {
    setDraft((current) => ({
      ...current,
      [collection]: current[collection].filter(
        (item) => item.localId !== localId,
      ),
    }));
  }

  async function handleSaveLocally() {
    try {
      await saveLocally(draft);

      const persistedRdo = await getLocalRdo(draft.id);

      if (!persistedRdo) {
        throw new Error(
          "O RDO foi salvo, mas não pôde ser relido do IndexedDB.",
        );
      }

      setDraft((current) => ({
        ...current,
        syncStatus: persistedRdo.syncStatus,
      }));

      onSaved();
    } catch {
      // O hook já registra e exibe o erro.
    }
  }

  async function handleSyncNow() {
    try {
      await synchronize();
    } catch {
      // O hook já registra e exibe o erro.
      return;
    }

    const persistedRdo = await getLocalRdo(draft.id);

    if (persistedRdo) {
      setDraft((current) => ({
        ...current,
        syncStatus: persistedRdo.syncStatus,
      }));
    }
  }

  function handleReset() {
    const confirmed = window.confirm(
      isExisting
        ? "Descartar as alterações não salvas e restaurar este RDO?"
        : "Limpar todos os campos deste RDO?",
    );

    if (!confirmed) {
      return;
    }

    setDraft(
      isExisting
        ? initialDraft
        : createEmptyRdo(),
    );

    setNotice("");
    setShowJson(false);
  }

  return (
    <main className="page-shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">
            Córtex · Operação de campo
          </p>

          <h1>
            {isExisting
              ? "Editar Relatório Diário de Obra"
              : "Novo Relatório Diário de Obra"}
          </h1>

          <p className="subtitle">
            Registre e continue editando o relatório mesmo
            sem conexão com a internet.
          </p>
        </div>

        <div className="workspace-actions">
          <button
            type="button"
            className="secondary-button"
            onClick={onBackToList}
            disabled={isSaving || isSyncing}
          >
            Voltar aos RDOs locais
          </button>

          <div className="status-panel">
            <span className="status-label">
              Status local
            </span>

            <strong className="status-badge">
              {draft.syncStatus}
            </strong>

            <span className="status-id">
              ID: {draft.id.slice(0, 8)}
            </span>
          </div>
        </div>
      </header>

      {notice && (
        <div className="notice">
          {notice}
        </div>
      )}

      {persistenceMessage && (
        <div className="notice">
          {persistenceMessage}
        </div>
      )}

      {persistenceError && (
        <div className="notice notice-error">
          {persistenceError}
        </div>
      )}

      <section className="form-card">
        <div className="section-heading">
          <div>
            <span className="section-number">
              01
            </span>
            <h2>Identificação</h2>
          </div>

          <p>
            Dados que vinculam o RDO à obra e à
            programação.
          </p>
        </div>

        <div className="form-grid">
          <label>
            Obra ID
            <input
              value={draft.obraId}
              onChange={(event) =>
                updateField(
                  "obraId",
                  event.target.value,
                )
              }
              placeholder="UUID da obra"
            />

            <small>
              Depois será substituído por seleção carregada
              da API.
            </small>
          </label>

          <label>
            Programação ID
            <input
              value={draft.programacaoId}
              onChange={(event) =>
                updateField(
                  "programacaoId",
                  event.target.value,
                )
              }
              placeholder="UUID da programação"
            />
          </label>

          <label>
            Número do RDO
            <input
              value={draft.numeroRdo}
              onChange={(event) =>
                updateField(
                  "numeroRdo",
                  event.target.value,
                )
              }
              placeholder="Ex.: RDO-2026-001"
            />
          </label>

          <label>
            Data
            <input
              type="date"
              value={draft.dataRdo}
              onChange={(event) =>
                updateField(
                  "dataRdo",
                  event.target.value,
                )
              }
            />
          </label>

          <label>
            Turno
            <select
              value={draft.turno}
              onChange={(event) =>
                updateField(
                  "turno",
                  event.target
                    .value as RdoDraft["turno"],
                )
              }
            >
              <option value="DIURNO">
                Diurno
              </option>
              <option value="NOTURNO">
                Noturno
              </option>
            </select>
          </label>

          <label>
            Hora inicial
            <input
              type="time"
              value={draft.horaInicio}
              onChange={(event) =>
                updateField(
                  "horaInicio",
                  event.target.value,
                )
              }
            />
          </label>

          <label>
            Hora final
            <input
              type="time"
              value={draft.horaFim}
              onChange={(event) =>
                updateField(
                  "horaFim",
                  event.target.value,
                )
              }
            />
          </label>
        </div>
      </section>

      <section className="form-card">
        <div className="section-heading">
          <div>
            <span className="section-number">
              02
            </span>
            <h2>Condições do dia</h2>
          </div>

          <p>
            Condições climáticas e interferências
            operacionais.
          </p>
        </div>

        <div className="form-grid">
          {[
            ["condicaoManha", "Manhã"],
            ["condicaoTarde", "Tarde"],
            ["condicaoNoite", "Noite"],
          ].map(([field, label]) => (
            <label key={field}>
              {label}

              <select
                value={
                  draft[
                    field as keyof RdoDraft
                  ] as string
                }
                onChange={(event) =>
                  updateField(
                    field as
                      | "condicaoManha"
                      | "condicaoTarde"
                      | "condicaoNoite",
                    event.target.value as RdoDraft[
                      | "condicaoManha"
                      | "condicaoTarde"
                      | "condicaoNoite"
                    ],
                  )
                }
              >
                <option value="">
                  Selecione
                </option>
                <option value="BOM">
                  Bom
                </option>
                <option value="NUBLADO">
                  Nublado
                </option>
                <option value="CHUVA">
                  Chuva
                </option>
                <option value="IMPOSSIBILITADO">
                  Trabalho impossibilitado
                </option>
                <option value="NAO_APLICAVEL">
                  Não aplicável
                </option>
              </select>
            </label>
          ))}

          <label>
            Pluviometria (mm)
            <input
              type="number"
              min="0"
              step="0.1"
              value={draft.pluviometriaMm}
              onChange={(event) =>
                updateField(
                  "pluviometriaMm",
                  parseNumericInput(
                    event.target.value,
                  ),
                )
              }
            />
          </label>
        </div>

        <label className="full-width">
          Observações
          <textarea
            rows={5}
            value={draft.observacoes}
            onChange={(event) =>
              updateField(
                "observacoes",
                event.target.value,
              )
            }
            placeholder="Interferências, ocorrências, paralisações e informações relevantes."
          />
        </label>
      </section>

      <section className="form-card">
        <CollectionHeader
          number="03"
          title="Mão de obra"
          description="Colaboradores e equipes envolvidos no serviço."
          onAdd={() =>
            setDraft((current) => ({
              ...current,
              maoObra: [
                ...current.maoObra,
                createEmptyMaoObra(),
              ],
            }))
          }
        />

        <div className="collection-list">
          {draft.maoObra.map((item, index) => (
            <div
              className="collection-row"
              key={item.localId}
            >
              <div className="row-title">
                <strong>
                  Registro {index + 1}
                </strong>

                <button
                  type="button"
                  className="danger-link"
                  onClick={() =>
                    removeCollectionItem(
                      "maoObra",
                      item.localId,
                    )
                  }
                >
                  Remover
                </button>
              </div>

              <div className="form-grid">
                <label>
                  Colaborador ID
                  <input
                    value={item.colaboradorId}
                    onChange={(event) =>
                      updateMaoObra(
                        item.localId,
                        {
                          colaboradorId:
                            event.target.value,
                        },
                      )
                    }
                  />
                </label>

                <label>
                  Nome
                  <input
                    value={item.nomeColaborador}
                    onChange={(event) =>
                      updateMaoObra(
                        item.localId,
                        {
                          nomeColaborador:
                            event.target.value,
                        },
                      )
                    }
                  />
                </label>

                <label>
                  Cargo
                  <input
                    value={item.cargo}
                    onChange={(event) =>
                      updateMaoObra(
                        item.localId,
                        {
                          cargo:
                            event.target.value,
                        },
                      )
                    }
                  />
                </label>

                <label>
                  Vínculo
                  <select
                    value={item.tipoVinculo}
                    onChange={(event) =>
                      updateMaoObra(
                        item.localId,
                        {
                          tipoVinculo:
                            event.target.value,
                        },
                      )
                    }
                  >
                    <option value="CONTRATADO">
                      Contratado
                    </option>
                    <option value="PROPRIO">
                      Próprio
                    </option>
                    <option value="TERCEIRIZADO">
                      Terceirizado
                    </option>
                  </select>
                </label>

                <label>
                  Quantidade
                  <input
                    type="number"
                    min="0"
                    value={item.quantidade}
                    onChange={(event) =>
                      updateMaoObra(
                        item.localId,
                        {
                          quantidade:
                            parseNumericInput(
                              event.target.value,
                            ),
                        },
                      )
                    }
                  />
                </label>
              </div>
            </div>
          ))}
        </div>
      </section>

      <section className="form-card">
        <CollectionHeader
          number="04"
          title="Equipamentos"
          description="Equipamentos utilizados durante a execução."
          onAdd={() =>
            setDraft((current) => ({
              ...current,
              equipamentos: [
                ...current.equipamentos,
                createEmptyEquipamento(),
              ],
            }))
          }
        />

        <div className="collection-list">
          {draft.equipamentos.map(
            (item, index) => (
              <div
                className="collection-row"
                key={item.localId}
              >
                <div className="row-title">
                  <strong>
                    Equipamento {index + 1}
                  </strong>

                  <button
                    type="button"
                    className="danger-link"
                    onClick={() =>
                      removeCollectionItem(
                        "equipamentos",
                        item.localId,
                      )
                    }
                  >
                    Remover
                  </button>
                </div>

                <div className="form-grid">
                  <label>
                    Asset ID
                    <input
                      value={item.assetId}
                      onChange={(event) =>
                        updateEquipamento(
                          item.localId,
                          {
                            assetId:
                              event.target.value,
                          },
                        )
                      }
                    />
                  </label>

                  <label>
                    Prefixo
                    <input
                      value={item.prefixo}
                      onChange={(event) =>
                        updateEquipamento(
                          item.localId,
                          {
                            prefixo:
                              event.target.value,
                          },
                        )
                      }
                    />
                  </label>

                  <label>
                    Descrição
                    <input
                      value={item.descricao}
                      onChange={(event) =>
                        updateEquipamento(
                          item.localId,
                          {
                            descricao:
                              event.target.value,
                          },
                        )
                      }
                    />
                  </label>

                  <label>
                    Tipo
                    <input
                      value={item.tipoEquipamento}
                      onChange={(event) =>
                        updateEquipamento(
                          item.localId,
                          {
                            tipoEquipamento:
                              event.target.value,
                          },
                        )
                      }
                    />
                  </label>

                  <label>
                    Vínculo
                    <select
                      value={item.tipoVinculo}
                      onChange={(event) =>
                        updateEquipamento(
                          item.localId,
                          {
                            tipoVinculo:
                              event.target.value,
                          },
                        )
                      }
                    >
                      <option value="PROPRIO">
                        Próprio
                      </option>
                      <option value="LOCADO">
                        Locado
                      </option>
                      <option value="TERCEIRIZADO">
                        Terceirizado
                      </option>
                    </select>
                  </label>

                  <label>
                    Quantidade
                    <input
                      type="number"
                      min="0"
                      value={item.quantidade}
                      onChange={(event) =>
                        updateEquipamento(
                          item.localId,
                          {
                            quantidade:
                              parseNumericInput(
                                event.target.value,
                              ),
                          },
                        )
                      }
                    />
                  </label>
                </div>
              </div>
            ),
          )}
        </div>
      </section>

      <section className="form-card">
        <CollectionHeader
          number="05"
          title="Materiais"
          description="Materiais previstos, usinados e aplicados."
          onAdd={() =>
            setDraft((current) => ({
              ...current,
              materiais: [
                ...current.materiais,
                createEmptyMaterial(),
              ],
            }))
          }
        />

        <div className="collection-list">
          {draft.materiais.map(
            (item, index) => (
              <div
                className="collection-row"
                key={item.localId}
              >
                <div className="row-title">
                  <strong>
                    Material {index + 1}
                  </strong>

                  <button
                    type="button"
                    className="danger-link"
                    onClick={() =>
                      removeCollectionItem(
                        "materiais",
                        item.localId,
                      )
                    }
                  >
                    Remover
                  </button>
                </div>

                <div className="form-grid">
                  <label>
                    Material
                    <input
                      value={item.materialNome}
                      onChange={(event) =>
                        updateMaterial(
                          item.localId,
                          {
                            materialNome:
                              event.target.value,
                          },
                        )
                      }
                    />
                  </label>

                  <label>
                    Unidade
                    <input
                      value={item.unidade}
                      onChange={(event) =>
                        updateMaterial(
                          item.localId,
                          {
                            unidade:
                              event.target.value,
                          },
                        )
                      }
                      placeholder="t, m³, kg..."
                    />
                  </label>

                  <NumericField
                    label="Quantidade prevista"
                    value={
                      item.quantidadePrevista
                    }
                    onChange={(value) =>
                      updateMaterial(
                        item.localId,
                        {
                          quantidadePrevista:
                            value,
                        },
                      )
                    }
                  />

                  <NumericField
                    label="Quantidade usinada"
                    value={
                      item.quantidadeUsinada
                    }
                    onChange={(value) =>
                      updateMaterial(
                        item.localId,
                        {
                          quantidadeUsinada:
                            value,
                        },
                      )
                    }
                  />

                  <NumericField
                    label="Quantidade aplicada"
                    value={
                      item.quantidadeAplicada
                    }
                    onChange={(value) =>
                      updateMaterial(
                        item.localId,
                        {
                          quantidadeAplicada:
                            value,
                        },
                      )
                    }
                  />
                </div>
              </div>
            ),
          )}
        </div>
      </section>

      <section className="form-card">
        <CollectionHeader
          number="06"
          title="Controle geométrico"
          description="Medições e dimensões dos trechos executados."
          onAdd={() =>
            setDraft((current) => ({
              ...current,
              controlesGeometricos: [
                ...current.controlesGeometricos,
                createEmptyControleGeometrico(),
              ],
            }))
          }
        />

        <div className="collection-list">
          {draft.controlesGeometricos.map(
            (item, index) => (
              <div
                className="collection-row"
                key={item.localId}
              >
                <div className="row-title">
                  <strong>
                    Trecho {index + 1}
                  </strong>

                  <button
                    type="button"
                    className="danger-link"
                    onClick={() =>
                      removeCollectionItem(
                        "controlesGeometricos",
                        item.localId,
                      )
                    }
                  >
                    Remover
                  </button>
                </div>

                <div className="form-grid">
                  <label>
                    Subtrecho
                    <input
                      value={item.subtrecho}
                      onChange={(event) =>
                        updateControle(
                          item.localId,
                          {
                            subtrecho:
                              event.target.value,
                          },
                        )
                      }
                    />
                  </label>

                  <label>
                    KM inicial
                    <input
                      value={item.kmInicial}
                      onChange={(event) =>
                        updateControle(
                          item.localId,
                          {
                            kmInicial:
                              event.target.value,
                          },
                        )
                      }
                      placeholder="100.000"
                    />
                  </label>

                  <label>
                    KM final
                    <input
                      value={item.kmFinal}
                      onChange={(event) =>
                        updateControle(
                          item.localId,
                          {
                            kmFinal:
                              event.target.value,
                          },
                        )
                      }
                      placeholder="100.500"
                    />
                  </label>

                  <NumericField
                    label="Comprimento (m)"
                    value={item.comprimentoM}
                    onChange={(value) =>
                      updateControle(
                        item.localId,
                        {
                          comprimentoM: value,
                        },
                      )
                    }
                  />

                  <NumericField
                    label="Largura (m)"
                    value={item.larguraM}
                    onChange={(value) =>
                      updateControle(
                        item.localId,
                        {
                          larguraM: value,
                        },
                      )
                    }
                  />

                  <NumericField
                    label="Espessura 1 (cm)"
                    value={item.espessura1Cm}
                    onChange={(value) =>
                      updateControle(
                        item.localId,
                        {
                          espessura1Cm: value,
                        },
                      )
                    }
                  />

                  <NumericField
                    label="Espessura 2 (cm)"
                    value={item.espessura2Cm}
                    onChange={(value) =>
                      updateControle(
                        item.localId,
                        {
                          espessura2Cm: value,
                        },
                      )
                    }
                  />

                  <NumericField
                    label="Espessura 3 (cm)"
                    value={item.espessura3Cm}
                    onChange={(value) =>
                      updateControle(
                        item.localId,
                        {
                          espessura3Cm: value,
                        },
                      )
                    }
                  />

                  <NumericField
                    label="Densidade"
                    value={item.densidade}
                    onChange={(value) =>
                      updateControle(
                        item.localId,
                        {
                          densidade: value,
                        },
                      )
                    }
                  />
                </div>
              </div>
            ),
          )}
        </div>
      </section>

      {showJson && (
        <section className="form-card json-preview">
          <div className="section-heading">
            <div>
              <span className="section-number">
                JSON
              </span>
              <h2>Payload gerado</h2>
            </div>

            <p>
              Estrutura que será enviada ao mecanismo de
              sincronização.
            </p>
          </div>

          <pre>
            {JSON.stringify(
              payload,
              null,
              2,
            )}
          </pre>
        </section>
      )}

      <footer className="action-bar">
        <button
          type="button"
          className="button secondary"
          onClick={handleReset}
          disabled={isSaving || isSyncing}
        >
          {isExisting
            ? "Descartar alterações"
            : "Limpar"}
        </button>

        <button
          type="button"
          className="button secondary"
          onClick={() =>
            setShowJson(
              (current) => !current,
            )
          }
          disabled={isSaving || isSyncing}
        >
          {showJson
            ? "Ocultar JSON"
            : "Visualizar JSON"}
        </button>

        <button
          type="button"
          className="button secondary"
          onClick={handleSyncNow}
          disabled={isSaving || isSyncing}
        >
          {isSyncing
            ? "Sincronizando..."
            : "Sincronizar agora"}
        </button>

        <button
          type="button"
          className="button primary"
          onClick={handleSaveLocally}
          disabled={isSaving || isSyncing}
        >
          {isSaving
            ? "Salvando..."
            : "Salvar localmente"}
        </button>
      </footer>
    </main>
  );
}

interface CollectionHeaderProps {
  number: string;
  title: string;
  description: string;
  onAdd: () => void;
}

function CollectionHeader({
  number,
  title,
  description,
  onAdd,
}: CollectionHeaderProps) {
  return (
    <div className="section-heading collection-heading">
      <div>
        <span className="section-number">
          {number}
        </span>
        <h2>{title}</h2>
      </div>

      <div className="section-actions">
        <p>{description}</p>

        <button
          type="button"
          className="add-button"
          onClick={onAdd}
        >
          + Adicionar
        </button>
      </div>
    </div>
  );
}

interface NumericFieldProps {
  label: string;
  value: NumericInput;
  onChange: (value: NumericInput) => void;
}

function NumericField({
  label,
  value,
  onChange,
}: NumericFieldProps) {
  return (
    <label>
      {label}

      <input
        type="number"
        min="0"
        step="0.001"
        value={value}
        onChange={(event) =>
          onChange(
            parseNumericInput(
              event.target.value,
            ),
          )
        }
      />
    </label>
  );
}