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
    expect(screen.getByText("Indisponível")).toBeVisible();
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
    const addition = screen.getByLabelText("Adicionar colaborador autorizado");
    expect(within(addition).queryByRole("option", { name: /Ana/ }))
      .not.toBeInTheDocument();
    await user.selectOptions(addition, "worker-c");
    await user.click(screen.getByRole("button", { name: "Adicionar à equipe" }));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        maoObra: expect.arrayContaining([
          expect.objectContaining({ colaboradorId: "worker-c", selected: true }),
        ]),
      }),
    );
  });

  it("mantém apontador nulo, trocável e derivado apenas da equipe selecionada", async () => {
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
    const select = screen.getByLabelText("Apontador do RDO");
    expect(select).toHaveValue("");
    expect(screen.queryByRole("option", { name: "Histórico" })).not.toBeInTheDocument();
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
