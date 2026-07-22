import {
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
} from "react";

import {
  listRdoAttachments,
  markRdoAttachmentRemoved,
  putRdoAttachment,
} from "../../lib/db/rdoAttachmentRepository";
import { addOperationalEvent } from "../../lib/db/operationalEventRepository";
import { getLocalRdo } from "../../lib/db/rdoRepository";
import { formatLocalSyncStatus } from "../../lib/db/syncStatusLabels";
import { buildRdoSyncPayload } from "../../lib/db/localRdoService";
import type { RdoAttachmentRecord } from "../../lib/db/db.types";
import {
  createEmptyAlocacaoColaborador,
  createEmptyControleGeometrico,
  createEmptyEquipamento,
  createEmptyMaoObra,
  createEmptyMaterial,
  createEmptyRdo,
  createEmptyServicoExecutado,
} from "./createEmptyRdo";
import type {
  AlocacaoColaboradorDraft,
  ControleGeometricoDraft,
  EquipamentoDraft,
  MaoObraDraft,
  MaterialDraft,
  NumericInput,
  RdoAttachmentDraft,
  RdoDraft,
  ServicoExecutadoDraft,
} from "./rdo.types";
import { processRdoPhoto } from "./rdoPhotoService";
import {
  buscarAssets,
  buscarColaboradores,
  type AssetLookup,
  type ColaboradorLookup,
} from "./rdoLookupApi";
import {
  formatRdoServiceType,
  searchRdoServiceTypes,
  type RdoServiceType,
} from "./rdoServiceTypes";
import {
  calcularControleGeometrico,
  calcularSobraMaterial,
  formatCalculatedNumber,
} from "./rdoCalculations";
import { useRdoLocalPersistence } from "./useRdoLocalPersistence";
import { UNIDADES_RDO, normalizarUnidade } from "./unidades";

interface RdoCreatePageProps {
  initialDraft: RdoDraft;
  isExisting: boolean;
  initialNotice?: string;
  onBackToList: () => void;
  onSaved: (savedObraId: string) => void;
}

function parseNumericInput(value: string): NumericInput {
  return value === "" ? "" : Number(value);
}

function attachmentToDraft(
  attachment: RdoAttachmentRecord,
): RdoAttachmentDraft {
  return {
    id: attachment.id,
    rdoId: attachment.rdoId,
    obraId: attachment.obraId,
    tipo: "FOTO",
    nome: attachment.nome,
    nomeOriginal: attachment.nomeOriginal,
    mimeType: attachment.mimeType,
    tamanhoOriginalBytes: attachment.tamanhoOriginalBytes,
    tamanhoComprimidoBytes:
      attachment.tamanhoComprimidoBytes,
    tamanhoBytes: attachment.tamanhoBytes,
    syncStatus: attachment.syncStatus,
    createdAt: attachment.createdAt,
    updatedAt: attachment.updatedAt,
    removedAt: attachment.removedAt,
    metadata: attachment.metadata,
  };
}

function fileSizeLabel(size: number): string {
  if (size < 1024) {
    return `${size} B`;
  }

  if (size < 1024 * 1024) {
    return `${(size / 1024).toFixed(1)} KB`;
  }

  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}

function parseKm(value: string): number | null {
  const parsed = Number(value.trim().replace(",", "."));
  return Number.isFinite(parsed) ? parsed : null;
}

function extensionMeters(
  start: string,
  end: string,
): number | null {
  const startKm = parseKm(start);
  const endKm = parseKm(end);

  if (startKm === null || endKm === null || endKm < startKm) {
    return null;
  }

  return Math.round((endKm - startKm) * 1000 * 1000) / 1000;
}

interface LookupFieldProps<TItem> {
  label: string;
  value: string;
  placeholder: string;
  emptyMessage: string;
  search: (query: string) => Promise<TItem[]>;
  onQueryChange: (value: string) => void;
  onSelect: (item: TItem) => void;
  getKey: (item: TItem) => string;
  getTitle: (item: TItem) => string;
  getSubtitle: (item: TItem) => string;
}

function LookupField<TItem>({
  label,
  value,
  placeholder,
  emptyMessage,
  search,
  onQueryChange,
  onSelect,
  getKey,
  getTitle,
  getSubtitle,
}: LookupFieldProps<TItem>) {
  const inputId = useId();
  const [isOpen, setIsOpen] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [items, setItems] = useState<TItem[]>([]);
  const [error, setError] = useState<string | null>(null);
  const searchTimeoutRef = useRef<number | null>(null);
  const blurTimeoutRef = useRef<number | null>(null);
  const requestIdRef = useRef(0);

  const runSearch = (query: string) => {
    const requestId = requestIdRef.current + 1;
    requestIdRef.current = requestId;
    setIsLoading(true);
    setError(null);

    search(query)
      .then((results) => {
        if (requestIdRef.current !== requestId) {
          return;
        }

        setItems(results);
      })
      .catch((unknownError) => {
        if (requestIdRef.current !== requestId) {
          return;
        }

        setItems([]);
        setError(
          unknownError instanceof Error
            ? unknownError.message
            : "Nao foi possivel carregar a lista.",
        );
      })
      .finally(() => {
        if (requestIdRef.current === requestId) {
          setIsLoading(false);
        }
      });
  };

  const queueSearch = (query: string, delay = 180) => {
    if (searchTimeoutRef.current !== null) {
      window.clearTimeout(searchTimeoutRef.current);
    }

    searchTimeoutRef.current = window.setTimeout(() => {
      runSearch(query);
    }, delay);
  };

  const handleFocus = () => {
    if (blurTimeoutRef.current !== null) {
      window.clearTimeout(blurTimeoutRef.current);
    }

    setIsOpen(true);
    queueSearch(value, 0);
  };

  const handleBlur = () => {
    blurTimeoutRef.current = window.setTimeout(() => {
      setIsOpen(false);
    }, 120);
  };

  return (
    <div className="lookup-field">
      <label htmlFor={inputId}>
        {label}
      </label>

      <div className="lookup-combobox">
        <input
          id={inputId}
          value={value}
          placeholder={placeholder}
          autoComplete="off"
          aria-expanded={isOpen}
          aria-controls={`${inputId}-options`}
          onFocus={handleFocus}
          onBlur={handleBlur}
          onChange={(event) => {
            onQueryChange(event.target.value);
            setIsOpen(true);
            queueSearch(event.target.value);
          }}
        />

        {isOpen ? (
          <div
            id={`${inputId}-options`}
            className="lookup-panel"
            role="listbox"
          >
            {isLoading ? (
              <div className="lookup-state">
                Buscando...
              </div>
            ) : null}

            {!isLoading && error ? (
              <div className="lookup-state">
                {error}
              </div>
            ) : null}

            {!isLoading && !error && items.length === 0 ? (
              <div className="lookup-state">
                {emptyMessage}
              </div>
            ) : null}

            {!isLoading && !error
              ? items.map((item) => (
                  <button
                    key={getKey(item)}
                    type="button"
                    className="lookup-option"
                    role="option"
                    onMouseDown={(event) => {
                      event.preventDefault();
                      onSelect(item);
                      setIsOpen(false);
                    }}
                  >
                    <span className="lookup-title">
                      {getTitle(item)}
                    </span>
                    <span className="lookup-subtitle">
                      {getSubtitle(item)}
                    </span>
                  </button>
                ))
              : null}
          </div>
        ) : null}
      </div>
    </div>
  );
}

function getColaboradorTitle(colaborador: ColaboradorLookup) {
  return (
    [colaborador.nome, colaborador.codigoColaborador]
      .filter(Boolean)
      .join(" · ") || colaborador.id
  );
}

function getColaboradorSubtitle(colaborador: ColaboradorLookup) {
  return (
    [
      colaborador.nomeGrupo,
      colaborador.nomePerfil,
      colaborador.email,
    ]
      .filter(Boolean)
      .join(" · ") || colaborador.id
  );
}

function getAssetTitle(asset: AssetLookup) {
  return (
    [asset.externalCode, asset.name]
      .filter(Boolean)
      .join(" · ") || asset.id
  );
}

function getAssetSubtitle(asset: AssetLookup) {
  return asset.category || asset.id;
}

function buscarTiposServico(
  query: string,
): Promise<RdoServiceType[]> {
  return Promise.resolve(searchRdoServiceTypes(query));
}

function getTipoServicoTitle(serviceType: RdoServiceType) {
  return serviceType.displayName;
}

function getTipoServicoSubtitle(serviceType: RdoServiceType) {
  return (
    [
      serviceType.parentCode && serviceType.parentName
        ? `${serviceType.parentCode} - ${serviceType.parentName}`
        : null,
      `Nivel ${serviceType.level}`,
    ]
      .filter(Boolean)
      .join(" · ")
  );
}

export function RdoCreatePage({
  initialDraft,
  isExisting,
  initialNotice,
  onBackToList,
  onSaved,
}: RdoCreatePageProps) {
  const [draft, setDraft] = useState<RdoDraft>(
    () => initialDraft,
  );

  const [showJson, setShowJson] = useState(false);
  const [notice, setNotice] = useState(initialNotice ?? "");
  const [photoError, setPhotoError] = useState("");
  const [isProcessingPhoto, setIsProcessingPhoto] = useState(false);
  const [photoPreviews, setPhotoPreviews] = useState<
    Record<string, string>
  >({});
  const photoInputRef = useRef<HTMLInputElement | null>(null);
  const previewUrlsRef = useRef<Set<string>>(new Set());
  const [
    alocacaoColaboradorLabels,
    setAlocacaoColaboradorLabels,
  ] = useState<Record<string, string>>({});

  const {
    isSaving,
    isSyncing,
    message: persistenceMessage,
    error: persistenceError,
    saveLocally,
    synchronize,
  } = useRdoLocalPersistence();

  const payload = useMemo(
    () => buildRdoSyncPayload(draft),
    [draft],
  );

  const photoCount = draft.attachments.filter(
    (attachment) => attachment.removedAt === null,
  ).length;
  const programmedExtensionM = extensionMeters(
    draft.kmInicialProgramado,
    draft.kmFinalProgramado,
  );
  const blockedExtensionM = extensionMeters(
    draft.kmInicialInterditado,
    draft.kmFinalInterditado,
  );

  useEffect(() => {
    let cancelled = false;

    void listRdoAttachments(initialDraft.id).then((attachments) => {
      if (cancelled) {
        return;
      }

      const previews: Record<string, string> = {};

      for (const attachment of attachments) {
        const url = URL.createObjectURL(attachment.arquivo);
        previewUrlsRef.current.add(url);
        previews[attachment.id] = url;
      }

      setPhotoPreviews((current) => ({
        ...current,
        ...previews,
      }));

      if (attachments.length > 0) {
        setDraft((current) => ({
          ...current,
          attachments: attachments.map(attachmentToDraft),
        }));
      }
    });

    return () => {
      cancelled = true;
    };
  }, [initialDraft.id]);

  useEffect(() => {
    const previewUrls = previewUrlsRef.current;

    return () => {
      previewUrls.forEach((url) =>
        URL.revokeObjectURL(url),
      );
      previewUrls.clear();
    };
  }, []);

  function updateField<K extends keyof RdoDraft>(
    field: K,
    value: RdoDraft[K],
  ) {
    setDraft((current) => ({
      ...current,
      [field]: value,
      attachments:
        field === "obraId"
          ? current.attachments.map((attachment) => ({
              ...attachment,
              obraId:
                typeof value === "string" && value.trim()
                  ? value
                  : null,
            }))
          : current.attachments,
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

  function updateServicoExecutado(
    localId: string,
    patch: Partial<ServicoExecutadoDraft>,
  ) {
    setDraft((current) => ({
      ...current,
      servicosExecutados:
        current.servicosExecutados.map((item) =>
          item.localId === localId
            ? { ...item, ...patch }
            : item,
        ),
    }));
  }

  function updateAlocacaoColaborador(
    localId: string,
    patch: Partial<AlocacaoColaboradorDraft>,
  ) {
    setDraft((current) => ({
      ...current,
      alocacoesColaboradores:
        current.alocacoesColaboradores.map((item) =>
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
      | "servicosExecutados"
      | "alocacoesColaboradores"
      | "maoObra"
      | "equipamentos"
      | "materiais"
      | "controlesGeometricos",
    localId: string,
  ) {
    if (collection === "alocacoesColaboradores") {
      setAlocacaoColaboradorLabels((current) => {
        const next = { ...current };
        delete next[localId];
        return next;
      });
    }

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

      onSaved(draft.obraId ?? "");
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

  async function handleAddPhotos(files: FileList | null) {
    if (!files || files.length === 0) {
      return;
    }

    setPhotoError("");
    setIsProcessingPhoto(true);

    try {
      const activeCount = draft.attachments.filter(
        (attachment) => attachment.removedAt === null,
      ).length;
      const availableSlots = Math.max(0, 5 - activeCount);

      if (availableSlots <= 0) {
        throw new Error("O limite é de 5 fotos por RDO.");
      }

      const selectedFiles = Array.from(files).slice(
        0,
        availableSlots,
      );

      if (files.length > availableSlots) {
        setNotice(
          `Foram anexadas ${availableSlots} foto(s). O limite é de 5 fotos por RDO.`,
        );
      }

      const added: RdoAttachmentDraft[] = [];
      const previewUpdates: Record<string, string> = {};

      for (const file of selectedFiles) {
        const { attachment, wasCompressed } =
          await processRdoPhoto({
            file,
            rdoId: draft.id,
            obraId: draft.obraId || null,
          });

        await putRdoAttachment(attachment);

        const previewUrl = URL.createObjectURL(
          attachment.arquivo,
        );
        previewUrlsRef.current.add(previewUrl);
        previewUpdates[attachment.id] = previewUrl;

        added.push(attachmentToDraft(attachment));

        await addOperationalEvent({
          type: "FOTO_ADICIONADA",
          principalEntity: {
            tipo: "RDO_FOTO",
            id: attachment.id,
            nome: attachment.nome,
          },
          relatedEntities: [
            {
              tipo: "RDO",
              id: draft.id,
              nome: draft.numeroRdo
                ? `RDO ${draft.numeroRdo}`
                : "RDO local",
            },
            {
              tipo: "OBRA",
              id: draft.obraId,
              nome: draft.contrato || draft.cliente || null,
            },
          ].filter((entity) => entity.id.trim()),
          obraId: draft.obraId || null,
          rdoId: draft.id,
          payload: {
            nomeOriginal: attachment.nomeOriginal,
            mimeType: attachment.mimeType,
            tamanhoOriginalBytes:
              attachment.tamanhoOriginalBytes,
            tamanhoComprimidoBytes:
              attachment.tamanhoComprimidoBytes,
            syncStatus: attachment.syncStatus,
          },
        });

        if (wasCompressed) {
          await addOperationalEvent({
            type: "FOTO_COMPRIMIDA",
            principalEntity: {
              tipo: "RDO_FOTO",
              id: attachment.id,
              nome: attachment.nome,
            },
            relatedEntities: [
              {
                tipo: "RDO",
                id: draft.id,
                nome: draft.numeroRdo
                  ? `RDO ${draft.numeroRdo}`
                  : "RDO local",
              },
            ],
            obraId: draft.obraId || null,
            rdoId: draft.id,
            payload: {
              tamanhoOriginalBytes:
                attachment.tamanhoOriginalBytes,
              tamanhoComprimidoBytes:
                attachment.tamanhoComprimidoBytes,
              reducaoBytes:
                attachment.tamanhoOriginalBytes -
                attachment.tamanhoComprimidoBytes,
            },
          });
        }
      }

      setPhotoPreviews((current) => ({
        ...current,
        ...previewUpdates,
      }));
      setDraft((current) => ({
        ...current,
        attachments: [...current.attachments, ...added],
      }));
    } catch (error: unknown) {
      setPhotoError(
        error instanceof Error
          ? error.message
          : "Falha ao anexar foto ao RDO.",
      );
    } finally {
      setIsProcessingPhoto(false);
    }
  }

  async function handleRemovePhoto(attachmentId: string) {
    setPhotoError("");

    try {
      const removed = await markRdoAttachmentRemoved(attachmentId);
      const timestamp =
        removed?.removedAt ?? new Date().toISOString();

      setDraft((current) => ({
        ...current,
        attachments: current.attachments.map((attachment) =>
          attachment.id === attachmentId
            ? {
                ...attachment,
                removedAt: timestamp,
                updatedAt: timestamp,
                syncStatus:
                  attachment.syncStatus === "SYNCED"
                    ? "PENDING_SYNC"
                    : attachment.syncStatus,
              }
            : attachment,
        ),
      }));

      await addOperationalEvent({
        type: "FOTO_REMOVIDA",
        principalEntity: {
          tipo: "RDO_FOTO",
          id: attachmentId,
          nome:
            draft.attachments.find(
              (attachment) => attachment.id === attachmentId,
            )?.nome ?? null,
        },
        relatedEntities: [
          {
            tipo: "RDO",
            id: draft.id,
            nome: draft.numeroRdo
              ? `RDO ${draft.numeroRdo}`
              : "RDO local",
          },
        ],
        obraId: draft.obraId || null,
        rdoId: draft.id,
        occurredAt: timestamp,
        payload: {
          attachmentId,
        },
      });
    } catch (error: unknown) {
      setPhotoError(
        error instanceof Error
          ? error.message
          : "Falha ao remover a foto.",
      );
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
      <datalist id="rdo-unidades">
        {UNIDADES_RDO.map((unidade) => (
          <option key={unidade} value={unidade} />
        ))}
      </datalist>

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

          <span className="brand-tick" aria-hidden="true" />

          <p className="subtitle">
            Registre e cont
            inue editando o relatório mesmo
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

            <strong
              className={`status-badge status-badge--${draft.syncStatus.toLowerCase()}`}
            >
              {formatLocalSyncStatus(
                draft.syncStatus,
              )}
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
            Cliente / Obra no Excel
            <input
              value={draft.cliente}
              onChange={(event) =>
                updateField(
                  "cliente",
                  event.target.value,
                )
              }
              placeholder="Nome da obra ou cliente"
            />
          </label>

          <label>
            Contrato / N° da obra
            <input
              value={draft.contrato}
              onChange={(event) =>
                updateField(
                  "contrato",
                  event.target.value,
                )
              }
              placeholder="Ex.: CW38386"
            />
          </label>

          <label>
            Rodovia
            <input
              value={draft.rodovia}
              onChange={(event) =>
                updateField(
                  "rodovia",
                  event.target.value,
                )
              }
              placeholder="Ex.: SP-348"
            />
          </label>

          <label>
            Cidade
            <input
              value={draft.cidade}
              onChange={(event) =>
                updateField(
                  "cidade",
                  event.target.value,
                )
              }
            />
          </label>

          <label>
            UF
            <input
              value={draft.uf}
              maxLength={2}
              onChange={(event) =>
                updateField(
                  "uf",
                  event.target.value.toUpperCase(),
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

          <label>
            Trecho programado inicial
            <input
              value={draft.kmInicialProgramado}
              onChange={(event) =>
                updateField(
                  "kmInicialProgramado",
                  event.target.value,
                )
              }
              placeholder="Km/estaca inicial"
            />
          </label>

          <label>
            Trecho programado final
            <input
              value={draft.kmFinalProgramado}
              onChange={(event) =>
                updateField(
                  "kmFinalProgramado",
                  event.target.value,
                )
              }
              placeholder="Km/estaca final"
            />
          </label>

          <label>
            Trecho interditado inicial
            <input
              value={draft.kmInicialInterditado}
              onChange={(event) =>
                updateField(
                  "kmInicialInterditado",
                  event.target.value,
                )
              }
              placeholder="Km/estaca inicial"
            />
          </label>

          <label>
            Trecho interditado final
            <input
              value={draft.kmFinalInterditado}
              onChange={(event) =>
                updateField(
                  "kmFinalInterditado",
                  event.target.value,
                )
              }
              placeholder="Km/estaca final"
            />
          </label>

          <label>
            Preenchido por
            <input
              value={draft.preenchidoPor}
              onChange={(event) =>
                updateField(
                  "preenchidoPor",
                  event.target.value,
                )
              }
            />
          </label>

          <label>
            Apontador do RDO
            <input
              value={draft.apontadorRdo}
              onChange={(event) =>
                updateField(
                  "apontadorRdo",
                  event.target.value,
                )
              }
            />
          </label>

          <label>
            Encarregado da obra
            <input
              value={draft.encarregadoObra}
              onChange={(event) =>
                updateField(
                  "encarregadoObra",
                  event.target.value,
                )
              }
            />
          </label>

          <label>
            Fiscalização de campo
            <input
              value={draft.fiscalizacaoCampo}
              onChange={(event) =>
                updateField(
                  "fiscalizacaoCampo",
                  event.target.value,
                )
              }
            />
          </label>
        </div>

        <div className="computed-grid rdo-extension-grid">
          <CalculatedMetric
            label="Extensão programada"
            value={
              programmedExtensionM === null
                ? "Em branco"
                : `${formatCalculatedNumber(programmedExtensionM)} m`
            }
          />
          <CalculatedMetric
            label="Extensão interditada"
            value={
              blockedExtensionM === null
                ? "Em branco"
                : `${formatCalculatedNumber(blockedExtensionM)} m`
            }
          />
          <CalculatedMetric
            label="Fotos no RDO"
            value={`${photoCount}/5`}
          />
          <CalculatedMetric
            label="Status offline"
            value={formatLocalSyncStatus(draft.syncStatus)}
          />
        </div>
      </section>

      <section className="form-card">
        <div className="section-heading">
          <div>
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
        <div className="section-heading collection-heading">
          <div>
            <h2>Fotos do RDO</h2>
          </div>

          <div className="section-actions">
            <p>
              Até 5 fotos por RDO, salvas localmente e
              comprimidas no navegador quando necessário.
            </p>

            <button
              type="button"
              className="add-button"
              onClick={() => photoInputRef.current?.click()}
              disabled={isProcessingPhoto || photoCount >= 5}
            >
              {isProcessingPhoto
                ? "Processando..."
                : "Adicionar foto"}
            </button>
          </div>
        </div>

        <input
          ref={photoInputRef}
          type="file"
          accept="image/*"
          multiple
          className="visually-hidden"
          onChange={(event) => {
            void handleAddPhotos(event.target.files);
            event.target.value = "";
          }}
        />

        {photoError && (
          <div className="notice notice-error">
            {photoError}
          </div>
        )}

        {photoCount === 0 ? (
          <div className="empty-inline-state">
            Nenhuma foto anexada a este RDO.
          </div>
        ) : (
          <div className="rdo-photo-grid">
            {draft.attachments
              .filter((attachment) => attachment.removedAt === null)
              .map((attachment) => (
                <article
                  className="rdo-photo-card"
                  key={attachment.id}
                >
                  {photoPreviews[attachment.id] ? (
                    <img
                      src={photoPreviews[attachment.id]}
                      alt={attachment.nome}
                    />
                  ) : (
                    <div className="rdo-photo-fallback">
                      Preview local indisponível
                    </div>
                  )}

                  <div className="rdo-photo-meta">
                    <strong>{attachment.nome}</strong>
                    <span>
                      {fileSizeLabel(
                        attachment.tamanhoComprimidoBytes,
                      )}
                      {attachment.tamanhoComprimidoBytes <
                      attachment.tamanhoOriginalBytes
                        ? ` de ${fileSizeLabel(
                            attachment.tamanhoOriginalBytes,
                          )}`
                        : ""}
                    </span>
                    <span>
                      {attachment.syncStatus === "SYNCED"
                        ? "Sincronizada"
                        : "Local/offline"}
                    </span>
                  </div>

                  <button
                    type="button"
                    className="danger-link"
                    onClick={() => {
                      void handleRemovePhoto(attachment.id);
                    }}
                  >
                    Remover
                  </button>
                </article>
              ))}
          </div>
        )}
      </section>

      <section className="form-card">
        <CollectionHeader
          title="Serviços executados"
          description="Serviços, quantidades, item contratual e custo realizado."
          onAdd={() =>
            setDraft((current) => ({
              ...current,
              servicosExecutados: [
                ...current.servicosExecutados,
                createEmptyServicoExecutado(),
              ],
            }))
          }
        />

        <div className="collection-list">
          {draft.servicosExecutados.map((item, index) => (
            <div
              className="collection-row"
              key={item.localId}
            >
              <div className="row-title">
                <strong>
                  Serviço {index + 1}
                </strong>

                <button
                  type="button"
                  className="danger-link"
                  onClick={() =>
                    removeCollectionItem(
                      "servicosExecutados",
                      item.localId,
                    )
                  }
                >
                  Remover
                </button>
              </div>

              <div className="form-grid">
                <LookupField
                  label="Tipo de serviço"
                  value={item.servicoNome}
                  placeholder="Pesquise por codigo, frente ou servico"
                  emptyMessage="Nenhum tipo de servico encontrado."
                  search={buscarTiposServico}
                  onQueryChange={(value) =>
                    updateServicoExecutado(
                      item.localId,
                      {
                        servicoNome: value,
                      },
                    )
                  }
                  onSelect={(serviceType) =>
                    updateServicoExecutado(
                      item.localId,
                      {
                        servicoNome:
                          formatRdoServiceType(serviceType),
                      },
                    )
                  }
                  getKey={(serviceType) => serviceType.catalogId}
                  getTitle={getTipoServicoTitle}
                  getSubtitle={getTipoServicoSubtitle}
                />

                <label>
                  Item contratual ID
                  <input
                    value={item.itemContratualId}
                    onChange={(event) =>
                      updateServicoExecutado(
                        item.localId,
                        {
                          itemContratualId:
                            event.target.value,
                        },
                      )
                    }
                    placeholder="UUID do item contratual"
                  />
                </label>

                <NumericField
                  label="Quantidade executada"
                  value={item.quantidadeExecutada}
                  onChange={(value) =>
                    updateServicoExecutado(
                      item.localId,
                      {
                        quantidadeExecutada: value,
                      },
                    )
                  }
                />

                <label>
                  Unidade
                  <input
                    value={item.unidade}
                    list="rdo-unidades"
                    onChange={(event) =>
                      updateServicoExecutado(
                        item.localId,
                        {
                          unidade: normalizarUnidade(
                            event.target.value,
                          ),
                        },
                      )
                    }
                    placeholder="m², m³, t..."
                  />
                </label>

                <label>
                  Status de validação
                  <select
                    value={item.statusValidacao}
                    onChange={(event) =>
                      updateServicoExecutado(
                        item.localId,
                        {
                          statusValidacao:
                            event.target
                              .value as ServicoExecutadoDraft["statusValidacao"],
                        },
                      )
                    }
                  >
                    <option value="REGISTRADA">
                      Registrada
                    </option>
                    <option value="VALIDADA">
                      Validada
                    </option>
                    <option value="REJEITADA">
                      Rejeitada
                    </option>
                  </select>
                </label>

                <label>
                  Turno do serviço
                  <select
                    value={item.turno}
                    onChange={(event) =>
                      updateServicoExecutado(
                        item.localId,
                        {
                          turno:
                            event.target
                              .value as ServicoExecutadoDraft["turno"],
                        },
                      )
                    }
                  >
                    <option value="">
                      Usar turno do RDO
                    </option>
                    <option value="DIURNO">
                      Diurno
                    </option>
                    <option value="NOTURNO">
                      Noturno
                    </option>
                  </select>
                </label>

                <NumericField
                  label="Custo realizado"
                  value={item.custoRealizado}
                  onChange={(value) =>
                    updateServicoExecutado(
                      item.localId,
                      {
                        custoRealizado: value,
                      },
                    )
                  }
                />

                <label>
                  Trecho inicial
                  <input
                    value={item.trechoInicial}
                    onChange={(event) =>
                      updateServicoExecutado(
                        item.localId,
                        {
                          trechoInicial:
                            event.target.value,
                        },
                      )
                    }
                  />
                </label>

                <label>
                  Trecho final
                  <input
                    value={item.trechoFinal}
                    onChange={(event) =>
                      updateServicoExecutado(
                        item.localId,
                        {
                          trechoFinal:
                            event.target.value,
                        },
                      )
                    }
                  />
                </label>

                <label>
                  Localização
                  <input
                    value={item.localizacao}
                    onChange={(event) =>
                      updateServicoExecutado(
                        item.localId,
                        {
                          localizacao:
                            event.target.value,
                        },
                      )
                    }
                  />
                </label>

                <label className="checkbox-field">
                  <input
                    type="checkbox"
                    checked={item.retrabalho}
                    onChange={(event) =>
                      updateServicoExecutado(
                        item.localId,
                        {
                          retrabalho:
                            event.target.checked,
                        },
                      )
                    }
                  />
                  Retrabalho
                </label>

                <label className="checkbox-field">
                  <input
                    type="checkbox"
                    checked={item.producaoRejeitada}
                    onChange={(event) =>
                      updateServicoExecutado(
                        item.localId,
                        {
                          producaoRejeitada:
                            event.target.checked,
                        },
                      )
                    }
                  />
                  Produção rejeitada
                </label>
              </div>

              <label className="full-width">
                Observações do serviço
                <textarea
                  rows={3}
                  value={item.observacoes}
                  onChange={(event) =>
                    updateServicoExecutado(
                      item.localId,
                      {
                        observacoes:
                          event.target.value,
                      },
                    )
                  }
                />
              </label>
            </div>
          ))}
        </div>
      </section>

      <section className="form-card">
        <CollectionHeader
          title="Rateio de colaboradores"
          description="Horas, percentual e origem da alocação operacional."
          onAdd={() =>
            setDraft((current) => ({
              ...current,
              alocacoesColaboradores: [
                ...current.alocacoesColaboradores,
                createEmptyAlocacaoColaborador(),
              ],
            }))
          }
        />

        <div className="collection-list">
          {draft.alocacoesColaboradores.map((item, index) => (
            <div
              className="collection-row"
              key={item.localId}
            >
              <div className="row-title">
                <strong>
                  Rateio {index + 1}
                </strong>

                <button
                  type="button"
                  className="danger-link"
                  onClick={() =>
                    removeCollectionItem(
                      "alocacoesColaboradores",
                      item.localId,
                    )
                  }
                >
                  Remover
                </button>
              </div>

              <div className="form-grid">
                <LookupField
                  label="Colaborador"
                  value={
                    alocacaoColaboradorLabels[
                      item.localId
                    ] ?? item.colaboradorId
                  }
                  placeholder="Digite nome, codigo, email ou equipe"
                  emptyMessage="Nenhum colaborador encontrado. Confira se a base Academy foi sincronizada."
                  search={buscarColaboradores}
                  onQueryChange={(value) => {
                    setAlocacaoColaboradorLabels(
                      (current) => ({
                        ...current,
                        [item.localId]: value,
                      }),
                    );
                    updateAlocacaoColaborador(
                      item.localId,
                      {
                        colaboradorId: "",
                      },
                    );
                  }}
                  onSelect={(colaborador) => {
                    setAlocacaoColaboradorLabels(
                      (current) => ({
                        ...current,
                        [item.localId]:
                          getColaboradorTitle(colaborador),
                      }),
                    );
                    updateAlocacaoColaborador(
                      item.localId,
                      {
                        colaboradorId: colaborador.id,
                        equipe:
                          colaborador.nomeGrupo ??
                          item.equipe,
                        funcao:
                          colaborador.nomePerfil ??
                          item.funcao,
                      },
                    );
                  }}
                  getKey={(colaborador) => colaborador.id}
                  getTitle={getColaboradorTitle}
                  getSubtitle={getColaboradorSubtitle}
                />

                <label>
                  Equipe
                  <input
                    value={item.equipe}
                    onChange={(event) =>
                      updateAlocacaoColaborador(
                        item.localId,
                        {
                          equipe:
                            event.target.value,
                        },
                      )
                    }
                  />
                </label>

                <LookupField
                  label="Tipo de serviço"
                  value={item.servicoNome}
                  placeholder="Pesquise por codigo, frente ou servico"
                  emptyMessage="Nenhum tipo de servico encontrado."
                  search={buscarTiposServico}
                  onQueryChange={(value) =>
                    updateAlocacaoColaborador(
                      item.localId,
                      {
                        servicoNome: value,
                      },
                    )
                  }
                  onSelect={(serviceType) =>
                    updateAlocacaoColaborador(
                      item.localId,
                      {
                        servicoNome:
                          formatRdoServiceType(serviceType),
                      },
                    )
                  }
                  getKey={(serviceType) => serviceType.catalogId}
                  getTitle={getTipoServicoTitle}
                  getSubtitle={getTipoServicoSubtitle}
                />

                <label>
                  Início
                  <input
                    type="time"
                    value={item.horaInicio}
                    onChange={(event) =>
                      updateAlocacaoColaborador(
                        item.localId,
                        {
                          horaInicio:
                            event.target.value,
                        },
                      )
                    }
                  />
                </label>

                <label>
                  Fim
                  <input
                    type="time"
                    value={item.horaFim}
                    onChange={(event) =>
                      updateAlocacaoColaborador(
                        item.localId,
                        {
                          horaFim:
                            event.target.value,
                        },
                      )
                    }
                  />
                </label>

                <NumericField
                  label="Percentual do dia"
                  value={item.percentualDia}
                  onChange={(value) =>
                    updateAlocacaoColaborador(
                      item.localId,
                      {
                        percentualDia: value,
                      },
                    )
                  }
                />

                <label>
                  Turno
                  <select
                    value={item.turno}
                    onChange={(event) =>
                      updateAlocacaoColaborador(
                        item.localId,
                        {
                          turno:
                            event.target
                              .value as AlocacaoColaboradorDraft["turno"],
                        },
                      )
                    }
                  >
                    <option value="">
                      Usar turno do RDO
                    </option>
                    <option value="DIURNO">
                      Diurno
                    </option>
                    <option value="NOTURNO">
                      Noturno
                    </option>
                  </select>
                </label>

                <label>
                  Função
                  <input
                    value={item.funcao}
                    onChange={(event) =>
                      updateAlocacaoColaborador(
                        item.localId,
                        {
                          funcao:
                            event.target.value,
                        },
                      )
                    }
                  />
                </label>

                <label>
                  Centro de custo
                  <input
                    value={item.centroCusto}
                    onChange={(event) =>
                      updateAlocacaoColaborador(
                        item.localId,
                        {
                          centroCusto:
                            event.target.value,
                        },
                      )
                    }
                  />
                </label>

                <label>
                  Tipo de alocação
                  <select
                    value={item.tipoAlocacao}
                    onChange={(event) =>
                      updateAlocacaoColaborador(
                        item.localId,
                        {
                          tipoAlocacao:
                            event.target
                              .value as AlocacaoColaboradorDraft["tipoAlocacao"],
                        },
                      )
                    }
                  >
                    <option value="TRABALHO">
                      Trabalho
                    </option>
                    <option value="DESLOCAMENTO">
                      Deslocamento
                    </option>
                    <option value="TREINAMENTO">
                      Treinamento
                    </option>
                    <option value="MANUTENCAO">
                      Manutenção
                    </option>
                    <option value="APOIO">
                      Apoio
                    </option>
                    <option value="ADMINISTRATIVO">
                      Administrativo
                    </option>
                    <option value="AFASTAMENTO">
                      Afastamento
                    </option>
                    <option value="OUTRO">
                      Outro
                    </option>
                  </select>
                </label>

                <label>
                  Status
                  <select
                    value={item.status}
                    onChange={(event) =>
                      updateAlocacaoColaborador(
                        item.localId,
                        {
                          status:
                            event.target
                              .value as AlocacaoColaboradorDraft["status"],
                        },
                      )
                    }
                  >
                    <option value="REGISTRADA">
                      Registrada
                    </option>
                    <option value="VALIDADA">
                      Validada
                    </option>
                    <option value="CONFLITO">
                      Conflito
                    </option>
                  </select>
                </label>

                <NumericField
                  label="Custo/hora"
                  value={item.custoHora}
                  onChange={(value) =>
                    updateAlocacaoColaborador(
                      item.localId,
                      {
                        custoHora: value,
                      },
                    )
                  }
                />
              </div>

              <label className="full-width">
                Observações do rateio
                <textarea
                  rows={3}
                  value={item.observacoes}
                  onChange={(event) =>
                    updateAlocacaoColaborador(
                      item.localId,
                      {
                        observacoes:
                          event.target.value,
                      },
                    )
                  }
                />
              </label>
            </div>
          ))}
        </div>
      </section>

      <section className="form-card">
        <CollectionHeader
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
                <LookupField
                  label="Colaborador"
                  value={
                    item.nomeColaborador ||
                    item.colaboradorId
                  }
                  placeholder="Digite nome, codigo, email ou equipe"
                  emptyMessage="Nenhum colaborador encontrado. Confira se a base Academy foi sincronizada."
                  search={buscarColaboradores}
                  onQueryChange={(value) =>
                    updateMaoObra(
                      item.localId,
                      {
                        colaboradorId: "",
                        nomeColaborador: value,
                      },
                    )
                  }
                  onSelect={(colaborador) =>
                    updateMaoObra(
                      item.localId,
                      {
                        colaboradorId: colaborador.id,
                        nomeColaborador:
                          colaborador.nome ??
                          colaborador.codigoColaborador ??
                          colaborador.id,
                        cargo:
                          colaborador.nomePerfil ??
                          item.cargo,
                      },
                    )
                  }
                  getKey={(colaborador) => colaborador.id}
                  getTitle={getColaboradorTitle}
                  getSubtitle={getColaboradorSubtitle}
                />

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

                <label>
                  Início
                  <input
                    type="time"
                    value={item.horaInicio}
                    onChange={(event) =>
                      updateMaoObra(
                        item.localId,
                        {
                          horaInicio:
                            event.target.value,
                        },
                      )
                    }
                  />
                </label>

                <label>
                  Fim
                  <input
                    type="time"
                    value={item.horaFim}
                    onChange={(event) =>
                      updateMaoObra(
                        item.localId,
                        {
                          horaFim:
                            event.target.value,
                        },
                      )
                    }
                  />
                </label>
              </div>

              <label className="full-width">
                Observações da mão de obra
                <textarea
                  rows={3}
                  value={item.observacoes}
                  onChange={(event) =>
                    updateMaoObra(
                      item.localId,
                      {
                        observacoes:
                          event.target.value,
                      },
                    )
                  }
                />
              </label>
            </div>
          ))}
        </div>
      </section>

      <section className="form-card">
        <CollectionHeader
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
                  <LookupField
                    label="Asset"
                    value={
                      item.prefixo ||
                      item.descricao ||
                      item.assetId
                    }
                    placeholder="Digite prefixo, nome ou categoria"
                    emptyMessage="Nenhum asset encontrado. Confira se a base Zeladoria foi sincronizada."
                    search={buscarAssets}
                    onQueryChange={(value) =>
                      updateEquipamento(
                        item.localId,
                        {
                          assetId: "",
                          prefixo: value,
                        },
                      )
                    }
                    onSelect={(asset) =>
                      updateEquipamento(
                        item.localId,
                        {
                          assetId: asset.id,
                          prefixo:
                            asset.externalCode ??
                            item.prefixo,
                          descricao:
                            asset.name ??
                            item.descricao,
                          tipoEquipamento:
                            asset.category ??
                            item.tipoEquipamento,
                        },
                      )
                    }
                    getKey={(asset) => asset.id}
                    getTitle={getAssetTitle}
                    getSubtitle={getAssetSubtitle}
                  />

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

                  <label>
                    Início
                    <input
                      type="time"
                      value={item.horaInicio}
                      onChange={(event) =>
                        updateEquipamento(
                          item.localId,
                          {
                            horaInicio:
                              event.target.value,
                          },
                        )
                      }
                    />
                  </label>

                  <label>
                    Fim
                    <input
                      type="time"
                      value={item.horaFim}
                      onChange={(event) =>
                        updateEquipamento(
                          item.localId,
                          {
                            horaFim:
                              event.target.value,
                          },
                        )
                      }
                    />
                  </label>
                </div>

                <label className="full-width">
                  Observações do equipamento
                  <textarea
                    rows={3}
                    value={item.observacoes}
                    onChange={(event) =>
                      updateEquipamento(
                        item.localId,
                        {
                          observacoes:
                            event.target.value,
                        },
                      )
                    }
                  />
                </label>
              </div>
            ),
          )}
        </div>
      </section>

      <section className="form-card">
        <CollectionHeader
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
                      list="rdo-unidades"
                      onChange={(event) =>
                        updateMaterial(
                          item.localId,
                          {
                            unidade: normalizarUnidade(
                              event.target.value,
                            ),
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

                  <NumericField
                    label="Quantidade sobra"
                    value={item.quantidadeSobra}
                    onChange={(value) =>
                      updateMaterial(
                        item.localId,
                        {
                          quantidadeSobra: value,
                        },
                      )
                    }
                  />

                  <label>
                    Nota fiscal
                    <input
                      value={item.notaFiscal}
                      onChange={(event) =>
                        updateMaterial(
                          item.localId,
                          {
                            notaFiscal:
                              event.target.value,
                          },
                        )
                      }
                    />
                  </label>

                  <label>
                    Fornecedor
                    <input
                      value={item.fornecedor}
                      onChange={(event) =>
                        updateMaterial(
                          item.localId,
                          {
                            fornecedor:
                              event.target.value,
                          },
                        )
                      }
                    />
                  </label>
                </div>

                <div className="computed-grid">
                  <CalculatedMetric
                    label="Sobra calculada"
                    value={`${formatCalculatedNumber(
                      calcularSobraMaterial(item),
                    )} ${item.unidade || ""}`.trim()}
                  />
                </div>

                <label className="full-width">
                  Observações do material
                  <textarea
                    rows={3}
                    value={item.observacoes}
                    onChange={(event) =>
                      updateMaterial(
                        item.localId,
                        {
                          observacoes:
                            event.target.value,
                        },
                      )
                    }
                  />
                </label>
              </div>
            ),
          )}
        </div>
      </section>

      <section className="form-card">
        <CollectionHeader
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
                    Número
                    <input
                      value={item.numero}
                      onChange={(event) =>
                        updateControle(
                          item.localId,
                          {
                            numero:
                              event.target.value,
                          },
                        )
                      }
                    />
                  </label>

                  <label>
                    Estaca inicial
                    <input
                      value={item.estacaInicial}
                      onChange={(event) =>
                        updateControle(
                          item.localId,
                          {
                            estacaInicial:
                              event.target.value,
                          },
                        )
                      }
                    />
                  </label>

                  <label>
                    Estaca final
                    <input
                      value={item.estacaFinal}
                      onChange={(event) =>
                        updateControle(
                          item.localId,
                          {
                            estacaFinal:
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

                  <label>
                    Pista
                    <input
                      value={item.pista}
                      onChange={(event) =>
                        updateControle(
                          item.localId,
                          {
                            pista:
                              event.target.value,
                          },
                        )
                      }
                    />
                  </label>

                  <label>
                    Faixa
                    <input
                      value={item.faixa}
                      onChange={(event) =>
                        updateControle(
                          item.localId,
                          {
                            faixa:
                              event.target.value,
                          },
                        )
                      }
                    />
                  </label>

                  <label>
                    Ordem de serviço
                    <input
                      value={item.ordemServico}
                      onChange={(event) =>
                        updateControle(
                          item.localId,
                          {
                            ordemServico:
                              event.target.value,
                          },
                        )
                      }
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

                <div className="computed-grid">
                  {(() => {
                    const calculo =
                      calcularControleGeometrico(item);

                    return (
                      <>
                        <CalculatedMetric
                          label="Espessura média"
                          value={`${formatCalculatedNumber(
                            calculo.espessuraMediaCm,
                          )} cm`}
                        />
                        <CalculatedMetric
                          label="Área"
                          value={`${formatCalculatedNumber(
                            calculo.areaM2,
                          )} m²`}
                        />
                        <CalculatedMetric
                          label="Volume"
                          value={`${formatCalculatedNumber(
                            calculo.volumeM3,
                          )} m³`}
                        />
                        <CalculatedMetric
                          label="Massa"
                          value={`${formatCalculatedNumber(
                            calculo.massaTonelada,
                          )} t`}
                        />
                      </>
                    );
                  })()}
                </div>

                <label className="full-width">
                  Atividades executadas / observações
                  <textarea
                    rows={3}
                    value={item.atividadeObservacoes}
                    onChange={(event) =>
                      updateControle(
                        item.localId,
                        {
                          atividadeObservacoes:
                            event.target.value,
                        },
                      )
                    }
                  />
                </label>

                <label className="full-width">
                  Observações do controle
                  <textarea
                    rows={3}
                    value={item.observacoes}
                    onChange={(event) =>
                      updateControle(
                        item.localId,
                        {
                          observacoes:
                            event.target.value,
                        },
                      )
                    }
                  />
                </label>
              </div>
            ),
          )}
        </div>
      </section>

      {showJson && (
        <section className="form-card json-preview">
          <div className="section-heading">
            <div>
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
  title: string;
  description: string;
  onAdd: () => void;
}

function CollectionHeader({
  title,
  description,
  onAdd,
}: CollectionHeaderProps) {
  return (
    <div className="section-heading collection-heading">
      <div>
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

interface CalculatedMetricProps {
  label: string;
  value: string;
}

function CalculatedMetric({
  label,
  value,
}: CalculatedMetricProps) {
  return (
    <div className="calculated-metric">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}
