import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type Ref,
} from "react";

import { InstitutionalPageHeader } from "../../components/institutional/InstitutionalPageHeader";
import {
  InstitutionalStatus,
  type InstitutionalStatusState,
} from "../../components/institutional/InstitutionalStatus";
import { SyncStateStrip } from "../../components/institutional/SyncStateStrip";
import { TraceReference } from "../../components/institutional/TraceReference";
import { ProgramacaoSemanalImport } from "../programacoes/ProgramacaoSemanalImport";
import { memoryHref } from "../home/memory/memoryLocation";
import type {
  LocalSyncStatus,
  LocalRdoRecord,
  OperationalEventRecord,
  RdoAttachmentRecord,
} from "../../lib/db/db.types";
import { formatLocalSyncStatus } from "../../lib/db/syncStatusLabels";
import { useSyncStatus } from "../../lib/sync/useSyncStatus";
import {
  listCachedAuthorizedRdoWorksites,
} from "./rdoCreationContextRepository";
import {
  localRdoExportAvailability,
  rdoWorkbookSnapshotFromLocalRecord,
  type RdoExportAvailability,
} from "./export/rdoWorkbookMapping";
import { RDO_IMPORT_ACCEPT } from "./rdoImportPolicy";
import { localRdoPdfExportAvailability } from "./export/rdoPdfAvailability";
import {
  assertRdoExportSessionGuard,
  captureRdoExportSessionGuard,
  RDO_EXPORT_SESSION_CHANGED_MESSAGE,
  type RdoExportSessionGuard,
} from "./rdoExportSessionGuard";
import type { RdoExportDownloadPermit } from "./export/rdoExportDownload";
import {
  preflightRdoImportFile,
} from "../../lib/files/rdoImportResourcePolicy";

interface RdoLocalListProps {
  records: LocalRdoRecord[];
  events: OperationalEventRecord[];
  attachments: RdoAttachmentRecord[];
  exportSessionGuard?: RdoExportSessionGuard | null;
  isLoading: boolean;
  error: string;
  onCreate: () => void;
  onImportRdoFile: (file: File) => void;
  isImporting: boolean;
  onOpen: (record: LocalRdoRecord) => void;
  onDiscardRejected?: (record: LocalRdoRecord) => void;
  discardingRdoId?: string | null;
  onRefresh: () => void;
  createButtonRef?: Ref<HTMLButtonElement>;
}

type PeriodFilter = "TODOS" | "HOJE" | "7_DIAS" | "30_DIAS";
type RdoExportFormat = "XLSX" | "PDF";
type RdoExportNotice = {
  message: string;
  isError: boolean;
};
type RdoExportSummary = {
  parts: string[];
  isError: boolean;
};
type ProfileTarget =
  | { type: "OBRA"; id: string; label: string }
  | { type: "RDO"; id: string; label: string }
  | { type: "COLABORADOR"; id: string; label: string };

function exportStateSummary(
  record: LocalRdoRecord,
  xlsxAvailability: RdoExportAvailability | undefined,
  pdfAvailability: RdoExportAvailability | undefined,
  notices: Partial<Record<RdoExportFormat, RdoExportNotice>> | undefined,
): RdoExportSummary {
  const formats = [
    { format: "XLSX", availability: xlsxAvailability },
    { format: "PDF", availability: pdfAvailability },
  ] as const;
  const origin =
    record.syncStatus === "SYNCED" && record.versaoEntidade !== null
      ? "Origem da exportação · Servidor · cópia local disponível offline"
      : "Origem da exportação · Dados locais pendentes · disponível offline";
  const allReady = formats.every(({ availability }) => availability?.ready);
  const allUnavailable = formats.every(
    ({ availability }) => !availability?.ready,
  );
  const unavailableMessages = formats.map(
    ({ availability }) =>
      availability?.message ?? "Exportação indisponível",
  );
  const sharedUnavailableMessage =
    allUnavailable &&
    unavailableMessages[0] === unavailableMessages[1]
      ? unavailableMessages[0]
      : null;

  let availabilityParts: string[];
  if (allReady) {
    availabilityParts = ["XLSX e PDF disponíveis"];
  } else if (sharedUnavailableMessage) {
    availabilityParts = [
      `XLSX e PDF indisponíveis: ${sharedUnavailableMessage}`,
    ];
  } else {
    availabilityParts = formats.map(({ format, availability }) =>
      availability?.ready
        ? `${format} disponível`
        : `${format} indisponível: ${
            availability?.message ?? "Exportação indisponível"
          }`,
    );
  }
  const noticeParts = formats.flatMap(({ format }) => {
    const notice = notices?.[format];
    return notice ? [notice.message] : [];
  });

  return {
    parts: [origin, ...availabilityParts, ...noticeParts],
    isError: formats.some(
      ({ format }) => notices?.[format]?.isError === true,
    ),
  };
}

function formatDate(value: string): string {
  const parts = value.split("-");

  if (parts.length !== 3) {
    return value || "Sem data";
  }

  return `${parts[2]}/${parts[1]}/${parts[0]}`;
}

function asObject(value: unknown): Record<string, unknown> {
  return typeof value === "object" &&
    value !== null &&
    !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}

function asArray(value: unknown): Record<string, unknown>[] {
  return Array.isArray(value)
    ? value
        .map(asObject)
        .filter((item) => Object.keys(item).length > 0)
    : [];
}

function asText(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function asNumber(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) {
    return value;
  }

  if (typeof value === "string" && value.trim()) {
    const parsed = Number(value.replace(",", "."));
    return Number.isFinite(parsed) ? parsed : null;
  }

  return null;
}

function normalize(value: string): string {
  return value
    .normalize("NFD")
    .replace(/\p{Diacritic}/gu, "")
    .toLowerCase();
}

function unique(values: string[]): string[] {
  const seen = new Set<string>();
  const result: string[] = [];

  for (const value of values) {
    const clean = value.trim();
    if (!clean) {
      continue;
    }

    const key = normalize(clean);
    if (!seen.has(key)) {
      seen.add(key);
      result.push(clean);
    }
  }

  return result;
}

function payload(record: LocalRdoRecord): Record<string, unknown> {
  return asObject(record.payload);
}

function collaborators(record: LocalRdoRecord): Array<{
  id: string;
  label: string;
}> {
  const data = payload(record);
  const items = [
    ...asArray(data.alocacoesColaboradores).map((item) => ({
      id: asText(item.colaboradorId),
      label:
        asText(item.nomeColaborador) ||
        asText(item.equipe) ||
        asText(item.funcao) ||
        asText(item.colaboradorId),
    })),
    ...asArray(data.maoObra).map((item) => ({
      id: asText(item.colaboradorId),
      label:
        asText(item.nomeColaborador) ||
        asText(item.cargo) ||
        asText(item.colaboradorId),
    })),
  ];

  const byKey = new Map<string, { id: string; label: string }>();
  for (const item of items) {
    const key = item.id || item.label;
    if (key) {
      byKey.set(key, item);
    }
  }

  return Array.from(byKey.values()).filter((item) => item.label);
}

function equipmentLabels(record: LocalRdoRecord): string[] {
  return unique(
    asArray(payload(record).equipamentos).map(
      (item) =>
        asText(item.prefixo) ||
        asText(item.descricao) ||
        asText(item.tipoEquipamento),
    ),
  );
}

function controls(record: LocalRdoRecord): Record<string, unknown>[] {
  return asArray(payload(record).controlesGeometricos);
}

function services(record: LocalRdoRecord): Record<string, unknown>[] {
  return asArray(payload(record).servicosExecutados);
}

function recordSearchText(record: LocalRdoRecord): string {
  const data = payload(record);
  return normalize(
    [
      record.id,
      record.obraId,
      record.numeroRdo,
      record.dataRdo,
      asText(data.cliente),
      asText(data.contrato),
      asText(data.rodovia),
      asText(data.cidade),
      asText(data.observacoes),
      ...collaborators(record).map((item) => item.label),
      ...equipmentLabels(record),
      ...controls(record).flatMap((item) => [
        asText(item.subtrecho),
        asText(item.numero),
        asText(item.kmInicial),
        asText(item.kmFinal),
      ]),
    ].join(" "),
  );
}

function isInPeriod(record: LocalRdoRecord, period: PeriodFilter): boolean {
  if (period === "TODOS") {
    return true;
  }

  const date = new Date(`${record.dataRdo}T00:00:00`);
  if (Number.isNaN(date.getTime())) {
    return false;
  }

  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const diffDays = Math.floor(
    (today.getTime() - date.getTime()) / 86_400_000,
  );

  if (period === "HOJE") {
    return diffDays === 0;
  }

  if (period === "7_DIAS") {
    return diffDays >= 0 && diffDays <= 7;
  }

  return diffDays >= 0 && diffDays <= 30;
}

function lengthMeters(record: LocalRdoRecord): number {
  return controls(record).reduce((total, item) => {
    const explicit = asNumber(item.comprimentoM);
    if (explicit !== null) {
      return total + explicit;
    }

    const start = asNumber(item.kmInicial);
    const end = asNumber(item.kmFinal);
    if (start === null || end === null || end < start) {
      return total;
    }

    return total + (end - start) * 1000;
  }, 0);
}

function hasOccurrence(
  record: LocalRdoRecord,
  events: OperationalEventRecord[],
): boolean {
  const text = normalize(
    [
      asText(payload(record).observacoes),
      ...services(record).map((item) => asText(item.observacoes)),
    ].join(" "),
  );

  return (
    /ocorr|problema|inciden|paralis|chuva|rejeitad/.test(text) ||
    events.some(
      (event) =>
        event.rdoId === record.id &&
        event.type === "OCORRENCIA_REGISTRADA",
    )
  );
}

function institutionalState(
  status: LocalSyncStatus,
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

function RdoSyncStatus({ status }: { status: LocalSyncStatus }) {
  const state = institutionalState(status);

  return state ? (
    <InstitutionalStatus
      state={state}
      label={formatLocalSyncStatus(status)}
    />
  ) : (
    <span
      className="institutional-status rdo-status-error"
      data-state="ERROR"
      role="status"
    >
      {formatLocalSyncStatus(status)}
    </span>
  );
}

export function RdoLocalList({
  records,
  events,
  attachments,
  exportSessionGuard,
  isLoading,
  error,
  onCreate,
  onImportRdoFile,
  isImporting,
  onOpen,
  onDiscardRejected,
  discardingRdoId = null,
  onRefresh,
  createButtonRef,
}: RdoLocalListProps) {
  const { snapshot } = useSyncStatus();
  const importInputRef = useRef<HTMLInputElement | null>(null);
  const renderedSessionGuardRef = useRef<RdoExportSessionGuard | null>(null);
  if (renderedSessionGuardRef.current === null) {
    try {
      renderedSessionGuardRef.current = captureRdoExportSessionGuard();
    } catch {
      // A missing guard remains fail-closed when an export is requested.
    }
  }
  const [obraFilter, setObraFilter] = useState("");
  const [periodFilter, setPeriodFilter] =
    useState<PeriodFilter>("TODOS");
  const [statusFilter, setStatusFilter] = useState("");
  const [syncFilter, setSyncFilter] = useState("");
  const [collaboratorFilter, setCollaboratorFilter] = useState("");
  const [trechoFilter, setTrechoFilter] = useState("");
  const [profile, setProfile] = useState<ProfileTarget | null>(null);
  const worksiteRequestKey = useMemo(
    () =>
      [...new Set(records.map((record) => record.obraId))]
        .sort()
        .join("|"),
    [records],
  );
  const [worksiteLoad, setWorksiteLoad] = useState<{
    key: string;
    worksites: Map<
      string,
      {
        id: string;
        nome: string;
        codigoContrato: string;
      }
    >;
    error: string;
  }>({ key: "", worksites: new Map(), error: "" });
  const isLoadingWorksites = worksiteLoad.key !== worksiteRequestKey;
  const cachedWorksites = worksiteLoad.worksites;
  const worksiteError = isLoadingWorksites ? "" : worksiteLoad.error;
  const [exporting, setExporting] = useState<{
    rdoId: string;
    format: RdoExportFormat;
  } | null>(null);
  const [exportNotices, setExportNotices] = useState<
    Record<string, Partial<Record<RdoExportFormat, RdoExportNotice>>>
  >({});
  const [importPreflightError, setImportPreflightError] = useState("");

  useEffect(() => {
    let active = true;
    void listCachedAuthorizedRdoWorksites()
      .then((worksites) => {
        if (!active) return;
        setWorksiteLoad({
          key: worksiteRequestKey,
          worksites: new Map(
            worksites.map((worksite) => [
              worksite.id,
              {
                id: worksite.id,
                nome: worksite.nome,
                codigoContrato: worksite.codigoContrato,
              },
            ]),
          ),
          error: "",
        });
      })
      .catch((caught: unknown) => {
        if (!active) return;
        setWorksiteLoad({
          key: worksiteRequestKey,
          worksites: new Map(),
          error:
            caught instanceof Error
              ? caught.message
              : "Não foi possível validar as obras disponíveis offline.",
        });
      });

    return () => {
      active = false;
    };
  }, [worksiteRequestKey]);

  const exportAvailabilityByRdo = useMemo(() => {
    const statuses = new Map<string, RdoExportAvailability>();
    for (const record of records) {
      if (isLoadingWorksites) {
        statuses.set(record.id, {
          ready: false,
          code: null,
          message: "Verificando o snapshot local…",
        });
      } else if (worksiteError) {
        statuses.set(record.id, {
          ready: false,
          code: null,
          message: `Obra offline indisponível: ${worksiteError}`,
        });
      } else {
        statuses.set(
          record.id,
          localRdoExportAvailability(
            record,
            cachedWorksites.get(record.obraId),
          ),
        );
      }
    }
    return statuses;
  }, [cachedWorksites, isLoadingWorksites, records, worksiteError]);

  const pdfExportAvailabilityByRdo = useMemo(() => {
    const statuses = new Map<string, RdoExportAvailability>();
    for (const record of records) {
      const availability = exportAvailabilityByRdo.get(record.id);
      if (!availability?.ready) {
        statuses.set(record.id, availability ?? {
          ready: false,
          code: null,
          message: "Exportação PDF indisponível",
        });
      } else {
        statuses.set(
          record.id,
          localRdoPdfExportAvailability(
            record,
            cachedWorksites.get(record.obraId),
          ),
        );
      }
    }
    return statuses;
  }, [
    cachedWorksites,
    exportAvailabilityByRdo,
    records,
  ]);

  function rememberExportNotice(
    rdoId: string,
    format: RdoExportFormat,
    notice: RdoExportNotice,
  ) {
    setExportNotices((current) => ({
      ...current,
      [rdoId]: {
        ...current[rdoId],
        [format]: notice,
      },
    }));
  }

  async function handleExport(
    record: LocalRdoRecord,
    format: RdoExportFormat,
  ) {
    const availability = format === "PDF"
      ? pdfExportAvailabilityByRdo.get(record.id)
      : exportAvailabilityByRdo.get(record.id);
    if (!availability?.ready || exporting) return;

    setExporting({ rdoId: record.id, format });
    try {
      const guard = exportSessionGuard ?? renderedSessionGuardRef.current;
      if (!guard) {
        throw new Error(RDO_EXPORT_SESSION_CHANGED_MESSAGE);
      }
      assertRdoExportSessionGuard(guard, record.obraId);
      const snapshot = rdoWorkbookSnapshotFromLocalRecord(
        record,
        cachedWorksites.get(record.obraId),
      );
      const useAuthoritativeServer =
        typeof navigator !== "undefined" &&
        navigator.onLine &&
        record.syncStatus === "SYNCED" &&
        record.versaoEntidade !== null;
      const downloadPermit: RdoExportDownloadPermit = {
        assertCurrentAuthorization: () => {
          assertRdoExportSessionGuard(guard, record.obraId);
          return true;
        },
      };

      if (useAuthoritativeServer) {
        if (format === "PDF") {
          const { downloadAuthoritativeRdoPdf } = await import("./export/exportRdoPdf");
          assertRdoExportSessionGuard(guard, record.obraId);
          await downloadAuthoritativeRdoPdf(snapshot, downloadPermit);
        } else {
          const { downloadAuthoritativeRdoWorkbook } = await import("./export/exportRdoWorkbook");
          assertRdoExportSessionGuard(guard, record.obraId);
          await downloadAuthoritativeRdoWorkbook(snapshot, downloadPermit);
        }
      } else {
        if (format === "PDF") {
          const { downloadRdoPdf } = await import("./export/exportRdoPdf");
          assertRdoExportSessionGuard(guard, record.obraId);
          await downloadRdoPdf(snapshot, downloadPermit);
        } else {
          const { downloadRdoWorkbook } = await import("./export/exportRdoWorkbook");
          assertRdoExportSessionGuard(guard, record.obraId);
          await downloadRdoWorkbook(snapshot, downloadPermit);
        }
      }

      rememberExportNotice(record.id, format, {
        message: useAuthoritativeServer
          ? `${format} autorizado pelo servidor baixado.`
          : record.syncStatus === "SYNCED"
            ? `${format} gerado com a cópia local disponível offline.`
            : `${format} local gerado; o RDO ainda está pendente de sincronização.`,
        isError: false,
      });
    } catch (caught: unknown) {
      rememberExportNotice(record.id, format, {
        message:
          caught instanceof Error
            ? `${format}: ${caught.message}`
            : `Não foi possível exportar o RDO em ${format}.`,
        isError: true,
      });
    } finally {
      setExporting(null);
    }
  }

  const attachmentsByRdo = useMemo(() => {
    const grouped = new Map<string, RdoAttachmentRecord[]>();
    for (const attachment of attachments) {
      grouped.set(attachment.rdoId, [
        ...(grouped.get(attachment.rdoId) ?? []),
        attachment,
      ]);
    }
    return grouped;
  }, [attachments]);

  const filteredRecords = useMemo(() => {
    const obraNeedle = normalize(obraFilter);
    const collaboratorNeedle = normalize(collaboratorFilter);
    const trechoNeedle = normalize(trechoFilter);

    return records.filter((record) => {
      if (!isInPeriod(record, periodFilter)) {
        return false;
      }

      if (statusFilter && record.statusRdo !== statusFilter) {
        return false;
      }

      if (syncFilter && record.syncStatus !== syncFilter) {
        return false;
      }

      const searchText = recordSearchText(record);

      if (obraNeedle && !searchText.includes(obraNeedle)) {
        return false;
      }

      if (
        collaboratorNeedle &&
        !collaborators(record).some((item) =>
          normalize(`${item.id} ${item.label}`).includes(
            collaboratorNeedle,
          ),
        )
      ) {
        return false;
      }

      if (
        trechoNeedle &&
        !controls(record).some((item) =>
          normalize(
            [
              asText(item.subtrecho),
              asText(item.numero),
              asText(item.kmInicial),
              asText(item.kmFinal),
              asText(item.pista),
              asText(item.faixa),
            ].join(" "),
          ).includes(trechoNeedle),
        )
      ) {
        return false;
      }

      return true;
    });
  }, [
    records,
    obraFilter,
    collaboratorFilter,
    trechoFilter,
    periodFilter,
    statusFilter,
    syncFilter,
  ]);

  const metrics = {
    trechos: filteredRecords.reduce(
      (total, record) => total + controls(record).length,
      0,
    ),
    metros: filteredRecords.reduce(
      (total, record) => total + lengthMeters(record),
      0,
    ),
    emExecucao: filteredRecords.filter(
      (record) => record.statusRdo === "RASCUNHO",
    ).length,
    pessoas: unique(
      filteredRecords.flatMap((record) =>
        collaborators(record).map((item) => item.label),
      ),
    ).length,
    equipamentos: unique(filteredRecords.flatMap(equipmentLabels)).length,
    pendentes: filteredRecords.filter(
      (record) => record.syncStatus !== "SYNCED",
    ).length,
    comFoto: filteredRecords.filter(
      (record) => (attachmentsByRdo.get(record.id) ?? []).length > 0,
    ).length,
    comOcorrencia: filteredRecords.filter((record) =>
      hasOccurrence(record, events),
    ).length,
  };

  return (
    <main className="rdo-dashboard">
      <InstitutionalPageHeader
        eyebrow="Operação de campo"
        title="Relatórios Diários de Obra"
        actions={(
          <div className="rdo-command-actions">
            <button
              ref={createButtonRef}
              type="button"
              className="primary-button"
              onClick={onCreate}
            >
              Novo RDO
            </button>
            <button
              type="button"
              className="secondary-button"
              onClick={() => importInputRef.current?.click()}
              disabled={isImporting}
            >
              {isImporting ? "Importando..." : "Importar RDO"}
            </button>
            <input
              ref={importInputRef}
              type="file"
              accept={RDO_IMPORT_ACCEPT}
              className="visually-hidden"
              onChange={(event) => {
                const file = event.target.files?.[0];
                event.target.value = "";
                if (file) {
                  try {
                    preflightRdoImportFile(file);
                    setImportPreflightError("");
                    onImportRdoFile(file);
                  } catch (caught: unknown) {
                    setImportPreflightError(
                      caught instanceof Error
                        ? caught.message
                        : "O arquivo de RDO não pôde ser validado.",
                    );
                  }
                }
              }}
            />
          </div>
        )}
      />

      <SyncStateStrip
        snapshot={snapshot}
        className="rdo-canonical-sync"
      />

      <section
        className="rdo-filter-region"
        aria-label="Filtros de RDO"
      >
        <div className="rdo-filter-grid">
          <label>
            Obra
            <input
              value={obraFilter}
              onChange={(event) => setObraFilter(event.target.value)}
              placeholder="ID, contrato, cidade ou cliente"
            />
          </label>

          <label>
            Período
            <select
              value={periodFilter}
              onChange={(event) =>
                setPeriodFilter(event.target.value as PeriodFilter)
              }
            >
              <option value="TODOS">Todos</option>
              <option value="HOJE">Hoje</option>
              <option value="7_DIAS">Últimos 7 dias</option>
              <option value="30_DIAS">Últimos 30 dias</option>
            </select>
          </label>

          <label>
            Status
            <select
              value={statusFilter}
              onChange={(event) => setStatusFilter(event.target.value)}
            >
              <option value="">Todos</option>
              <option value="RASCUNHO">Rascunho</option>
              <option value="ENVIADO">Enviado</option>
            </select>
          </label>

          <label>
            Colaborador
            <input
              value={collaboratorFilter}
              onChange={(event) =>
                setCollaboratorFilter(event.target.value)
              }
              placeholder="Nome, equipe ou ID"
            />
          </label>

          <label>
            Trecho
            <input
              value={trechoFilter}
              onChange={(event) => setTrechoFilter(event.target.value)}
              placeholder="Subtrecho, caixa, KM, pista"
            />
          </label>

          <label>
            Sync
            <select
              value={syncFilter}
              onChange={(event) => setSyncFilter(event.target.value)}
            >
              <option value="">Todos</option>
              <option value="LOCAL_ONLY">Somente local</option>
              <option value="PENDING_SYNC">Pendente</option>
              <option value="SYNCING">Sincronizando</option>
              <option value="SYNCED">Sincronizado</option>
              <option value="ERROR">Erro</option>
              <option value="CONFLICT">Conflito</option>
            </select>
          </label>
        </div>
      </section>

      {error && <div className="notice notice-error">{error}</div>}
      {importPreflightError && (
        <div className="notice notice-error">{importPreflightError}</div>
      )}
      {isLoading && <div className="notice">Carregando RDOs locais...</div>}

      <section
        className="rdo-register-summary institutional-frame"
        aria-label="Resumo dos documentos filtrados"
      >
        <h2>Resumo do registro</h2>
        <dl>
          <div>
            <dt>Trechos</dt>
            <dd>{metrics.trechos}</dd>
          </div>
          <div>
            <dt>Metros concluídos</dt>
            <dd>
              {Math.round(metrics.metros).toLocaleString("pt-BR")} m
            </dd>
          </div>
          <div>
            <dt>Em execução</dt>
            <dd>{metrics.emExecucao}</dd>
          </div>
          <div>
            <dt>Equipe / equipamentos</dt>
            <dd>{metrics.pessoas}/{metrics.equipamentos}</dd>
          </div>
          <div>
            <dt>Pendentes de sync</dt>
            <dd>{metrics.pendentes}</dd>
          </div>
          <div>
            <dt>Com foto</dt>
            <dd>{metrics.comFoto}</dd>
          </div>
          <div>
            <dt>Com ocorrência</dt>
            <dd>{metrics.comOcorrencia}</dd>
          </div>
        </dl>
      </section>

      <ProgramacaoSemanalImport onRdoCreated={onRefresh} />

      <section className="rdo-main-grid">
        <div className="rdo-list-column">
          {filteredRecords.length === 0 && !isLoading ? (
            <section className="form-card">
              <h2>Nenhum RDO encontrado</h2>
              <p>
                Ajuste os filtros ou crie um RDO para iniciar a
                sequência operacional.
              </p>
              <button
                type="button"
                className="primary-button"
                onClick={onCreate}
              >
                Criar RDO
              </button>
            </section>
          ) : null}

          {filteredRecords.map((record) => {
            const data = payload(record);
            const rdoAttachments = attachmentsByRdo.get(record.id) ?? [];
            const people = collaborators(record);
            const eventCount = events.filter(
              (event) => event.rdoId === record.id,
            ).length;
            const latestEvent = events
              .filter((event) => event.rdoId === record.id)
              .sort((left, right) =>
                right.occurredAt.localeCompare(left.occurredAt),
              )[0];
            const exportAvailability = exportAvailabilityByRdo.get(record.id);
            const pdfExportAvailability = pdfExportAvailabilityByRdo.get(record.id);
            const exportState = exportStateSummary(
              record,
              exportAvailability,
              pdfExportAvailability,
              exportNotices[record.id],
            );
            const exportStateId = `rdo-export-state-${record.id}`;

            return (
              <article className="rdo-operational-card" key={record.id}>
                <div className="rdo-card-heading">
                  <button
                    type="button"
                    className="link-button rdo-title-button"
                    onClick={() =>
                      setProfile({
                        type: "RDO",
                        id: record.id,
                        label: record.numeroRdo || record.id,
                      })
                    }
                  >
                    {record.numeroRdo || "RDO sem número"}
                  </button>
                  <RdoSyncStatus status={record.syncStatus} />
                </div>

                <div className="rdo-card-subtitle">
                  <button
                    type="button"
                    className="link-button"
                    onClick={() =>
                      setProfile({
                        type: "OBRA",
                        id: record.obraId,
                        label: asText(data.contrato) || record.obraId,
                      })
                    }
                  >
                    {asText(data.contrato) || record.obraId}
                  </button>
                  <span>{formatDate(record.dataRdo)}</span>
                  <span>{asText(data.cidade) || "Sem cidade"}</span>
                </div>

                <div className="rdo-card-facts">
                  <div className="rdo-fact">
                    <small>Trechos</small>
                    <strong>{controls(record).length}</strong>
                  </div>
                  <div className="rdo-fact">
                    <small>Extensão</small>
                    <strong>
                      {Math.round(lengthMeters(record)).toLocaleString(
                        "pt-BR",
                      )}{" "}
                      m
                    </strong>
                  </div>
                  <div className="rdo-fact">
                    <small>Equipamentos</small>
                    <strong>{equipmentLabels(record).length}</strong>
                  </div>
                  <div className="rdo-fact">
                    <small>Fotos</small>
                    <strong>{rdoAttachments.length}</strong>
                  </div>
                  <div className="rdo-fact">
                    <small>Eventos</small>
                    <strong>{eventCount}</strong>
                  </div>
                </div>

                <div className="entity-chip-row">
                  {people.slice(0, 5).map((person) => (
                    <button
                      type="button"
                      className="entity-chip"
                      key={`${record.id}:${person.id || person.label}`}
                      onClick={() =>
                        setProfile({
                          type: "COLABORADOR",
                          id: person.id || person.label,
                          label: person.label,
                        })
                      }
                    >
                      {person.label}
                    </button>
                  ))}
                </div>

                <div className="rdo-card-actions">
                  <div className="rdo-export-action">
                    <button
                      type="button"
                      className="secondary-button"
                      aria-describedby={exportStateId}
                      onClick={() => void handleExport(record, "XLSX")}
                      disabled={!exportAvailability?.ready || Boolean(exporting)}
                      title={exportAvailability?.message}
                    >
                      {exporting?.rdoId === record.id && exporting.format === "XLSX"
                        ? "Gerando XLSX…"
                        : "Exportar XLSX"}
                    </button>
                    <button
                      type="button"
                      className="secondary-button"
                      aria-describedby={exportStateId}
                      onClick={() => void handleExport(record, "PDF")}
                      disabled={!pdfExportAvailability?.ready || Boolean(exporting)}
                      title={pdfExportAvailability?.message}
                    >
                      {exporting?.rdoId === record.id && exporting.format === "PDF"
                        ? "Gerando PDF…"
                        : "Exportar PDF"}
                    </button>
                    <small
                      id={exportStateId}
                      data-testid={exportStateId}
                      className={
                        exportState.isError
                          ? "rdo-export-state rdo-export-state--error"
                          : "rdo-export-state"
                    }
                      aria-live="polite"
                    >
                      {exportState.parts.join(" · ")}
                    </small>
                  </div>
                  <button
                    type="button"
                    className="secondary-button"
                    onClick={() => onOpen(record)}
                    disabled={record.statusRdo === "ENVIADO"}
                  >
                    {record.statusRdo === "ENVIADO"
                      ? "RDO enviado"
                      : "Continuar RDO"}
                  </button>
                  {record.syncStatus === "ERROR" &&
                  onDiscardRejected ? (
                    <button
                      type="button"
                      className="secondary-button rdo-discard-button"
                      onClick={() => onDiscardRejected(record)}
                      disabled={discardingRdoId === record.id}
                    >
                      {discardingRdoId === record.id
                        ? "Excluindo..."
                        : "Excluir RDO rejeitado"}
                    </button>
                  ) : null}
                  {latestEvent ? (
                    <TraceReference
                      eventId={latestEvent.id}
                      entityId={record.id}
                    />
                  ) : (
                    <a
                      className="rdo-memory-link"
                      href={memoryHref({
                        obraId: record.obraId,
                        rdoId: record.id,
                        entityType: "RDO",
                        entityId: record.id,
                      })}
                    >
                      Abrir ficha na Memória
                    </a>
                  )}
                </div>
              </article>
            );
          })}
        </div>

        <aside className="rdo-side-panel">
          <section className="rdo-memory-link-panel">
            <span className="eyebrow">Registro central</span>
            <h2>Memória operacional</h2>
            <a href={memoryHref()}>Abrir Memória</a>
          </section>
        </aside>
      </section>

      {profile ? (
        <ProfileDrawer
          profile={profile}
          records={records}
          events={events}
          attachments={attachments}
          onClose={() => setProfile(null)}
          onOpenRdo={(record) => onOpen(record)}
        />
      ) : null}
    </main>
  );
}

function ProfileDrawer({
  profile,
  records,
  events,
  attachments,
  onClose,
  onOpenRdo,
}: {
  profile: ProfileTarget;
  records: LocalRdoRecord[];
  events: OperationalEventRecord[];
  attachments: RdoAttachmentRecord[];
  onClose: () => void;
  onOpenRdo: (record: LocalRdoRecord) => void;
}) {
  const relatedRecords = records.filter((record) => {
    if (profile.type === "RDO") {
      return record.id === profile.id;
    }

    if (profile.type === "OBRA") {
      return record.obraId === profile.id;
    }

    return collaborators(record).some(
      (person) => person.id === profile.id || person.label === profile.id,
    );
  });
  const relatedIds = new Set(relatedRecords.map((record) => record.id));
  const relatedEvents = events.filter((event) => {
    if (profile.type === "RDO") {
      return event.rdoId === profile.id || event.principalEntity.id === profile.id;
    }

    if (profile.type === "OBRA") {
      return event.obraId === profile.id || event.principalEntity.id === profile.id;
    }

    return event.colaboradorId === profile.id || relatedIds.has(event.rdoId ?? "");
  });
  const relatedAttachments = attachments.filter((attachment) =>
    relatedIds.has(attachment.rdoId),
  );

  return (
    <aside className="profile-drawer" aria-label="Perfil ontológico">
      <div className="profile-drawer-header">
        <div>
          <span>{profile.type}</span>
          <h2>{profile.label}</h2>
        </div>
        <button type="button" className="icon-button" onClick={onClose}>
          ×
        </button>
      </div>

      <dl className="profile-summary-grid rdo-profile-summary">
        <div>
          <dt>RDOs</dt>
          <dd>{relatedRecords.length}</dd>
        </div>
        <div>
          <dt>Eventos</dt>
          <dd>{relatedEvents.length}</dd>
        </div>
        <div>
          <dt>Fotos</dt>
          <dd>{relatedAttachments.length}</dd>
        </div>
        <div>
          <dt>Pendentes</dt>
          <dd>
            {
              relatedRecords.filter(
                (record) => record.syncStatus !== "SYNCED",
              ).length
            }
          </dd>
        </div>
      </dl>

      <section>
        <h3>RDOs relacionados</h3>
        <div className="profile-list">
          {relatedRecords.map((record) => (
            <button
              type="button"
              key={record.id}
              onClick={() => onOpenRdo(record)}
            >
              <strong>{record.numeroRdo || "RDO sem número"}</strong>
              <span>
                {formatDate(record.dataRdo)} ·{" "}
                {formatLocalSyncStatus(record.syncStatus)}
              </span>
            </button>
          ))}
        </div>
      </section>

      <section className="profile-memory-link">
        <h3>Memória da entidade</h3>
        <p>
          {relatedEvents.length} registro{relatedEvents.length === 1 ? "" : "s"}
          {" "}localmente conhecido{relatedEvents.length === 1 ? "" : "s"}.
        </p>
        <a
          href={memoryHref({
            obraId: profile.type === "OBRA" ? profile.id : undefined,
            rdoId: profile.type === "RDO" ? profile.id : undefined,
            entityType: profile.type,
            entityId: profile.id,
          })}
        >
          Ver alterações na Memória
        </a>
      </section>
    </aside>
  );
}
