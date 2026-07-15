import { useNavigate } from "react-router-dom";

export function MensagensCard() {
  const navigate = useNavigate();

  return (
    <section className="home-card home-card--messages">
      <h3>Mensagens</h3>
      <p className="home-card-muted">
        Decisões, arquivos e atualizações vinculados às suas obras,
        disponíveis também offline.
      </p>
      <button type="button" onClick={() => navigate("/mensagens")}>
        Abrir conversas
      </button>
    </section>
  );
}
