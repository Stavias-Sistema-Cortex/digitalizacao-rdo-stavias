import cortexLogo from "../assets/login/cortex-logo.png";
import "../features/auth/LoginPage.css";
import "./ConectandoScreen.css";

/**
 * O que se vê enquanto o bootstrap decide o que montar.
 *
 * A raiz do documento é vazia até a sessão ser consultada, e essa consulta
 * depende de um serviço que sobe sob demanda. O resultado era uma tela branca
 * de duração indefinida — indistinguível, para quem está em campo, de um
 * aplicativo que não abriu. Esta tela não acelera nada; ela diz o que está
 * acontecendo, com a marca no lugar certo, enquanto acontece.
 */
export function ConectandoScreen() {
  return (
    <main className="cortex-login">
      <section className="login__stage">
        <div className="login__identity">
          <div className="login__brand">
            <img
              className="login__brand-lockup"
              src={cortexLogo}
              alt="Stavias Córtex"
              draggable={false}
            />
          </div>
        </div>

        <p className="conectando" role="status">
          <span className="login__spinner" aria-hidden="true" />
          Conectando ao Córtex…
        </p>
      </section>
    </main>
  );
}
