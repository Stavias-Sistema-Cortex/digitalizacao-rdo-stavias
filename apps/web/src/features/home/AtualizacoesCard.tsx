import type { OperationalEventRecord } from "../../lib/db/db.types";
import { operationalEventLabel } from "./eventLabels";
import { relativeTime } from "./relativeTime";

interface AtualizacoesCardProps {
  events: OperationalEventRecord[];
}

export function AtualizacoesCard({
  events,
}: AtualizacoesCardProps) {
  const latest = events.slice(0, 5);

  return (
    <section className="home-card">
      <h3>Atualizações</h3>
      {latest.length === 0 ? (
        <p className="home-card-muted">
          Nenhuma atividade registrada para esta obra
          ainda.
        </p>
      ) : (
        <ul>
          {latest.map((event) => (
            <li key={event.id}>
              {operationalEventLabel(event.type)}{" "}
              <span className="home-card-muted">
                {/* eslint-disable-next-line react-hooks/purity */}
                · {relativeTime(event.occurredAt, Date.now())}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
