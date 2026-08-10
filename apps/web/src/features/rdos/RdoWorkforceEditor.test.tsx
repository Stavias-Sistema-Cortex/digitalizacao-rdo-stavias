// @vitest-environment jsdom

import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { createEmptyRdo } from "./createEmptyRdo";
import { RdoWorkforceEditor } from "./RdoWorkforceEditor";
import type { RdoContextCollaborator } from "./rdoLookupApi";

/**
 * A mão de obra virou uma lista de marcar.
 *
 * <p>Ela era dois painéis com botões de mover e, embaixo, uma tabela de sete
 * colunas por pessoa: função, vínculo, quantidade, início, fim, observações.
 * Uma frente de doze eram oitenta e quatro caixas num celular à beira da pista,
 * quase todas repetindo o mesmo valor. A pergunta que o RDO faz é uma só — quem
 * trabalhou hoje —, e agora a tela faz essa.
 */

const catalog: RdoContextCollaborator[] = [
  { id: "worker-a", codigoColaborador: "001", nome: "Ana", papelNaObra: "APONTADOR", nomePerfil: "Apontadora" },
  { id: "worker-b", codigoColaborador: "002", nome: "Bruno", papelNaObra: "OPERACIONAL", nomePerfil: "Operador" },
  { id: "worker-c", codigoColaborador: "003", nome: "Carla", papelNaObra: "OPERACIONAL", nomePerfil: "Sinaleira" },
];

function draft() {
  return {
    ...createEmptyRdo(),
    previousRdoId: "source-rdo",
    maoObra: [
      {
        localId: "row-a",
        origemItemId: "source-a",
        sourceRdoId: "source-rdo",
        origin: "PREVIOUS_RDO" as const,
        availability: "AVAILABLE" as const,
        selected: true,
        colaboradorId: "worker-a",
        nomeColaborador: "Ana",
        cargo: "Apontadora",
        tipoVinculo: "PROPRIO",
        quantidade: 1,
        horaInicio: "07:00",
        horaFim: "17:00",
        observacoes: "",
      },
      {
        localId: "row-historical",
        origemItemId: "source-historical",
        sourceRdoId: "source-rdo",
        origin: "PREVIOUS_RDO" as const,
        availability: "UNAVAILABLE" as const,
        selected: false,
        colaboradorId: "historical",
        nomeColaborador: "Histórico",
        cargo: "Operador",
        tipoVinculo: "TERCEIRIZADO",
        quantidade: 1,
        horaInicio: "",
        horaFim: "",
        observacoes: "Sem vínculo atual",
      },
    ],
  };
}

function lista() {
  return screen.getByRole("list", { name: "Pessoas do RDO" });
}

function caixaDe(nome: string | RegExp): HTMLInputElement {
  const item = within(lista())
    .getAllByRole("listitem")
    .find((linha) =>
      typeof nome === "string"
        ? within(linha).queryByText(nome) !== null
        : nome.test(linha.textContent ?? ""),
    );
  if (!item) throw new Error(`Linha não encontrada: ${String(nome)}`);
  return within(item).getByRole("checkbox");
}

afterEach(cleanup);

describe("lista de mão de obra do RDO", () => {
  /*
   * Uma lista só, e não dois painéis: quem já está no RDO e quem está na obra
   * respondem à mesma pergunta e não precisam de lados diferentes.
   */
  it("mostra numa lista só quem já veio e quem está na obra", () => {
    render(
      <RdoWorkforceEditor
        draft={draft()}
        collaborators={catalog}
        sourceRdoNumber="RDO-0007"
        onChange={vi.fn()}
      />,
    );

    const linhas = within(lista()).getAllByRole("listitem");
    expect(linhas.map((linha) => linha.textContent)).toEqual([
      expect.stringContaining("Ana"),
      expect.stringContaining("Histórico"),
      expect.stringContaining("Bruno"),
      expect.stringContaining("Carla"),
    ]);
    expect(caixaDe("Ana")).toBeChecked();
    expect(caixaDe("Bruno")).not.toBeChecked();
  });

  it("traz para o RDO quem foi marcado no catálogo da obra", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(
      <RdoWorkforceEditor
        draft={draft()}
        collaborators={catalog}
        sourceRdoNumber={null}
        onChange={onChange}
      />,
    );

    await user.click(caixaDe("Bruno"));

    const proximo = onChange.mock.calls.at(-1)?.[0];
    expect(proximo.maoObra).toHaveLength(3);
    expect(proximo.maoObra[2]).toMatchObject({
      colaboradorId: "worker-b",
      nomeColaborador: "Bruno",
      // A função vem do cadastro, que é quem a conhece — deixou de ser um
      // campo para alguém redigitar por pessoa.
      cargo: "Operador",
      selected: true,
      origin: "AUTHORIZED_CONTEXT",
    });
  });

  /*
   * Desmarcar não apaga a linha: ela guarda de onde a pessoa veio, e essa
   * procedência é o que liga este RDO ao anterior. O que sai do envio é a
   * marca, não o registro.
   */
  it("desmarca sem apagar a linha herdada", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(
      <RdoWorkforceEditor
        draft={draft()}
        collaborators={catalog}
        sourceRdoNumber={null}
        onChange={onChange}
      />,
    );

    await user.click(caixaDe("Ana"));

    const proximo = onChange.mock.calls.at(-1)?.[0];
    expect(proximo.maoObra).toHaveLength(2);
    expect(proximo.maoObra[0]).toMatchObject({
      localId: "row-a",
      origemItemId: "source-a",
      selected: false,
    });
  });

  it("mantém quem perdeu o vínculo à vista, dizendo por quê, e não deixa marcar", () => {
    render(
      <RdoWorkforceEditor
        draft={draft()}
        collaborators={catalog}
        sourceRdoNumber={null}
        onChange={vi.fn()}
      />,
    );

    const caixa = caixaDe("Histórico");
    expect(caixa).toBeDisabled();
    expect(caixa).not.toBeChecked();
    expect(screen.getByText("Indisponível nesta obra")).toBeVisible();
  });

  /*
   * O ajudante do dia não tem cadastro e o dia não espera por ele. Mas é a
   * exceção: a caixa de texto fica atrás de um botão para não competir com a
   * lista que responde a pergunta comum.
   */
  it("soma alguém à mão só depois de pedirem por isso", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(
      <RdoWorkforceEditor
        draft={{ ...draft(), maoObra: [] }}
        collaborators={catalog}
        sourceRdoNumber={null}
        onChange={onChange}
      />,
    );

    expect(
      screen.queryByRole("textbox", { name: /Nome de quem não está na lista/ }),
    ).toBeNull();

    await user.click(
      screen.getByRole("button", { name: "Somar alguém à mão" }),
    );
    const campo = screen.getByRole("textbox", {
      name: /Nome de quem não está na lista/,
    });
    expect(campo).toHaveAttribute("maxLength", "255");
    await user.type(campo, "  Maria   Servente  ");
    await user.click(screen.getByRole("button", { name: "Adicionar" }));

    const proximo = onChange.mock.calls.at(-1)?.[0];
    expect(proximo.maoObra).toHaveLength(1);
    expect(proximo.maoObra[0]).toMatchObject({
      colaboradorId: "",
      nomeColaborador: "Maria Servente",
      selected: true,
      origin: "MANUAL",
    });
  });

  it("adiciona à mão ao pressionar Enter", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(
      <RdoWorkforceEditor
        draft={{ ...draft(), maoObra: [] }}
        collaborators={[]}
        sourceRdoNumber={null}
        onChange={onChange}
      />,
    );

    await user.click(
      screen.getByRole("button", { name: "Somar alguém à mão" }),
    );
    await user.type(
      screen.getByRole("textbox", { name: /Nome de quem não está na lista/ }),
      "João Ajudante{Enter}",
    );

    expect(onChange.mock.calls.at(-1)?.[0].maoObra[0]).toMatchObject({
      nomeColaborador: "João Ajudante",
    });
  });

  /*
   * Quem foi somado à mão é o único que sai de vez: não há cadastro nem
   * procedência a preservar, e uma linha digitada por engano precisa poder
   * sumir.
   */
  it("deixa remover quem foi somado à mão, e só ele", () => {
    const base = draft();
    render(
      <RdoWorkforceEditor
        draft={{
          ...base,
          maoObra: [
            ...base.maoObra,
            {
              ...base.maoObra[0],
              localId: "row-manual",
              origemItemId: "",
              sourceRdoId: "",
              origin: "MANUAL" as const,
              colaboradorId: "",
              nomeColaborador: "Maria Servente",
            },
          ],
        }}
        collaborators={catalog}
        sourceRdoNumber={null}
        onChange={vi.fn()}
      />,
    );

    expect(
      screen.getByRole("button", { name: "Remover Maria Servente" }),
    ).toBeVisible();
    expect(screen.queryByRole("button", { name: "Remover Ana" })).toBeNull();
  });

  it("procura por nome, código e função sem esconder quem já está marcado", async () => {
    const user = userEvent.setup();
    render(
      <RdoWorkforceEditor
        draft={draft()}
        collaborators={[
          ...catalog,
          { id: "worker-d", codigoColaborador: "004", nome: "Daniel", papelNaObra: "OPERACIONAL", nomePerfil: "Pedreiro" },
          { id: "worker-e", codigoColaborador: "005", nome: "Elis", papelNaObra: "OPERACIONAL", nomePerfil: "Servente" },
          { id: "worker-f", codigoColaborador: "006", nome: "Fábio", papelNaObra: "OPERACIONAL", nomePerfil: "Servente" },
          { id: "worker-g", codigoColaborador: "007", nome: "Gisele", papelNaObra: "OPERACIONAL", nomePerfil: "Servente" },
        ]}
        sourceRdoNumber={null}
        onChange={vi.fn()}
      />,
    );

    await user.type(screen.getByRole("searchbox", { name: "Procurar" }), "servente");
    const nomes = within(lista())
      .getAllByRole("listitem")
      .map((linha) => linha.textContent);
    expect(nomes).toHaveLength(3);
    expect(nomes.join(" ")).toContain("Elis");
    expect(nomes.join(" ")).not.toContain("Ana");
  });

  /*
   * O apontador só pode ser alguém que trabalhou: a lista do campo é a dos
   * marcados, e não a do catálogo inteiro.
   */
  it("oferece como apontador apenas quem está marcado", () => {
    render(
      <RdoWorkforceEditor
        draft={draft()}
        collaborators={catalog}
        sourceRdoNumber={null}
        onChange={vi.fn()}
      />,
    );

    const seletor = screen.getByLabelText("Apontador do RDO");
    expect(
      within(seletor).getAllByRole("option").map((opcao) => opcao.textContent),
    ).toEqual(["Sem apontador", "Ana"]);
  });

  /*
   * Avisa, e não impede: a mesma pessoa em duas frentes no mesmo dia acontece,
   * e barrar transformaria o caso legítimo num problema sem saída em campo.
   */
  it("avisa quem já foi apontado em outro RDO do dia sem impedir a marcação", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(
      <RdoWorkforceEditor
        draft={draft()}
        collaborators={catalog}
        sourceRdoNumber={null}
        jaApontados={new Map([["worker-b", "RDO-0042"]])}
        onChange={onChange}
      />,
    );

    expect(screen.getByText("Já apontado hoje no RDO-0042.")).toBeVisible();
    expect(caixaDe("Bruno")).toBeEnabled();

    await user.click(caixaDe("Bruno"));
    expect(onChange.mock.calls.at(-1)?.[0].maoObra).toHaveLength(3);
  });

  it("diz o que houve quando o catálogo não pôde ser carregado", () => {
    render(
      <RdoWorkforceEditor
        draft={{ ...draft(), maoObra: [] }}
        collaborators={[]}
        catalogUnavailableMessage="Catálogo indisponível offline."
        sourceRdoNumber={null}
        onChange={vi.fn()}
      />,
    );

    expect(screen.getByText("Catálogo indisponível offline.")).toBeVisible();
    expect(
      screen.getByText("Nenhum colaborador autorizado carregado."),
    ).toBeVisible();
  });
});
