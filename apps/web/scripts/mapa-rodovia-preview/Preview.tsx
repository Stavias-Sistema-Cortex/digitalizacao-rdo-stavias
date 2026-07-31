import { ObraTrechoSection } from "../../src/features/obras/trecho/ObraTrechoSection";
import { OBRA_FIXTURE } from "./fixtures";
import "./preview.css";

/** Composição medida pelo harness: obra operável e obra desativada. */
export function Preview() {
  return (
    <main className="preview-root">
      <p className="preview-aviso">
        Harness de verificação visual · dados de fixture, não operacionais
      </p>

      <section className="preview-bloco" data-cenario="obra-ativa">
        <h2>Obra ativa</h2>
        <ObraTrechoSection
          obra={OBRA_FIXTURE}
          podeDesenhar
          operavelLocalmente
        />
      </section>

      <section className="preview-bloco" data-cenario="obra-desativada">
        <h2>Obra desativada</h2>
        <ObraTrechoSection
          obra={{ ...OBRA_FIXTURE, id: `${OBRA_FIXTURE.id}-desativada` }}
          podeDesenhar={false}
          operavelLocalmente={false}
        />
      </section>
    </main>
  );
}
