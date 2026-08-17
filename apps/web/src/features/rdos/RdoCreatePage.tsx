import {
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
} from "react";

import { InstitutionalPageHeader } from "../../components/institutional/InstitutionalPageHeader";
import {
  InstitutionalStatus,
  type InstitutionalStatusState,
} from "../../components/institutional/InstitutionalStatus";
import { getSession, isAlfa } from "../auth/authSession";
import {
  listRdoAttachments,
  markRdoAttachmentRemoved,
  putRdoAttachment,
} from "../../lib/db/rdoAttachmentRepository";
import { getLocalRdo } from "../../lib/db/rdoRepository";
import { garantirArquivoDaFoto } from "./rdoPhotoSync";
import {
  rascunhoDifereDoQueEstaGravado,
  servicoExecutadoNeedsCatalogSelection,
} from "../../lib/db/localRdoService";
import { formatLocalSyncStatus } from "../../lib/db/syncStatusLabels";
import { SYNC_COMPLETED_EVENT } from "../../lib/sync/syncEvents";
import type {
  LocalRdoRecord,
  RdoAttachmentRecord,
} from "../../lib/db/db.types";
import {
  createEmptyMaterial,
  createEmptyRdo,
  createEmptyServicoExecutado,
} from "./createEmptyRdo";
import type {
  MaterialDraft,
  NumericInput,
  RdoAttachmentDraft,
  RdoDraft,
  ServicoExecutadoDraft,
} from "./rdo.types";
import type { RdoSyncStatus } from "./rdo.types";
import { processRdoPhoto } from "./rdoPhotoService";

import {
  formatRdoServiceType,
  isRdoPriceCatalogSelectable,
  searchRdoServiceTypes,
  type RdoServiceType,
  unidadeUnicaDasOpcoesDePreco,
} from "./rdoServiceTypes";
import {
  calcularSobraMaterial,
  extensionMeters,
  formatCalculatedNumber,
  medidasDoServico,
} from "./rdoCalculations";
import {
  comQuantidadeMedida,
  quantidadeDoServico,
  rotuloDaQuantidade,
} from "./quantidadeDoServico";
import { rdoObservationBudget } from "./export/rdoExportProjection";
import { useRdoLocalPersistence } from "./useRdoLocalPersistence";
import { UNIDADES_RDO, normalizarUnidade } from "./unidades";
import { requireRdoCreationContext } from "./rdoCreationContextRepository";
import { RdoWorkforceEditor } from "./RdoWorkforceEditor";
import { RdoEquipmentPicker } from "./RdoEquipmentPicker";
import type { RdoCreationContextLookup } from "./rdoLookupApi";
import { RDO_WORKFORCE_CATALOG_OFFLINE_UNAVAILABLE } from "./rdoCreationContext";
import { localRecordToDraft } from "./localRecordToDraft";
import {
  apontadosEmOutroRdo,
  type ApontamentosDoDia,
} from "./apontadosEmOutroRdo";

interface RdoCreatePageProps {
  initialDraft: RdoDraft;
  isExisting: boolean;
  initialNotice?: string;
  creationContext?: RdoCreationContextLookup;
  onBackToList: () => void;
  onSaved: (savedObraId: string) => void;
}

type PersistedWorkforceSnapshot = Pick<
  RdoDraft,
  "maoObra" | "alocacoesColaboradores" | "apontadorColaboradorId"
>;

function persistedWorkforceSnapshot(
  draft: RdoDraft,
): PersistedWorkforceSnapshot {
  return {
    maoObra: draft.maoObra.map((item) => ({ ...item })),
    alocacoesColaboradores: draft.alocacoesColaboradores.map(
      (item) => ({ ...item }),
    ),
    apontadorColaboradorId: draft.apontadorColaboradorId,
  };
}

function samePersistedItem(left: unknown, right: unknown): boolean {
  return JSON.stringify(left) === JSON.stringify(right);
}

function mergePersistedCollection<T extends { localId: string }>(
  baseline: readonly T[],
  current: readonly T[],
  persisted: readonly T[],
): T[] {
  const baselineById = new Map(
    baseline.map((item) => [item.localId, item]),
  );
  const persistedById = new Map(
    persisted.map((item) => [item.localId, item]),
  );
  const currentIds = new Set(current.map((item) => item.localId));
  const merged: T[] = [];

  for (const currentItem of current) {
    const baselineItem = baselineById.get(currentItem.localId);
    if (
      baselineItem === undefined ||
      !samePersistedItem(currentItem, baselineItem)
    ) {
      merged.push(currentItem);
      continue;
    }
    const persistedItem = persistedById.get(currentItem.localId);
    if (persistedItem !== undefined) merged.push(persistedItem);
  }
  for (const persistedItem of persisted) {
    if (
      !currentIds.has(persistedItem.localId) &&
      !baselineById.has(persistedItem.localId)
    ) {
      merged.push(persistedItem);
    }
  }
  return merged;
}

function mergePersistedWorkforce(
  current: RdoDraft,
  baseline: PersistedWorkforceSnapshot,
  persisted: RdoDraft,
): RdoDraft {
  return {
    ...current,
    numeroRdo: persisted.numeroRdo,
    syncStatus: persisted.syncStatus,
    maoObra: mergePersistedCollection(
      baseline.maoObra,
      current.maoObra,
      persisted.maoObra,
    ),
    alocacoesColaboradores: mergePersistedCollection(
      baseline.alocacoesColaboradores,
      current.alocacoesColaboradores,
      persisted.alocacoesColaboradores,
    ),
    apontadorColaboradorId:
      current.apontadorColaboradorId ===
          baseline.apontadorColaboradorId
        ? persisted.apontadorColaboradorId
        : current.apontadorColaboradorId,
  };
}

function parseNumericInput(value: string): NumericInput {
  return value === "" ? "" : Number(value);
}

function removeLocalId<T extends { localId: string }>(
  item: T,
): Omit<T, "localId"> {
  const { localId, ...payload } = item;

  void localId;

  return payload;
}

function buildPayload(draft: RdoDraft) {
  return {
    id: draft.id,
    obraId: draft.obraId,
    programacaoId: draft.programacaoId || null,
    numeroRdo: draft.numeroRdo,
    dataRdo: draft.dataRdo,
    cliente: draft.cliente || null,
    contrato: draft.contrato || null,
    rodovia: draft.rodovia || null,
    cidade: draft.cidade || null,
    uf: draft.uf || null,
    kmInicialProgramado:
      draft.kmInicialProgramado || null,
    kmFinalProgramado:
      draft.kmFinalProgramado || null,
    kmInicialInterditado:
      draft.kmInicialInterditado || null,
    kmFinalInterditado:
      draft.kmFinalInterditado || null,
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
    preenchidoPor: draft.preenchidoPor || null,
    apontadorRdo: draft.apontadorRdo || null,
    encarregadoObra: draft.encarregadoObra || null,
    fiscalizacaoCampo:
      draft.fiscalizacaoCampo || null,
    servicosExecutados:
      draft.servicosExecutados.map(removeLocalId),
    alocacoesColaboradores:
      draft.alocacoesColaboradores.map(removeLocalId),
    maoObra: draft.maoObra.map(removeLocalId),
    equipamentos:
      draft.equipamentos.map(removeLocalId),
    materiais: draft.materiais.map(removeLocalId),
    controlesGeometricos:
      draft.controlesGeometricos.map(removeLocalId),
    attachments: draft.attachments,
  };
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

/**
 * Comprimento, área e volume do serviço — contas, não campos.
 *
 * <p>Cada uma só aparece quando as parcelas existem: sem largura não há área,
 * e área ausente não é área zero. Mostrar zero onde falta medida faria o
 * relatório somar produção que ninguém executou.
 *
 * <p>Uma delas é a quantidade que vai para a medição, e qual é depende da
 * unidade que o catálogo dá ao serviço. Ela aparece marcada, porque quem
 * aponta precisa ver que número está afirmando — a quantidade deixou de ser
 * um campo, e um número que não se vê é um número em que não se confia.
 */
function MedidasDoServicoCalculadas({
  item,
}: {
  item: ServicoExecutadoDraft;
}) {
  const { comprimentoM, areaM2, volumeM3 } = medidasDoServico(item);
  const medida = rotuloDaQuantidade(item.unidade);
  const quantidade = quantidadeDoServico(item);
  return (
    <div className="rdo-servico-medidas">
      <CalculatedMetric
        label={medida === "Comprimento" ? "Comprimento · quantidade" : "Comprimento"}
        value={
          comprimentoM === null
            ? "—"
            : `${formatCalculatedNumber(comprimentoM)} m`
        }
      />
      <CalculatedMetric
        label={medida === "Área" ? "Área · quantidade" : "Área"}
        value={
          areaM2 === null
            ? "—"
            : `${formatCalculatedNumber(areaM2)} m²`
        }
      />
      <CalculatedMetric
        label={medida === "Volume" ? "Volume · quantidade" : "Volume"}
        value={
          volumeM3 === null
            ? "—"
            : `${formatCalculatedNumber(volumeM3)} m³`
        }
      />
      {item.unidade && medida === null ? (
        <p className="rdo-servico-medidas__aviso" role="status">
          Este serviço é medido em {item.unidade}, que não sai do trecho.
          Informe a quantidade pelo Financeiro.
        </p>
      ) : null}
      {medida !== null && quantidade === null ? (
        <p className="rdo-servico-medidas__aviso" role="status">
          Faltam medidas para fechar a quantidade em {item.unidade}.
        </p>
      ) : null}
    </div>
  );
}

function hasText(value: string): boolean {
  return value.trim().length > 0;
}

function institutionalState(
  status: RdoSyncStatus,
): InstitutionalStatusState | null {
  switch (status) {
    case "LOCAL_ONLY":
    case "LOCAL_PENDING":
      return "LOCAL";
    case "PENDING_SYNC":
      return "PENDING";
    case "SYNCING":
      return "SYNCING";
    case "SYNCED":
      return "SYNCED";
    case "CONFLICT":
      return "CONFLICT";
    case "ERROR":
      return null;
  }
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
  disabled?: boolean;
  disabledMessage?: string;
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
  disabled = false,
  disabledMessage = "",
}: LookupFieldProps<TItem>) {
  const inputId = useId();
  const [isOpen, setIsOpen] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [items, setItems] = useState<TItem[]>([]);
  const [activeIndex, setActiveIndex] = useState(-1);
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
        setActiveIndex(-1);
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

  const selectItem = (item: TItem) => {
    onSelect(item);
    setIsOpen(false);
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "Escape") {
      setIsOpen(false);
      return;
    }
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      setIsOpen(true);
      if (items.length === 0) return;
      const direction = event.key === "ArrowDown" ? 1 : -1;
      setActiveIndex((current) => {
        if (current < 0) {
          return event.key === "ArrowDown" ? 0 : items.length - 1;
        }
        return (current + direction + items.length) % items.length;
      });
      return;
    }
    if (
      event.key === "Enter" &&
      isOpen &&
      !isLoading &&
      !error
    ) {
      const selectedItem = items[activeIndex];
      if (!selectedItem) return;
      event.preventDefault();
      selectItem(selectedItem);
    }
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
          role="combobox"
          aria-autocomplete="list"
          aria-expanded={isOpen}
          aria-controls={`${inputId}-options`}
          aria-activedescendant={
            isOpen && items[activeIndex]
              ? `${inputId}-option-${activeIndex}`
              : undefined
          }
          disabled={disabled}
          onFocus={handleFocus}
          onBlur={handleBlur}
          onKeyDown={handleKeyDown}
          onChange={(event) => {
            onQueryChange(event.target.value);
            setIsOpen(true);
            queueSearch(event.target.value);
          }}
        />

        {isOpen && !disabled ? (
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
              ? items.map((item, index) => (
                  <button
                    key={getKey(item)}
                    id={`${inputId}-option-${index}`}
                    type="button"
                    className={
                      index === activeIndex
                        ? "lookup-option is-active"
                        : "lookup-option"
                    }
                    role="option"
                    aria-selected={index === activeIndex}
                    onMouseEnter={() => setActiveIndex(index)}
                    onMouseDown={(event) => {
                      event.preventDefault();
                    }}
                    onClick={() => selectItem(item)}
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
      {disabled && disabledMessage ? (
        <span className="lookup-state">{disabledMessage}</span>
      ) : null}
    </div>
  );
}

function getTipoServicoTitle(serviceType: RdoServiceType) {
  return serviceType.displayName;
}

function getTipoServicoSubtitle(serviceType: RdoServiceType) {
  return (
    [
      serviceType.description,
      `${serviceType.priceChoices.length} ${
        serviceType.priceChoices.length === 1
          ? "preço vigente"
          : "preços vigentes"
      }`,
    ]
      .filter(Boolean)
      .join(" · ")
  );
}

export function RdoCreatePage({
  initialDraft,
  isExisting,
  initialNotice,
  creationContext,
  onBackToList,
  onSaved,
}: RdoCreatePageProps) {
  const [draft, setDraft] = useState<RdoDraft>(
    () => initialDraft,
  );
  const [activeCreationContext, setActiveCreationContext] =
    useState<RdoCreationContextLookup | null>(creationContext ?? null);
  const [workforceCatalogMessage, setWorkforceCatalogMessage] =
    useState(
      initialDraft.syncStatus === "LOCAL_PENDING"
        ? "Contexto canônico pendente; o RDO permanece local até a validação automática."
        : "",
    );

  const canViewRawPayload = isAlfa(getSession());
  const [showJson, setShowJson] = useState(false);
  const [notice, setNotice] = useState(initialNotice ?? "");
  const [photoError, setPhotoError] = useState("");
  const [isProcessingPhoto, setIsProcessingPhoto] = useState(false);
  const [photoPreviews, setPhotoPreviews] = useState<
    Record<string, string>
  >({});
  const photoInputRef = useRef<HTMLInputElement | null>(null);
  const previewUrlsRef = useRef<Set<string>>(new Set());
  const persistedWorkforceRef = useRef<PersistedWorkforceSnapshot>(
    persistedWorkforceSnapshot(initialDraft),
  );
  const persistedReadGenerationRef = useRef(0);
  const {
    isSaving,
    isSyncing,
    message: persistenceMessage,
    error: persistenceError,
    saveLocally,
    synchronize,
  } = useRdoLocalPersistence();

  const payload = useMemo(
    () =>
      canViewRawPayload
        ? buildPayload(draft)
        : null,
    [canViewRawPayload, draft],
  );
  // A caixa de observações do RDO é compartilhada: além do que se escreve aqui,
  // ela recebe as linhas de continuidade, praticabilidade e clima, e ainda a
  // observação de cada colaborador, equipamento, material, controle e serviço.
  // Por isso o aviso mede o agregado, e não o tamanho deste campo.
  const observationBudget = useMemo(
    () => rdoObservationBudget(draft),
    [draft],
  );
  const observationBudgetId = useId();
  const serviceCatalog = activeCreationContext?.serviceCatalog ?? [];
  const priceCatalogSelectable = isRdoPriceCatalogSelectable(
    activeCreationContext?.coverage.serviceCatalog,
    activeCreationContext?.coverage.priceCatalog,
  );
  const priceCoverageMessage = priceCatalogSelectable
    ? ""
    : "Catálogo parcial ou indisponível. Sincronize o contexto antes de selecionar um preço.";
  const buscarTiposServico = (query: string): Promise<RdoServiceType[]> =>
    Promise.resolve(searchRdoServiceTypes(serviceCatalog, query));

  const parqueDaObra = activeCreationContext?.equipamentos ?? [];

  /*
   * Quem já aparece em outro RDO desta obra na mesma data. É aviso, não
   * impedimento: a mesma máquina atender duas frentes no mesmo dia acontece, e
   * barrar transformaria o caso legítimo num problema sem saída em campo.
   */
  const [apontadosHoje, setApontadosHoje] = useState<ApontamentosDoDia>(
    () => ({ pessoas: new Map(), equipamentos: new Map() }),
  );
  useEffect(() => {
    let cancelado = false;
    void apontadosEmOutroRdo(draft.obraId, draft.dataRdo, draft.id)
      .then((encontrados) => {
        if (!cancelado) setApontadosHoje(encontrados);
      })
      .catch(() => {
        // A leitura é um adorno da lista: sem ela a marcação continua
        // funcionando, só deixa de avisar. Derrubar o formulário por causa
        // disso seria trocar um aviso perdido por um dia perdido.
      });
    return () => {
      cancelado = true;
    };
  }, [draft.obraId, draft.dataRdo, draft.id]);

  const photoCount = draft.attachments.filter(
    (attachment) => attachment.removedAt === null,
  ).length;
  const blockedExtensionM = extensionMeters(
    draft.kmInicialInterditado,
    draft.kmFinalInterditado,
  );
  const hasServiceWithoutCatalogSelection = draft.servicosExecutados.some(
    servicoExecutadoNeedsCatalogSelection,
  );
  const rdoSections = useMemo(
    () => [
      {
        id: "rdo-identificacao",
        label: "Identificação",
        isComplete:
          hasText(draft.obraId) &&
          hasText(draft.numeroRdo) &&
          hasText(draft.dataRdo),
      },
      {
        id: "rdo-condicoes",
        label: "Condições do dia",
        isComplete:
          Boolean(
            draft.condicaoManha ||
              draft.condicaoTarde ||
              draft.condicaoNoite,
          ) ||
          draft.pluviometriaMm !== "" ||
          hasText(draft.observacoes),
      },
      {
        id: "rdo-fotos",
        label: "Fotos",
        isComplete: photoCount > 0,
      },
      {
        id: "rdo-servicos",
        label: "Serviços",
        isComplete:
          draft.servicosExecutados.some((item) =>
            hasText(item.servicoNome),
          ) && !hasServiceWithoutCatalogSelection,
      },
      {
        id: "rdo-mao-de-obra",
        label: "Mão de obra",
        isComplete: draft.maoObra.some(
          (item) =>
            hasText(item.colaboradorId) ||
            hasText(item.nomeColaborador) ||
            hasText(item.cargo),
        ),
      },
      {
        id: "rdo-equipamentos",
        label: "Equipamentos",
        isComplete: draft.equipamentos.some(
          (item) =>
            hasText(item.assetId) ||
            hasText(item.prefixo) ||
            hasText(item.descricao),
        ),
      },
      {
        id: "rdo-materiais",
        label: "Materiais",
        isComplete: draft.materiais.some((item) =>
          hasText(item.materialNome),
        ),
      },
    ],
    [draft, photoCount, hasServiceWithoutCatalogSelection],
  );
  const completeSectionCount = rdoSections.filter(
    (section) => section.isComplete,
  ).length;
  const localStatusState = institutionalState(draft.syncStatus);
  const hasLocalCopy =
    isExisting ||
    draft.syncStatus !== "LOCAL_ONLY" ||
    persistenceMessage.startsWith("RDO salvo");

  function applyPersistedRdo(persistedRdo: LocalRdoRecord) {
    const persistedDraft = localRecordToDraft(persistedRdo);
    const baseline = persistedWorkforceRef.current;
    setDraft((current) =>
      mergePersistedWorkforce(current, baseline, persistedDraft)
    );
    persistedWorkforceRef.current =
      persistedWorkforceSnapshot(persistedDraft);
  }

  useEffect(() => {
    let cancelled = false;

    void listRdoAttachments(initialDraft.id).then((attachments) => {
      if (cancelled) {
        return;
      }

      const previews: Record<string, string> = {};

      for (const attachment of attachments) {
        /*
         * A ficha que veio de outro aparelho chega sem o binário: a foto mora
         * no servidor até o primeiro download. O cartão aparece já — é o que
         * diz que a foto existe — e o download preenche a imagem, gravando o
         * blob de volta na ficha para as próximas aberturas serem offline.
         */
        if (!attachment.arquivo) {
          void garantirArquivoDaFoto(attachment).then((blob) => {
            if (cancelled || !blob) return;
            const url = URL.createObjectURL(blob);
            previewUrlsRef.current.add(url);
            setPhotoPreviews((current) => ({
              ...current,
              [attachment.id]: url,
            }));
          });
          continue;
        }
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

  useEffect(() => {
    let active = true;

    const reconcilePersistedRdo = () => {
      const generation = ++persistedReadGenerationRef.current;
      void getLocalRdo(initialDraft.id)
        .then((persistedRdo) => {
          if (
            !active ||
            generation !== persistedReadGenerationRef.current ||
            !persistedRdo
          ) {
            return;
          }
          applyPersistedRdo(persistedRdo);
        })
        .catch(() => {
          // A próxima execução automática tentará novamente. A tela mantém
          // o último estado local confirmado em vez de inventar um resultado.
        });
    };

    window.addEventListener(
      SYNC_COMPLETED_EVENT,
      reconcilePersistedRdo,
    );
    return () => {
      active = false;
      window.removeEventListener(
        SYNC_COMPLETED_EVENT,
        reconcilePersistedRdo,
      );
    };
  }, [initialDraft.id]);

  useEffect(() => {
    let cancelled = false;
    if (
      creationContext ||
      draft.syncStatus === "LOCAL_PENDING" ||
      !draft.obraId ||
      !draft.dataRdo
    ) {
      return () => {
        cancelled = true;
      };
    }

    const online = typeof navigator !== "undefined" && navigator.onLine;
    void requireRdoCreationContext(
      draft.obraId,
      draft.dataRdo,
      online,
    )
      .then((resolved) => {
        if (!cancelled) {
          setActiveCreationContext(resolved.context);
          setWorkforceCatalogMessage("");
        }
      })
      .catch((unknownError: unknown) => {
        if (!cancelled) {
          setActiveCreationContext(null);
          setWorkforceCatalogMessage(
            online
              ? unknownError instanceof Error
                ? unknownError.message
                : "Não foi possível carregar os colaboradores autorizados desta obra."
              : RDO_WORKFORCE_CATALOG_OFFLINE_UNAVAILABLE,
          );
        }
      });

    return () => {
      cancelled = true;
    };
  }, [
    creationContext,
    draft.dataRdo,
    draft.obraId,
    draft.syncStatus,
  ]);

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

  /*
   * A quantidade é refeita a cada toque no bloco porque ela não é mais um
   * campo: é o que o trecho, a largura e a espessura afirmam, lidos na unidade
   * que o catálogo dá ao serviço. Recalcular aqui, e não na hora de salvar,
   * mantém a tela e o que sobe dizendo a mesma coisa.
   */
  function updateServicoExecutado(
    localId: string,
    patch: Partial<ServicoExecutadoDraft>,
  ) {
    setDraft((current) => ({
      ...current,
      servicosExecutados:
        current.servicosExecutados.map((item) =>
          item.localId === localId
            ? comQuantidadeMedida({ ...item, ...patch })
            : item,
        ),
    }));
  }

  function removeCollectionItem(
    collection:
      | "servicosExecutados"
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

      persistedReadGenerationRef.current += 1;
      applyPersistedRdo(persistedRdo);

      onSaved(draft.obraId ?? "");
    } catch {
      // O hook já registra e exibe o erro.
    }
  }

  async function handleSyncNow() {
    /*
     * Sincronizar é mandar embora o que está na tela — e o que está na tela só
     * sai daqui se estiver gravado. Antes disto o botão enviava a última versão
     * salva e devolvia "sincronização concluída" para quem tinha acabado de
     * digitar algo que continuava sem sair do aparelho: a frase era verdadeira
     * sobre a fila e falsa sobre a intenção de quem apertou.
     *
     * Grava só quando há diferença. Gravar sempre enfileiraria uma edição vazia
     * a cada toque no botão — versão consumida no servidor e linha inventada na
     * Memória, por nada.
     *
     * Se a gravação falhar, não sincroniza. Subir o estado antigo depois de a
     * pessoa ver um erro de salvamento seria a pior das duas metades: ela fica
     * achando que mandou o que está vendo.
     */
    try {
      if (await rascunhoDifereDoQueEstaGravado(draft)) {
        await saveLocally(draft);
      }
    } catch {
      // O hook já registra e exibe o erro do salvamento.
      return;
    }

    if (hasServiceWithoutCatalogSelection) {
      return;
    }

    try {
      await synchronize();
    } catch {
      // O hook já registra e exibe o erro.
      return;
    }

    const generation = ++persistedReadGenerationRef.current;
    const persistedRdo = await getLocalRdo(draft.id);

    if (
      persistedRdo &&
      generation === persistedReadGenerationRef.current
    ) {
      applyPersistedRdo(persistedRdo);
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
        const { attachment } = await processRdoPhoto({
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
    <main className="page-shell rdo-create-workspace">
      <datalist id="rdo-unidades">
        {UNIDADES_RDO.map((unidade) => (
          <option key={unidade} value={unidade} />
        ))}
      </datalist>

      <InstitutionalPageHeader
        eyebrow="Operação de campo"
        title={
          isExisting
            ? "Editar Relatório Diário de Obra"
            : "Novo Relatório Diário de Obra"
        }
        actions={(
          <button
            type="button"
            className="secondary-button"
            onClick={onBackToList}
            disabled={isSaving || isSyncing}
          >
            Voltar aos RDOs locais
          </button>
        )}
      />

      <div className="rdo-document-layout">
        <aside
          className="rdo-document-index"
          aria-label="Índice do RDO"
        >
          <div className="rdo-document-index__heading">
            <span>Preenchimento</span>
            <strong className="tabular-nums">
              {completeSectionCount}/{rdoSections.length}
            </strong>
          </div>

          <nav>
            <ol>
              {rdoSections.map((section, index) => (
                <li key={section.id}>
                  <a href={`#${section.id}`}>
                    <span className="tabular-nums">
                      {String(index + 1).padStart(2, "0")}
                    </span>
                    <span>{section.label}</span>
                    <strong
                      data-complete={section.isComplete}
                      aria-label={
                        section.isComplete
                          ? "Seção preenchida"
                          : "Seção pendente"
                      }
                    >
                      {section.isComplete ? "OK" : "—"}
                    </strong>
                  </a>
                </li>
              ))}
            </ol>
          </nav>
        </aside>

        <div className="rdo-document-surface">
          <section
            className="rdo-document-status"
            aria-label="Persistência do RDO"
          >
        <div className="rdo-document-status__identity">
          <span>Registro local</span>
          <strong className="tabular-nums">
            {draft.numeroRdo || `ID ${draft.id.slice(0, 8)}`}
          </strong>
        </div>
        <dl>
          <div>
            <dt>Estado do registro</dt>
            <dd>
              {localStatusState ? (
                <InstitutionalStatus
                  state={localStatusState}
                  label={formatLocalSyncStatus(draft.syncStatus)}
                />
              ) : (
                <span
                  className="institutional-status rdo-status-error"
                  data-state="ERROR"
                  role="status"
                >
                  {formatLocalSyncStatus(draft.syncStatus)}
                </span>
              )}
            </dd>
          </div>
          <div>
            <dt>Autossalvamento</dt>
            <dd>Não habilitado</dd>
          </div>
          <div>
            <dt>Cópia local</dt>
            <dd>
              {isSaving
                ? "Salvando neste dispositivo"
                : hasLocalCopy
                  ? "Salva neste dispositivo"
                  : "Ainda não salva"}
            </dd>
          </div>
          <div>
            <dt>Confirmação do servidor</dt>
            <dd>
              {draft.syncStatus === "SYNCED"
                ? "Confirmada"
                : "Ainda não confirmada"}
            </dd>
          </div>
        </dl>
          </section>

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

          <section className="form-card" id="rdo-identificacao">
        <div className="section-heading">
          <div>
            <h2>Identificação</h2>
          </div>
        </div>

        <div className="form-grid">
          <label>
            Número do RDO
            <input
              value={draft.numeroRdo}
              readOnly
              aria-readonly="true"
            />

            <small>
              O número é preenchido automaticamente.
            </small>
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

        </div>

        {/* A extensão programada saiu. Ela media o que a programação semanal
            previu, não o que a frente executou, e ficava no alto da tela ao
            lado de números que falam do dia — dois significados na mesma
            caixa. A extensão que interessa ao RDO é a dos trechos apontados,
            e essa o cartão do serviço já mostra. */}
        <div className="computed-grid rdo-extension-grid">
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

          <section className="form-card" id="rdo-condicoes">
        <div className="section-heading">
          <div>
            <h2>Condições do dia</h2>
          </div>
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
            Condição do dia
            <select
              value={draft.condicaoTrabalho}
              onChange={(event) =>
                updateField(
                  "condicaoTrabalho",
                  event.target.value as RdoDraft["condicaoTrabalho"],
                )
              }
            >
              <option value="">Selecione</option>
              <option value="PRATICAVEL">Praticável</option>
              <option value="IMPRATICAVEL">Impraticável</option>
            </select>
            {/*
              * Deixar em branco é dizer que ninguém declarou, e é assim que
              * fica gravado. Ausência não vira "praticável" por omissão: um
              * dia parado que o relatório conta como trabalhado some da
              * medição.
              */}
            <small>
              Se ficar em branco, o RDO registra que a condição não foi
              declarada.
            </small>
          </label>

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
            aria-describedby={observationBudgetId}
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
        <small
          id={observationBudgetId}
          data-testid="rdo-observation-budget"
          className={
            observationBudget.fits
              ? "rdo-observation-budget full-width"
              : "rdo-observation-budget rdo-observation-budget--over full-width"
          }
          aria-live="polite"
        >
          {`Caixa de observações do RDO: ${observationBudget.used} de ${observationBudget.capacity} linhas`}
          {observationBudget.fits
            ? ""
            : " · não cabe na folha; o RDO não exporta assim, e nada será cortado por conta própria"}
        </small>
      </section>

          <section className="form-card" id="rdo-fotos">
        <div className="section-heading collection-heading">
          <div>
            <h2>Fotos do RDO</h2>
          </div>

          <div className="section-actions">
            <p>
              Adicione até 5 fotos deste RDO.
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

          <section className="form-card" id="rdo-servicos">
        <CollectionHeader
          title="Serviços executados"
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
                  disabled={!priceCatalogSelectable}
                  disabledMessage={priceCoverageMessage}
                  onQueryChange={(value) =>
                    updateServicoExecutado(
                      item.localId,
                      {
                        serviceId: "",
                        priceVersionId: "",
                        servicoNome: value,
                      },
                    )
                  }
                  /*
                   * A unidade passou a vir sempre do catálogo, e não só quando
                   * o bloco estava vazio. Ela deixou de ser digitável, então
                   * preservar a que estava ali antes guardaria a unidade de
                   * outro serviço — e é ela que decide se a quantidade é
                   * comprimento, área ou volume.
                   */
                  onSelect={(serviceType) =>
                    updateServicoExecutado(
                      item.localId,
                      {
                        serviceId: serviceType.catalogId,
                        priceVersionId:
                          serviceType.priceChoices.length === 1
                            ? serviceType.priceChoices[0].id
                            : "",
                        servicoNome:
                          formatRdoServiceType(serviceType),
                        unidade:
                          unidadeUnicaDasOpcoesDePreco(
                            serviceType.priceChoices,
                          ) ?? "",
                      },
                    )
                  }
                  getKey={(serviceType) => serviceType.catalogId}
                  getTitle={getTipoServicoTitle}
                  getSubtitle={getTipoServicoSubtitle}
                />

                {servicoExecutadoNeedsCatalogSelection(item) ? (
                  <p className="notice notice-error" role="alert">
                    Selecione no catálogo um serviço com unidade válida antes de sincronizar esta linha.
                  </p>
                ) : null}

                <label>
                  Pista
                  <input
                    value={item.pista}
                    onChange={(event) =>
                      updateServicoExecutado(
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
                      updateServicoExecutado(
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
                  Km inicial
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
                  Km final
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

                <NumericField
                  label="Largura (m)"
                  value={item.larguraM}
                  onChange={(value) =>
                    updateServicoExecutado(item.localId, {
                      larguraM: value,
                    })
                  }
                />

                <NumericField
                  label="Espessura (m)"
                  value={item.espessuraM}
                  onChange={(value) =>
                    updateServicoExecutado(item.localId, {
                      espessuraM: value,
                    })
                  }
                />

                <MedidasDoServicoCalculadas item={item} />

                {/* Preço versionado, quantidade, unidade e turno do serviço
                    saíram da tela porque nenhum deles era pergunta para quem
                    aponta a frente. A unidade é do contrato e vem do catálogo;
                    o preço é a versão vigente na data, e escolhê-lo à mão era
                    convidar a escolher a errada; o turno do serviço repetia o
                    do RDO; e a quantidade é o que o trecho, a largura e a
                    espessura já dizem — digitá-la de novo só criava um segundo
                    número para divergir do primeiro. Todos continuam no
                    rascunho e continuam subindo. */}
              </div>

              <div className="rdo-servico-marcas">
                <label className="checkbox-field rdo-servico-marca">
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

                <label className="checkbox-field rdo-servico-marca">
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

          {/* O rateio de colaboradores saiu do RDO.

              Ele pedia, por pessoa e por linha, percentual do dia, tipo de
              alocação, centro de custo, função, turno e status — uma segunda
              folha de apontamento ao lado da mão de obra, preenchida pela
              mesma pessoa sobre as mesmas pessoas. Ninguém a preenchia, e o
              que dela se esperava (quem trabalhou, em que horário) a seção de
              Mão de obra já responde. O que ficou órfão foi um bloco que
              tornava o formulário mais longo sem tornar o dia mais descrito. */}

      <div id="rdo-mao-de-obra">
        <RdoWorkforceEditor
          draft={draft}
          collaborators={activeCreationContext?.colaboradores ?? []}
          catalogUnavailableMessage={
            workforceCatalogMessage || undefined
          }
          sourceRdoNumber={
            activeCreationContext?.previousRdo?.numeroRdo ?? null
          }
          jaApontados={apontadosHoje.pessoas}
          onChange={setDraft}
        />
      </div>

          <section className="form-card" id="rdo-equipamentos">
        <div className="section-heading collection-heading">
          <div>
            <h2>Equipamentos</h2>
          </div>
          <p>Marque as máquinas que trabalharam hoje.</p>
        </div>

        <RdoEquipmentPicker
          parque={parqueDaObra}
          equipamentos={draft.equipamentos}
          jaApontados={apontadosHoje.equipamentos}
          onChange={(equipamentos) =>
            setDraft((current) => ({ ...current, equipamentos }))
          }
        />
      </section>

          <section className="form-card" id="rdo-materiais">
        <CollectionHeader
          title="Materiais"
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


      {canViewRawPayload && showJson && (
        <section className="form-card json-preview">
          <div className="section-heading">
            <div>
              <h2>Payload gerado</h2>
            </div>
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

        {canViewRawPayload && (
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
        )}

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
        </div>
      </div>
    </main>
  );
}

interface CollectionHeaderProps {
  title: string;
  onAdd: () => void;
}

function CollectionHeader({
  title,
  onAdd,
}: CollectionHeaderProps) {
  return (
    <div className="section-heading collection-heading">
      <div>
        <h2>{title}</h2>
      </div>

      <div className="section-actions">
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
