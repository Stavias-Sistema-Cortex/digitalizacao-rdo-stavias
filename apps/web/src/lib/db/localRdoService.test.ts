import { describe, expect, it } from "vitest";

import { createEmptyRdo } from "../../features/rdos/createEmptyRdo";
import { validateRdoDraftForSync } from "./localRdoService";

function validDraft() {
  const draft = createEmptyRdo();

  draft.id = "rdo-local-1";
  draft.obraId = "obra-1";
  draft.numeroRdo = "RDO-001";
  draft.dataRdo = "2026-07-03";
  draft.servicosExecutados[0] = {
    ...draft.servicosExecutados[0],
    servicoNome: "Aplicação de CBUQ",
    quantidadeExecutada: 0,
  };

  return draft;
}

describe("validateRdoDraftForSync", () => {
  it("bloqueia quantidade executada negativa antes de criar mutação offline", () => {
    const draft = validDraft();
    draft.servicosExecutados[0].quantidadeExecutada = -1;

    expect(() => validateRdoDraftForSync(draft)).toThrow(
      "A quantidade executada do serviço 1 deve ser maior ou igual a zero.",
    );
  });

  it("aceita quantidade executada zero", () => {
    expect(() =>
      validateRdoDraftForSync(validDraft()),
    ).not.toThrow();
  });
});
