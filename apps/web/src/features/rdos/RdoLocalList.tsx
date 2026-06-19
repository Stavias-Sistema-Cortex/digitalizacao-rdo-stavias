import type { LocalRdoRecord } from "../../lib/db/db.types";

interface RdoLocalListProps {
  records: LocalRdoRecord[];
  isLoading: boolean;
  error: string;
  onCreate: () => void;
  onOpen: (record: LocalRdoRecord) => void;
  onRefresh: () => void;
}

function formatDate(value: string): string {
  const parts = value.split("-");

  if (parts.length !== 3) {
    return value || "Sem data";
  }

  return `${parts[2]}/${parts[1]}/${parts[0]}`;
}

export function RdoLocalList({
  records,
  isLoading,
  error,
  onCreate,
  onOpen,
  onRefresh,
}: RdoLocalListProps) {
  return (
    <main className="page-shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">
            Córtex · Operação de campo
          </p>

          <h1>RDOs locais</h1>

          <p className="subtitle">
            Relatórios armazenados neste dispositivo,
            disponíveis mesmo sem internet.
          </p>
        </div>

        <div className="workspace-actions">
          <button
            type="button"
            className="secondary-button"
            onClick={onRefresh}
            disabled={isLoading}
          >
            Atualizar
          </button>

          <button
            type="button"
            className="primary-button"
            onClick={onCreate}
          >
            Novo RDO
          </button>
        </div>
      </header>

      {error && (
        <div className="notice notice-error">
          {error}
        </div>
      )}

      {isLoading && (
        <div className="notice">
          Carregando RDOs locais...
        </div>
      )}

      {!isLoading && records.length === 0 && (
        <section className="form-card">
          <h2>Nenhum RDO salvo</h2>

          <p>
            Crie um relatório e salve localmente para
            que ele apareça aqui.
          </p>

          <button
            type="button"
            className="primary-button"
            onClick={onCreate}
          >
            Criar primeiro RDO
          </button>
        </section>
      )}

      <section className="local-rdo-grid">
        {records.map((record) => (
          <article
            className="form-card local-rdo-card"
            key={record.id}
          >
            <div className="local-rdo-card-header">
              <span className="status-badge">
                {record.syncStatus}
              </span>

              <span className="status-id">
                ID: {record.id.slice(0, 8)}
              </span>
            </div>

            <div>
              <h2>
                {record.numeroRdo || "RDO sem número"}
              </h2>

              <p className="subtitle">
                {formatDate(record.dataRdo)}
              </p>
            </div>

            <dl className="local-rdo-details">
              <div>
                <dt>Obra</dt>
                <dd>{record.obraId}</dd>
              </div>

              <div>
                <dt>Status</dt>
                <dd>{record.statusRdo}</dd>
              </div>

              <div>
                <dt>Atualizado</dt>
                <dd>
                  {new Date(
                    record.updatedAt,
                  ).toLocaleString("pt-BR")}
                </dd>
              </div>
            </dl>

            <button
              type="button"
              className="primary-button"
              onClick={() => onOpen(record)}
              disabled={record.statusRdo === "ENVIADO"}
            >
              {record.statusRdo === "ENVIADO"
                ? "RDO enviado"
                : "Continuar RDO"}
            </button>
          </article>
        ))}
      </section>
    </main>
  );
}
