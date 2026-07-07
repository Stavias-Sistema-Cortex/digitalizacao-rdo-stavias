import type { OperationalEventRecord } from "../../lib/db/db.types";

interface AtualizacoesCardProps {
  events: OperationalEventRecord[];
}

export function AtualizacoesCard(props: AtualizacoesCardProps) {
  void props;

  return (
    <section className="home-card">
      <h3>Atualizações</h3>
    </section>
  );
}
