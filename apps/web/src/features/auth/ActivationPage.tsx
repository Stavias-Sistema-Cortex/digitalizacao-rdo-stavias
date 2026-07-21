import { useState } from "react";

import cortexLogo from "../../assets/login/cortex-logo.png";
import { EmailOtpAccessForm } from "./EmailOtpAccessForm";

import "./ActivationPage.css";

export function ActivationPage() {
  const [complete, setComplete] = useState(false);

  return (
    <main className="cortex-activation">
      <section className="cortex-activation__stage" aria-labelledby="activation-title">
        <div className="cortex-activation__identity">
          <img
            className="cortex-activation__logo"
            src={cortexLogo}
            alt="Stavias Córtex"
            draggable={false}
          />
          <div className="cortex-activation__rule" aria-hidden="true" />
          <p className="cortex-activation__classification">Ativação inicial</p>
          <h1 id="activation-title">Controle de acesso do proprietário</h1>
          <p className="cortex-activation__lead">
            Esta etapa confirma a identidade administrativa inicial do Córtex.
            Nenhum módulo operacional é disponibilizado durante a ativação.
          </p>
          <p className="cortex-activation__scope">
            Escopo atual: verificação de identidade por e-mail institucional.
          </p>
        </div>

        <div className="cortex-activation__panel">
          {complete ? (
            <section className="cortex-activation__complete" aria-live="polite">
              <p className="cortex-activation__complete-label">Identidade confirmada</p>
              <h2>Ativação concluída.</h2>
              <p>
                A identidade proprietária foi ativada. O ambiente operacional
                permanece fechado até a liberação explícita do runtime Córtex.
              </p>
            </section>
          ) : (
            <EmailOtpAccessForm
              purpose="activation"
              onVerified={() => {
                setComplete(true);
              }}
            />
          )}
        </div>
      </section>
      <p className="cortex-activation__footer">
        Stavias Córtex · Ambiente institucional restrito
      </p>
    </main>
  );
}
