import type { LocalRdoRecord } from "../../lib/db/db.types";
import { teamFromRdo } from "./teamFromRdo";

interface TimeCardProps {
  latestRdo: LocalRdoRecord | null;
}

export function TimeCard({ latestRdo }: TimeCardProps) {
  const team = teamFromRdo(latestRdo);

  return (
    <section className="home-card">
      <h3>Seu time mais recente</h3>
      {team.length === 0 ? (
        <p className="home-card-muted">
          Sem mão de obra registrada no último RDO desta
          obra.
        </p>
      ) : (
        <>
          <ul>
            {team.map((entry) => (
              <li key={entry.cargo}>
                {entry.cargo}
                <strong style={{ float: "right" }}>
                  {entry.quantidade}
                </strong>
              </li>
            ))}
          </ul>
          {latestRdo && (
            <p className="home-card-muted">
              do RDO {latestRdo.numeroRdo || latestRdo.id} ·{" "}
              {latestRdo.dataRdo}
            </p>
          )}
        </>
      )}
    </section>
  );
}
