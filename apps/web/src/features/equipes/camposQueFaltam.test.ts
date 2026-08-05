import { describe, expect, it } from "vitest";

import { camposQueFaltam, mensagemDoQueFalta } from "./camposQueFaltam";

/*
 * O aviso nomeava os três campos sempre. Quem já tinha escolhido a pessoa e
 * escrito o motivo lia uma acusação sobre o que estava ali na tela, e concluía
 * — com razão — que o erro não fazia sentido.
 */
describe("o que falta para salvar a participação", () => {
  const completo = {
    colaboradorId: "colab-1",
    funcaoOperacionalId: "func-1",
    motivo: "Chefe",
  };

  it("acusa só a função quando pessoa e motivo já estão preenchidos", () => {
    const faltando = camposQueFaltam({ ...completo, funcaoOperacionalId: "" });

    expect(faltando).toEqual(["a função operacional"]);
    expect(mensagemDoQueFalta(faltando)).toBe("Informe a função operacional.");
  });

  it("lista os três quando o formulário está vazio", () => {
    expect(
      mensagemDoQueFalta(
        camposQueFaltam({
          colaboradorId: "",
          funcaoOperacionalId: "",
          motivo: "   ",
        }),
      ),
    ).toBe("Informe a pessoa, a função operacional e o motivo da alteração.");
  });

  it("junta dois com 'e', sem vírgula sobrando", () => {
    expect(
      mensagemDoQueFalta(
        camposQueFaltam({ ...completo, colaboradorId: "", motivo: "" }),
      ),
    ).toBe("Informe a pessoa e o motivo da alteração.");
  });

  it("não acusa nada quando está tudo preenchido", () => {
    expect(camposQueFaltam(completo)).toEqual([]);
  });

  it("trata motivo só com espaços como ausente", () => {
    expect(camposQueFaltam({ ...completo, motivo: "   " })).toEqual([
      "o motivo da alteração",
    ]);
  });
});
