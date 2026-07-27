import { Link } from "react-router";

export function MensagensCard() {
  return (
    <section className="home-card">
      <h3>Mensagens</h3>
      <p className="home-card-muted">
        Converse com a equipe neste dispositivo. O histórico local não é
        apresentado como mensagem confirmada pelo servidor.
      </p>
      <Link to="/mensagens">Abrir mensagens locais</Link>
    </section>
  );
}
