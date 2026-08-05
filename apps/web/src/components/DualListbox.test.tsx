// @vitest-environment jsdom

import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { DualListbox, type DualListboxItem } from "./DualListbox";

afterEach(cleanup);

const DISPONIVEIS: DualListboxItem[] = [
  { id: "adao", titulo: "ADAO LEITE", detalhe: "37 · Encarregado" },
  { id: "paulo", titulo: "PAULO CESAR", detalhe: "12 · Operador" },
  { id: "carlos", titulo: "CARLOS ALBERTO", detalhe: "9 · Encarregado" },
];

function montar(overrides: Partial<Parameters<typeof DualListbox>[0]> = {}) {
  const onEscolher = vi.fn();
  const onRemover = vi.fn();
  render(
    <DualListbox
      rotulo="Mão de obra"
      disponiveis={DISPONIVEIS}
      escolhidos={[]}
      onEscolher={onEscolher}
      onRemover={onRemover}
      {...overrides}
    />,
  );
  return { onEscolher, onRemover };
}

function painel(nome: "Disponíveis" | "Na equipe") {
  return screen.getByRole("listbox", { name: new RegExp(nome) });
}

describe("escolher com os dois lados à vista", () => {
  /*
   * A razão de existir: a combinação anterior cobrava um ciclo inteiro de
   * busca-escolha-adiciona por pessoa. Montar uma frente de doze eram doze
   * ciclos.
   */
  it("move vários de uma vez", async () => {
    const usuario = userEvent.setup();
    const { onEscolher } = montar();

    await usuario.click(screen.getByRole("option", { name: /ADAO LEITE/ }));
    await usuario.click(screen.getByRole("option", { name: /PAULO CESAR/ }));
    await usuario.click(screen.getByRole("button", { name: "Adicionar" }));

    expect(onEscolher).toHaveBeenCalledWith(["adao", "paulo"]);
  });

  /*
   * O caminho de volta importa tanto quanto: quem chegou por herança do RDO
   * anterior costuma precisar sair.
   */
  it("devolve vários de uma vez", async () => {
    const usuario = userEvent.setup();
    const { onRemover } = montar({
      disponiveis: [],
      escolhidos: DISPONIVEIS,
    });

    await usuario.click(screen.getByRole("option", { name: /ADAO LEITE/ }));
    await usuario.click(screen.getByRole("option", { name: /CARLOS ALBERTO/ }));
    await usuario.click(screen.getByRole("button", { name: "Remover" }));

    expect(onRemover).toHaveBeenCalledWith(["adao", "carlos"]);
  });

  it("desmarca ao clicar de novo", async () => {
    const usuario = userEvent.setup();
    const { onEscolher } = montar();

    const adao = screen.getByRole("option", { name: /ADAO LEITE/ });
    await usuario.click(adao);
    await usuario.click(adao);

    expect(screen.getByRole("button", { name: "Adicionar" })).toBeDisabled();
    expect(onEscolher).not.toHaveBeenCalled();
  });

  /*
   * A busca filtra só o lado disponível. Filtrar os dois esconderia parte de
   * quem já está escolhido — o item continuaria valendo sem aparecer, que é a
   * forma mais silenciosa de perder trabalho neste app.
   */
  it("busca não esconde quem já está escolhido", async () => {
    const usuario = userEvent.setup();
    montar({
      disponiveis: [DISPONIVEIS[0]],
      escolhidos: [DISPONIVEIS[1]],
    });

    await usuario.type(screen.getByLabelText("Buscar"), "adao");

    expect(within(painel("Disponíveis")).getByText("ADAO LEITE"))
      .toBeVisible();
    expect(within(painel("Na equipe")).getByText("PAULO CESAR"))
      .toBeVisible();
  });

  it("acha sem acento e sem caixa", async () => {
    const usuario = userEvent.setup();
    montar({
      disponiveis: [{ id: "joao", titulo: "JOÃO PEREIRA", detalhe: null }],
    });

    await usuario.type(screen.getByLabelText("Buscar"), "joao");

    expect(screen.getByRole("option", { name: /JOÃO PEREIRA/ })).toBeVisible();
  });

  /*
   * A regra delicada: marcar alguém, digitar até ele sumir e então mover levaria
   * embora um item que não está mais na tela. A pessoa veria acontecer algo que
   * não pediu.
   */
  it("não move quem a busca escondeu depois de marcado", async () => {
    const usuario = userEvent.setup();
    const { onEscolher } = montar();

    await usuario.click(screen.getByRole("option", { name: /ADAO LEITE/ }));
    await usuario.click(screen.getByRole("option", { name: /PAULO CESAR/ }));
    await usuario.type(screen.getByLabelText("Buscar"), "paulo");
    await usuario.click(screen.getByRole("button", { name: "Adicionar" }));

    expect(onEscolher).toHaveBeenCalledWith(["paulo"]);
  });

  it("mostra a contagem de cada lado", () => {
    montar({ escolhidos: [DISPONIVEIS[2]] });

    expect(within(painel("Disponíveis")).queryAllByRole("option"))
      .toHaveLength(3);
    expect(within(painel("Na equipe")).queryAllByRole("option"))
      .toHaveLength(1);
  });

  it("diz o que fazer quando não há catálogo", () => {
    montar({
      disponiveis: [],
      mensagemSemDisponiveis: "Nenhum colaborador autorizado nesta obra.",
    });

    expect(
      screen.getByText("Nenhum colaborador autorizado nesta obra."),
    ).toBeVisible();
  });
});
