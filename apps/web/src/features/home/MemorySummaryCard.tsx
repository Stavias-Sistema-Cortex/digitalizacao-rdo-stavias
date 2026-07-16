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
    <section className="home-card home-memory-summary">
      <div className="home-memory-summary__heading">
        <div>
          <span className="home-card-kicker">Registro ontológico</span>
          <h3>Memória operacional</h3>
        </div>
        <strong>{events.length}</strong>
      </div>
      <p className="home-card-muted">
        {events.length === 0
          ? "Nenhuma alteração local registrada neste recorte."
          : `${pending} registro${pending === 1 ? "" : "s"} aguardando confirmação do servidor.`}
      </p>
      <Link to={`/home?${search.toString()}`}>
        Abrir registro completo
      </Link>
    </section>
  );
}
