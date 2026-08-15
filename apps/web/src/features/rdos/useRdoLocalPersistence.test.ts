import { describe, expect, it } from "vitest";

import { rdoLocalSaveMessage } from "./useRdoLocalPersistence";

describe("rdoLocalSaveMessage", () => {
  it("expõe bloqueio recuperável de contexto sem chamar a fila de erro", () => {
    expect(rdoLocalSaveMessage("RDO_CREATION_CONTEXT_REQUIRED")).toBe(
      "RDO salvo neste dispositivo. A sincronização aguarda o contexto da obra; reconecte para atualizar os dados de origem.",
    );
  });

  it("mantém mensagem pendente comum quando a fila está pronta", () => {
    expect(rdoLocalSaveMessage(null)).toBe(
      "RDO salvo apenas neste dispositivo. Pendente de sincronização.",
    );
  });

  it("explica que o serviço digitado precisa de uma seleção de catálogo", () => {
    expect(
      rdoLocalSaveMessage("RDO_SERVICE_CATALOG_SELECTION_REQUIRED"),
    ).toBe(
      "RDO salvo neste dispositivo. Selecione o serviço no catálogo antes de sincronizar esta linha.",
    );
  });
});
