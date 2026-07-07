import type { LocalRdoRecord } from "../../lib/db/db.types";

interface TimeCardProps {
  latestRdo: LocalRdoRecord | null;
}

export function TimeCard(props: TimeCardProps) {
  void props;

  return (
    <section className="home-card">
      <h3>Seu time mais recente</h3>
    </section>
  );
}
