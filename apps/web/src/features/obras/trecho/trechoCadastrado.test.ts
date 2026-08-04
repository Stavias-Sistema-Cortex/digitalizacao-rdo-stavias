import { describe, expect, it } from "vitest";

import {
  CADASTRO_VAZIO,
  extensaoMedidaEmCampo,
  validarCadastro,
  type CadastroTrecho,
} from "./trechoCadastrado";

function cadastro(overrides: Partial<CadastroTrecho> = {}): CadastroTrecho {
  return {
    ...CADASTRO_VAZIO,
    rodovia: "SP-310",
    sentido: "SUL",
    faixa: "DIREITA",
    kmInicial: "172",
    kmFinal: "171",
    ...overrides,
  };
}

describe("validarCadastro", () => {
  it("aceita um trecho com os dois extremos declarados", () => {
    expect(validarCadastro(cadastro())).toBeNull();
  });

  it("exige a rodovia", () => {
    expect(validarCadastro(cadastro({ rodovia: "  " })))
      .toMatch(/rodovia/i);
  });

  it("exige os dois extremos, porque um só é ponto e não trecho", () => {
    expect(validarCadastro(cadastro({ kmFinal: "" })))
      .toMatch(/km inicial e o km final/i);
  });

  it("recusa extremos iguais", () => {
    expect(validarCadastro(cadastro({ kmFinal: "172" })))
      .toMatch(/mesmo ponto/i);
  });

  it("recusa extensão medida que não é número positivo", () => {
    expect(validarCadastro(cadastro({ extensaoM: "-5" })))
      .toMatch(/maior que zero/i);
    expect(validarCadastro(cadastro({ extensaoM: "1500" }))).toBeNull();
  });

  it("aceita a notação rodoviária de km", () => {
    expect(validarCadastro(cadastro({
      kmInicial: "309+400",
      kmFinal: "310+100",
    }))).toBeNull();
  });
});

describe("extensaoMedidaEmCampo", () => {
  it("lê a medida declarada, inclusive com vírgula decimal", () => {
    expect(extensaoMedidaEmCampo(cadastro({ extensaoM: "1500" }))).toBe(1500);
    expect(extensaoMedidaEmCampo(cadastro({ extensaoM: "1500,5" })))
      .toBe(1500.5);
  });

  /**
   * Campo vazio e texto sem número não são medição. Devolver zero faria a
   * ausência de medida parecer uma medida, e o comprimento da própria linha
   * desenhada — que existe e é confiável — seria descartado por ela.
   */
  it("não transforma ausência de medida em zero", () => {
    expect(extensaoMedidaEmCampo(cadastro({ extensaoM: "" }))).toBeNull();
    expect(extensaoMedidaEmCampo(cadastro({ extensaoM: "  " }))).toBeNull();
    expect(extensaoMedidaEmCampo(cadastro({ extensaoM: "abc" }))).toBeNull();
    expect(extensaoMedidaEmCampo(cadastro({ extensaoM: "0" }))).toBeNull();
  });
});
