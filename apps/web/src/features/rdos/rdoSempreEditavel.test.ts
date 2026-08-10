import { describe, expect, it } from "vitest";

import {
  createEmptyControleGeometrico,
  createEmptyRdo,
  createEmptyServicoExecutado,
} from "./createEmptyRdo";
import { validateRdoDraftForSync } from "../../lib/db/localRdoService";

/**
 * Nenhum bloco do RDO é obrigatório.
 *
 * <p>O que falta num RDO aparece na conferência e na exportação, que dizem
 * qual campo está vazio. Impedir o salvamento é diferente: prende no aparelho
 * o registro do dia, e quem está em campo não tem como destravar.
 */
function rascunhoMinimo() {
  const draft = createEmptyRdo();
  draft.id = "rdo-livre-1";
  draft.obraId = "obra-1";
  draft.dataRdo = "2026-07-20";
  return draft;
}

describe("RDO sem bloco obrigatório", () => {
  it("aceita o rascunho sem mão de obra, equipamento, serviço ou material", () => {
    expect(() => validateRdoDraftForSync(rascunhoMinimo())).not.toThrow();
  });

  /*
   * A etapa de controle geométrico saiu da tela, mas a regra continuava
   * exigindo-a para serviço de caixa: o RDO travava no salvar e não havia
   * onde preencher o que ela pedia.
   */
  it("aceita serviço de caixa sem controle geométrico, que a tela não tem mais", () => {
    const draft = rascunhoMinimo();
    draft.servicosExecutados = [{
      ...createEmptyServicoExecutado(),
      servicoNome: "Execução de caixa",
      quantidadeExecutada: 3,
    }];

    expect(() => validateRdoDraftForSync(draft)).not.toThrow();
  });

  it("aceita o rascunho com controle geométrico em branco", () => {
    const draft = rascunhoMinimo();
    draft.controlesGeometricos = [createEmptyControleGeometrico()];

    expect(() => validateRdoDraftForSync(draft)).not.toThrow();
  });

  /*
   * O que continua barrado não é bloco: é a identidade do RDO. Sem obra e sem
   * data ele não tem a que dia nem a que obra pertencer, e o servidor não
   * teria onde guardá-lo.
   */
  it("ainda exige a obra e a data, que dizem de quem é o RDO", () => {
    const semObra = rascunhoMinimo();
    semObra.obraId = "";
    const semData = rascunhoMinimo();
    semData.dataRdo = "";

    expect(() => validateRdoDraftForSync(semObra)).toThrow(/obra/i);
    expect(() => validateRdoDraftForSync(semData)).toThrow(/data/i);
  });
});
