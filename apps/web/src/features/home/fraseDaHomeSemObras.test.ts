import { describe, expect, it } from "vitest";

import { fraseDaHomeSemObras } from "./fraseDaHomeSemObras";

/**
 * A tela vazia tem de dizer qual das três situações é — porque as três se
 * vestiam de uma, e a frase única mandava "conectar" quem já estava conectado.
 *
 * O caso que motivou isto: uma conta nova entrou, a sincronização ficou verde,
 * e todas as telas ficaram vazias. O servidor tinha respondido, com sucesso,
 * que o escopo daquela conta é vazio — Beta sem vínculo com obra nenhuma. A
 * solução não estava no aparelho; estava na Gestão de Obras. A tela agora diz
 * isso, em vez de sugerir um problema de conexão que não existe.
 */
describe("a frase da Home sem obra nenhuma", () => {
  it("manda vincular quando o servidor confirmou que o escopo do Beta é vazio", () => {
    const frase = fraseDaHomeSemObras(true, false);

    expect(frase).toContain("não está vinculado");
    expect(frase).toContain("Gestão de Obras");
    // A frase antiga sugeria conexão; a nova não pode repetir o engano.
    expect(frase).not.toContain("Conecte-se");
  });

  it("trata escopo vazio de Alfa como defeito a reportar, não como instrução", () => {
    const frase = fraseDaHomeSemObras(true, true);

    expect(frase).toContain("defeito");
    expect(frase).not.toContain("Gestão de Obras");
  });

  it("só fala de rede quando o servidor de fato ainda não respondeu", () => {
    const frase = fraseDaHomeSemObras(false, false);

    expect(frase).toContain("primeira sincronização");
    expect(frase).not.toContain("vinculado");
  });
});
