import { useMemo, useRef, useState } from "react";

import { ProgramacaoSemanalImport } from "../programacoes/ProgramacaoSemanalImport";
import type {
  LocalRdoRecord,
  OperationalEventRecord,
  RdoAttachmentRecord,
} from "../../lib/db/db.types";
import { formatLocalSyncStatus } from "../../lib/db/syncStatusLabels";

interface RdoLocalListProps {
  records: LocalRdoRecord[];
  events: OperationalEventRecord[];
  attachments: RdoAttachmentRecord[];
  isLoading: boolean;
  error: string;
  onCreate: () => void;
  onImportRdoFile: (file: File) => void;
  isImporting: boolean;
  onOpen: (record: LocalRdoRecord) => void;
  onRefresh: () => void;
}

type PeriodFilter = "TODOS" | "HOJE" | "7_DIAS" | "30_DIAS";

type ProfileTarget =
  | { type: "OBRA"; id: string; label: string }
  | { type: "RDO"; id: string; label: string }
  | { type: "COLABORADOR"; id: string; label: string };

function formatDate(value: string): string {
  const parts = value.split("-");

  if (parts.length !== 3) {
    return value || "Sem data";
  }

  return `${parts[2]}/${parts[1]}/${parts[0]}`;
}

function formatDateTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return new Intl.DateTimeFormat("pt-BR", {
    dateStyle: "short",
    timeStyle: "short",
  }).format(date);
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

function eventLabel(event: OperationalEventRecord): string {
  return event.type
    .toLowerCase()
    .replace(/_/g, " ")
    .replace(/^./, (first) => first.toUpperCase());
}

function eventSyncLabel(
  status: OperationalEventRecord["syncStatus"],
): string {
  switch (status) {
    case "LOCAL_ONLY":
      return "Somente local";
    case "PENDING_SYNC":
      return "Pendente de sincronização";
    case "SYNCING":
      return "Sincronizando";
    case "SYNCED":
      return "Sincronizado";
    case "SYNC_FAILED":
      return "Falha ao sincronizar";
  }
}

export function RdoLocalList({
  records,
  events,
  attachments,
  isLoading,
  error,
  onCreate,
  onImportRdoFile,
  isImporting,
  onOpen,
  onRefresh,
}: RdoLocalListProps) {
  const importInputRef = useRef<HTMLInputElement | null>(null);
  const [obraFilter, setObraFilter] = useState("");
  const [periodFilter, setPeriodFilter] =
    useState<PeriodFilter>("TODOS");
  const [statusFilter, setStatusFilter] = useState("");
  const [syncFilter, setSyncFilter] = useState("");
  const [collaboratorFilter, setCollaboratorFilter] = useState("");
  const [trechoFilter, setTrechoFilter] = useState("");
  const [profile, setProfile] = useState<ProfileTarget | null>(null);

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

  const visibleIds = new Set(filteredRecords.map((record) => record.id));
  const visibleEvents = events.filter(
    (event) => !event.rdoId || visibleIds.has(event.rdoId),
  );

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
      <section className="rdo-command-band">
        <div>
          <p className="eyebrow">Stavias · Sistema Córtex</p>
          <h1>Relatórios Diários de Obra</h1>
          <span className="brand-tick" aria-hidden="true" />
          <p className="subtitle">
            RDOs locais, eventos ontológicos, fotos e status de
            sincronização em uma única visão operacional.
          </p>
        </div>

        <div className="rdo-command-actions">
          <button
            type="button"
            className="secondary-button"
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
            accept=".pdf,.xlsx,.xls,.xlsm,application/pdf,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.ms-excel,application/vnd.ms-excel.sheet.macroEnabled.12"
            className="visually-hidden"
            onChange={(event) => {
              const file = event.target.files?.[0];
              event.target.value = "";
              if (file) {
                onImportRdoFile(file);
              }
            }}
          />
        </div>
      </section>

      <section className="rdo-filter-grid">
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
      </section>

      {error && <div className="notice notice-error">{error}</div>}
      {isLoading && <div className="notice">Carregando RDOs locais...</div>}

      <section className="rdo-metric-grid">
        <MetricCard label="Trechos" value={metrics.trechos} />
        <MetricCard
          label="Metros concluídos"
          value={`${Math.round(metrics.metros).toLocaleString("pt-BR")} m`}
        />
        <MetricCard label="Em execução" value={metrics.emExecucao} />
        <MetricCard
          label="Equipe e equipamentos"
          value={`${metrics.pessoas}/${metrics.equipamentos}`}
        />
        <MetricCard label="Pendentes de sync" value={metrics.pendentes} />
        <MetricCard label="Com foto" value={metrics.comFoto} />
        <MetricCard label="Com ocorrência" value={metrics.comOcorrencia} />
      </section>

      <ProgramacaoSemanalImport onRdoCreated={onRefresh} />

      <section className="rdo-main-grid">
        <div className="rdo-list-column">
          {filteredRecords.length === 0 && !isLoading ? (
            <section className="form-card">
              <h2>Nenhum RDO encontrado</h2>
              <p>
                Ajuste os filtros ou crie um RDO para iniciar a
                timeline operacional.
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
                  <span
                    className={`status-badge status-badge--${record.syncStatus.toLowerCase()}`}
                  >
                    {formatLocalSyncStatus(record.syncStatus)}
                  </span>
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
                </div>
              </article>
            );
          })}
        </div>

        <aside className="rdo-side-panel">
          <section className="timeline-panel">
            <h2>Últimas atualizações operacionais</h2>
            <div className="timeline-list">
              {visibleEvents.slice(0, 8).map((event) => (
                <button
                  type="button"
                  className={`timeline-item timeline-item--${event.syncStatus.toLowerCase()}`}
                  key={event.id}
                  onClick={() => {
                    if (event.rdoId) {
                      setProfile({
                        type: "RDO",
                        id: event.rdoId,
                        label: event.rdoId,
                      });
                    }
                  }}
                >
                  <strong>{eventLabel(event)}</strong>
                  <span>{formatDateTime(event.occurredAt)}</span>
                  <small>{eventSyncLabel(event.syncStatus)}</small>
                </button>
              ))}
              {visibleEvents.length === 0 ? (
                <p className="muted-text">
                  Nenhum evento ontológico local ainda.
                </p>
              ) : null}
            </div>
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

function MetricCard({
  label,
  value,
}: {
  label: string;
  value: string | number;
}) {
  return (
    <article className="metric-card">
      <span>{label}</span>
      <strong>{value}</strong>
    </article>
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

      <div className="profile-summary-grid">
        <MetricCard label="RDOs" value={relatedRecords.length} />
        <MetricCard label="Eventos" value={relatedEvents.length} />
        <MetricCard label="Fotos" value={relatedAttachments.length} />
        <MetricCard
          label="Pendentes"
          value={
            relatedRecords.filter((record) => record.syncStatus !== "SYNCED")
              .length
          }
        />
      </div>

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

      <section>
        <h3>Timeline</h3>
        <div className="profile-list">
          {relatedEvents.slice(0, 12).map((event) => (
            <div className="profile-event" key={event.id}>
              <strong>{eventLabel(event)}</strong>
              <span>
                {formatDateTime(event.occurredAt)} ·{" "}
                {eventSyncLabel(event.syncStatus)}
              </span>
            </div>
          ))}
        </div>
      </section>
    </aside>
  );
}
