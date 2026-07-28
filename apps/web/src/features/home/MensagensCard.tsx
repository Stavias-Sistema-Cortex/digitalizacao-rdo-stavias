import { Link } from "react-router";

export function MensagensCard() {
  return (
    <section className="home-card">
      <h3>Mensagens</h3>
      <p className="home-card-muted">
        Histórico local não equivale à confirmação do servidor.
      </p>
      <Link to="/mensagens">Abrir mensagens</Link>
    </section>
  );
}
