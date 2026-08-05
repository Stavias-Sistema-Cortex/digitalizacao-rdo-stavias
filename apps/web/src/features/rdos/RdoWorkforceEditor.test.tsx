// @vitest-environment jsdom

import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { createEmptyRdo } from "./createEmptyRdo";
import { RdoWorkforceEditor } from "./RdoWorkforceEditor";
import type { RdoContextCollaborator } from "./rdoLookupApi";

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

afterEach(cleanup);

describe("editor da equipe carregada", () => {
  it("adiciona mão de obra nova no próprio RDO sem criar acesso à obra", async () => {
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

    const newCollaborator = screen.getByRole("textbox", {
      name: "Adicionar trabalhador ao RDO",
    });
    expect(newCollaborator).toHaveAttribute("maxLength", "255");
    await user.type(newCollaborator, "  Maria   Servente  ");
    await user.click(
      screen.getByRole("button", { name: "Adicionar trabalhador" }),
    );

    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        maoObra: [
          expect.objectContaining({
            colaboradorId: "",
            nomeColaborador: "Maria Servente",
            origin: "MANUAL",
            availability: "AVAILABLE",
            selected: true,
          }),
        ],
      }),
    );
  });

  it("adiciona a mão de obra manual ao pressionar Enter", async () => {
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

    const newCollaborator = screen.getByRole("textbox", {
      name: "Adicionar trabalhador ao RDO",
    });
    await user.type(newCollaborator, "Maria Servente{Enter}");

    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        maoObra: [
          expect.objectContaining({
            colaboradorId: "",
            nomeColaborador: "Maria Servente",
          }),
        ],
      }),
    );
  });

  it("preserva indisponível desmarcado e permite desmarcar o disponível", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(
      <RdoWorkforceEditor
        draft={draft()}
        collaborators={catalog}
        sourceRdoNumber="RDO-0020"
        onChange={onChange}
      />,
    );

    expect(screen.getByText("Importada do RDO RDO-0020")).toBeVisible();
    expect(within(screen.getByRole("table")).getByText("Indisponível"))
      .toBeVisible();
    // E também no seletor, senão só se descobre rolando até a tabela.
    expect(
      within(screen.getByRole("listbox", { name: /Na equipe do RDO/ }))
        .getByRole("option", { name: /Histórico.*Indisponível/ }),
    ).toBeVisible();
    expect(screen.getByRole("checkbox", { name: "Selecionar Histórico" })).toBeDisabled();
    await user.click(screen.getByRole("checkbox", { name: "Selecionar Ana" }));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        maoObra: expect.arrayContaining([
          expect.objectContaining({ colaboradorId: "worker-a", selected: false }),
        ]),
      }),
    );
  });

  it("adiciona apenas autorizados que ainda não estão na equipe", async () => {
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
    const autorizados = screen.getByRole("listbox", {
      name: /Autorizados na obra/,
    });
    // Ana já veio do RDO anterior; oferecê-la de novo só criaria a chance de erro.
    expect(within(autorizados).queryByRole("option", { name: /Ana/ }))
      .not.toBeInTheDocument();

    await user.type(screen.getByLabelText("Buscar"), "003");
    await user.click(
      within(autorizados).getByRole("option", {
        name: /Carla.*003.*OPERACIONAL/,
      }),
    );
    await user.click(screen.getByRole("button", { name: "Adicionar" }));

    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        maoObra: expect.arrayContaining([
          expect.objectContaining({ colaboradorId: "worker-c", selected: true }),
        ]),
      }),
    );
  });

  /*
   * O motivo de existir do seletor de dois lados: montar a frente do dia era um
   * ciclo inteiro de busca-escolha-adiciona por pessoa. Aqui as duas entram no
   * mesmo movimento — e num único onChange, senão o pai reagiria só à última e
   * a primeira sumiria.
   */
  it("leva vários autorizados para a equipe de uma vez só", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(
      <RdoWorkforceEditor
        draft={{ ...draft(), maoObra: [draft().maoObra[0]] }}
        collaborators={catalog}
        sourceRdoNumber={null}
        onChange={onChange}
      />,
    );

    await user.click(screen.getByRole("option", { name: /Bruno/ }));
    await user.click(screen.getByRole("option", { name: /Carla/ }));
    await user.click(screen.getByRole("button", { name: "Adicionar" }));

    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange.mock.calls[0][0].maoObra.map(
      (row: { colaboradorId: string }) => row.colaboradorId,
    )).toEqual(["worker-a", "worker-b", "worker-c"]);
  });

  /*
   * O caminho de volta importa tanto quanto: quem chegou por herança do RDO
   * anterior costuma precisar sair, e sair em bloco.
   */
  it("devolve vários da equipe de uma vez só", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(
      <RdoWorkforceEditor
        draft={draft()}
        collaborators={catalog}
        sourceRdoNumber="RDO-0020"
        onChange={onChange}
      />,
    );
    const equipe = screen.getByRole("listbox", { name: /Na equipe do RDO/ });

    await user.click(within(equipe).getByRole("option", { name: /Ana/ }));
    await user.click(within(equipe).getByRole("option", { name: /Histórico/ }));
    await user.click(screen.getByRole("button", { name: "Remover" }));

    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange.mock.calls[0][0].maoObra).toEqual([]);
  });

  it("remove um colaborador herdado da equipe do novo RDO", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(
      <RdoWorkforceEditor
        draft={draft()}
        collaborators={catalog}
        sourceRdoNumber="RDO-0020"
        onChange={onChange}
      />,
    );

    await user.click(
      screen.getByRole("button", { name: "Remover Ana da equipe" }),
    );

    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        maoObra: expect.not.arrayContaining([
          expect.objectContaining({ colaboradorId: "worker-a" }),
        ]),
      }),
    );
  });

  it("busca por nome, código e papel sem esconder quem já está na equipe", async () => {
    const user = userEvent.setup();
    render(
      <RdoWorkforceEditor
        draft={{ ...draft(), maoObra: [draft().maoObra[0]] }}
        collaborators={catalog}
        sourceRdoNumber={null}
        onChange={vi.fn()}
      />,
    );
    const search = screen.getByLabelText("Buscar");
    const autorizados = screen.getByRole("listbox", {
      name: /Autorizados na obra/,
    });
    const equipe = screen.getByRole("listbox", { name: /Na equipe do RDO/ });

    await user.type(search, "operacional");
    expect(within(autorizados).getByRole("option", { name: /Bruno.*002/ }))
      .toBeVisible();
    expect(within(autorizados).getByRole("option", { name: /Carla.*003/ }))
      .toBeVisible();
    // A busca é do lado de fora; quem já está dentro não some da vista.
    expect(within(equipe).getByRole("option", { name: /Ana/ })).toBeVisible();

    await user.clear(search);
    await user.type(search, "Bruno");
    expect(within(autorizados).getByRole("option", { name: /Bruno/ }))
      .toBeVisible();
    await user.clear(search);
    await user.type(search, "002");
    expect(within(autorizados).getByRole("option", { name: /Bruno/ }))
      .toBeVisible();
  });

  it("mantém apontador nulo, trocável e derivado apenas da equipe selecionada", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    const manual = {
      ...draft().maoObra[0],
      localId: "row-manual",
      origemItemId: "",
      sourceRdoId: "",
      origin: "MANUAL" as const,
      colaboradorId: "",
      nomeColaborador: "Maria Servente",
      cargo: "Servente",
    };
    render(
      <RdoWorkforceEditor
        draft={{ ...draft(), maoObra: [...draft().maoObra, manual] }}
        collaborators={catalog}
        sourceRdoNumber={null}
        onChange={onChange}
      />,
    );
    const select = screen.getByLabelText("Apontador do RDO");
    expect(select).toHaveValue("");
    expect(screen.queryByRole("option", { name: "Histórico" })).not.toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "Maria Servente" }))
      .not.toBeInTheDocument();
    await user.selectOptions(select, "worker-a");
    expect(onChange).toHaveBeenLastCalledWith(
      expect.objectContaining({ apontadorColaboradorId: "worker-a" }),
    );
    await user.selectOptions(select, "");
    expect(onChange).toHaveBeenLastCalledWith(
      expect.objectContaining({ apontadorColaboradorId: "" }),
    );
  });

  it("move o foco entre checkboxes com setas, Home e End", async () => {
    const user = userEvent.setup();
    render(
      <RdoWorkforceEditor
        draft={{
          ...draft(),
          maoObra: [
            draft().maoObra[0],
            { ...draft().maoObra[0], localId: "row-b", colaboradorId: "worker-b", nomeColaborador: "Bruno" },
          ],
        }}
        collaborators={catalog}
        sourceRdoNumber={null}
        onChange={vi.fn()}
      />,
    );
    const ana = screen.getByRole("checkbox", { name: "Selecionar Ana" });
    const bruno = screen.getByRole("checkbox", { name: "Selecionar Bruno" });
    ana.focus();
    await user.keyboard("{ArrowDown}");
    expect(bruno).toHaveFocus();
    await user.keyboard("{Home}");
    expect(ana).toHaveFocus();
    await user.keyboard("{End}");
    expect(bruno).toHaveFocus();
  });
});
