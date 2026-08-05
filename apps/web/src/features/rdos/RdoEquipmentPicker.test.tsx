// @vitest-environment jsdom

import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { createEmptyEquipamento } from "./createEmptyRdo";
import { RdoEquipmentPicker } from "./RdoEquipmentPicker";
import type { EquipamentoDraft } from "./rdo.types";
import type { RdoContextEquipment } from "./rdoLookupApi";

const PARQUE: RdoContextEquipment[] = [
  {
    id: "asset-esc",
    codigoExterno: "ESC-07",
    nome: "Escavadeira Hidráulica",
    categoria: "Terraplenagem",
  },
  {
    id: "asset-cam",
    codigoExterno: "CAM-12",
    nome: "Caminhão Basculante",
    categoria: "Transporte",
  },
];

function linha(patch: Partial<EquipamentoDraft>): EquipamentoDraft {
  return { ...createEmptyEquipamento(), ...patch };
}

afterEach(cleanup);

describe("escolha de equipamentos com o parque à vista", () => {
  it("mostra o parque de um lado e o que já entrou do outro", () => {
    render(
      <RdoEquipmentPicker
        parque={PARQUE}
        equipamentos={[
          linha({
            localId: "ja-lancado",
            assetId: "asset-cam",
            descricao: "Caminhão Basculante",
            prefixo: "CAM-12",
          }),
        ]}
        onChange={vi.fn()}
      />,
    );

    const parque = screen.getByRole("listbox", { name: /No parque da obra/ });
    const noRdo = screen.getByRole("listbox", { name: /Neste RDO/ });

    expect(within(parque).getByRole("option", { name: /Escavadeira/ }))
      .toBeVisible();
    // O que já foi lançado sai da oferta: duas linhas do mesmo asset dobrariam
    // a hora de máquina do dia sem ninguém ver.
    expect(within(parque).queryByRole("option", { name: /Caminhão/ }))
      .not.toBeInTheDocument();
    expect(within(noRdo).getByRole("option", { name: /Caminhão/ }))
      .toBeVisible();
  });

  it("lança várias máquinas de uma vez, já identificadas pelo cadastro", async () => {
    const usuario = userEvent.setup();
    const onChange = vi.fn();
    render(
      <RdoEquipmentPicker
        parque={PARQUE}
        equipamentos={[]}
        onChange={onChange}
      />,
    );

    await usuario.click(screen.getByRole("option", { name: /Escavadeira/ }));
    await usuario.click(screen.getByRole("option", { name: /Caminhão/ }));
    await usuario.click(screen.getByRole("button", { name: "Adicionar" }));

    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange.mock.calls[0][0]).toEqual([
      expect.objectContaining({
        assetId: "asset-esc",
        prefixo: "ESC-07",
        descricao: "Escavadeira Hidráulica",
        tipoEquipamento: "Terraplenagem",
      }),
      expect.objectContaining({ assetId: "asset-cam", prefixo: "CAM-12" }),
    ]);
  });

  it("tira várias do RDO de uma vez", async () => {
    const usuario = userEvent.setup();
    const onChange = vi.fn();
    const equipamentos = [
      linha({ localId: "l1", assetId: "asset-esc", descricao: "Escavadeira Hidráulica" }),
      linha({ localId: "l2", descricao: "Betoneira do empreiteiro" }),
    ];
    render(
      <RdoEquipmentPicker
        parque={PARQUE}
        equipamentos={equipamentos}
        onChange={onChange}
      />,
    );
    const noRdo = screen.getByRole("listbox", { name: /Neste RDO/ });

    await usuario.click(within(noRdo).getByRole("option", { name: /Escavadeira/ }));
    await usuario.click(within(noRdo).getByRole("option", { name: /Betoneira/ }));
    await usuario.click(screen.getByRole("button", { name: "Remover" }));

    expect(onChange).toHaveBeenCalledWith([]);
  });

  /*
   * Sem contexto — offline, ou obra sem parque cadastrado — a tela não pode
   * dar a entender que não há saída: a máquina ainda entra à mão pela ficha.
   */
  it("aponta a saída manual quando o parque não veio", () => {
    render(
      <RdoEquipmentPicker parque={[]} equipamentos={[]} onChange={vi.fn()} />,
    );

    expect(screen.getByText(/lançar a máquina à mão/)).toBeVisible();
  });
});
