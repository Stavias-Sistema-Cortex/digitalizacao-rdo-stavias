import { Link } from "react-router-dom";

import type { OperationalEventRecord } from "../../lib/db/db.types";

interface MemorySummaryCardProps {
  events: OperationalEventRecord[];
  obraId: string | null;
}

export function MemorySummaryCard({
  events,
  obraId,
}: MemorySummaryCardProps) {
  const pending = events.filter(
    (event) => event.syncStatus !== "SYNCED",
  ).length;
  const search = new URLSearchParams({ tab: "memory" });
  if (obraId) {
    search.set("obraId", obraId);
  }

  return (
    <section className="home-command-summary home-memory-summary">
      <header className="home-memory-summary__heading">
        <div>
          <span className="home-card-kicker">Rastreabilidade</span>
          <h3>Memória operacional</h3>
        </div>
        <span className="home-memory-summary__scope">Recorte ativo</span>
      </header>
      <dl className="home-memory-summary__facts">
        <div>
          <dt>Eventos locais</dt>
          <dd>{events.length}</dd>
        </div>
        <div>
          <dt>Aguardando confirmação</dt>
          <dd>{pending}</dd>
        </div>
      </dl>
      <p className="home-card-muted">
        {events.length === 0
          ? "Nenhuma alteração local registrada neste recorte."
          : `${pending} registro${pending === 1 ? "" : "s"} aguardando confirmação do servidor.`}
      </p>
      <Link to={`/home?${search.toString()}`}>
        Consultar Memória
      </Link>
    </section>
  );
}
