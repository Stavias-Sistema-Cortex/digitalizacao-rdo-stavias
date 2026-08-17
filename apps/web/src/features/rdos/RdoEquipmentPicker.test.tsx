// @vitest-environment jsdom

import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { createEmptyEquipamento } from "./createEmptyRdo";
import { RdoEquipmentPicker } from "./RdoEquipmentPicker";
import type { EquipamentoDraft } from "./rdo.types";
import type { RdoContextEquipment } from "./rdoLookupApi";

/**
 * O parque da obra virou uma lista de marcar, e o terceiro ficou atrás de um
 * botão.
 *
 * <p>Cada máquina era uma ficha com nove campos — asset, prefixo, descrição,
 * tipo, vínculo, quantidade, início, fim, observações — para dizer que a
 * retroescavadeira da obra trabalhou hoje. O que é exceção, a máquina de
 * terceiro que não está no Zeladoria, ocupava a tela inteira; o que é regra
 * não tinha lugar nenhum.
 */

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

function lista() {
  return screen.getByRole("list", { name: "Equipamentos do RDO" });
}

function caixaDe(nome: RegExp): HTMLInputElement {
  const item = within(lista())
    .getAllByRole("listitem")
    .find((it) => nome.test(it.textContent ?? ""));
  if (!item) throw new Error(`Linha não encontrada: ${String(nome)}`);
  return within(item).getByRole("checkbox");
}

afterEach(cleanup);

describe("marcação de equipamentos do RDO", () => {
  it("mostra numa lista só o que já entrou e o resto do parque", () => {
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

    // O que já foi lançado aparece marcado, e uma vez só: duas linhas do mesmo
    // asset dobrariam a hora de máquina do dia sem ninguém ver.
    expect(within(lista()).getAllByRole("listitem")).toHaveLength(2);
    expect(caixaDe(/Caminhão/)).toBeChecked();
    expect(caixaDe(/Escavadeira/)).not.toBeChecked();
  });

  it("lança a máquina já identificada pelo cadastro", async () => {
    const usuario = userEvent.setup();
    const onChange = vi.fn();
    render(
      <RdoEquipmentPicker
        parque={PARQUE}
        equipamentos={[]}
        onChange={onChange}
      />,
    );

    await usuario.click(caixaDe(/Escavadeira/));

    expect(onChange.mock.calls.at(-1)?.[0]).toEqual([
      expect.objectContaining({
        assetId: "asset-esc",
        prefixo: "ESC-07",
        descricao: "Escavadeira Hidráulica",
        tipoEquipamento: "Terraplenagem",
      }),
    ]);
  });

  it("tira do RDO a máquina desmarcada", async () => {
    const usuario = userEvent.setup();
    const onChange = vi.fn();
    render(
      <RdoEquipmentPicker
        parque={PARQUE}
        equipamentos={[
          linha({ localId: "l1", assetId: "asset-esc", descricao: "Escavadeira Hidráulica" }),
          linha({ localId: "l2", descricao: "Betoneira do empreiteiro" }),
        ]}
        onChange={onChange}
      />,
    );

    await usuario.click(caixaDe(/Escavadeira/));

    expect(onChange.mock.calls.at(-1)?.[0]).toEqual([
      expect.objectContaining({ localId: "l2" }),
    ]);
  });

  /*
   * A máquina de terceiro é a exceção que justifica campos livres, e por isso
   * eles ficam escondidos: abertos, competiam com a lista que responde à
   * pergunta comum.
   */
  it("mantém escondidos os campos do terceiro até alguém pedir por eles", async () => {
    const usuario = userEvent.setup();
    const onChange = vi.fn();
    render(
      <RdoEquipmentPicker
        parque={PARQUE}
        equipamentos={[]}
        onChange={onChange}
      />,
    );

    expect(screen.queryByLabelText("Prefixo ou placa")).toBeNull();

    await usuario.click(
      screen.getByRole("button", { name: "Adicionar equipamento de terceiro" }),
    );
    await usuario.type(screen.getByLabelText("Prefixo ou placa"), "BET-01");
    await usuario.type(
      screen.getByLabelText("Descrição"),
      "Betoneira do empreiteiro",
    );
    await usuario.click(screen.getByRole("button", { name: "Adicionar ao RDO" }));

    expect(onChange.mock.calls.at(-1)?.[0]).toEqual([
      expect.objectContaining({
        assetId: "",
        prefixo: "BET-01",
        descricao: "Betoneira do empreiteiro",
        tipoVinculo: "TERCEIRIZADO",
      }),
    ]);
  });

  /*
   * A exportação recusa equipamento sem descrição. Cair no prefixo evita que a
   * máquina suma do RDO por um campo que ninguém viu em branco.
   */
  it("usa o prefixo como descrição quando só ele foi escrito", async () => {
    const usuario = userEvent.setup();
    const onChange = vi.fn();
    render(
      <RdoEquipmentPicker parque={[]} equipamentos={[]} onChange={onChange} />,
    );

    await usuario.click(
      screen.getByRole("button", { name: "Adicionar equipamento de terceiro" }),
    );
    await usuario.type(screen.getByLabelText("Prefixo ou placa"), "BET-01");
    await usuario.click(screen.getByRole("button", { name: "Adicionar ao RDO" }));

    expect(onChange.mock.calls.at(-1)?.[0][0]).toMatchObject({
      prefixo: "BET-01",
      descricao: "BET-01",
    });
  });

  it("avisa a máquina já apontada em outro RDO do dia sem impedir a marcação", async () => {
    const usuario = userEvent.setup();
    const onChange = vi.fn();
    render(
      <RdoEquipmentPicker
        parque={PARQUE}
        equipamentos={[]}
        jaApontados={new Map([["asset-esc", "RDO-0042"]])}
        onChange={onChange}
      />,
    );

    expect(screen.getByText("Já apontado hoje no RDO-0042.")).toBeVisible();
    await usuario.click(caixaDe(/Escavadeira/));
    expect(onChange).toHaveBeenCalled();
  });

  /*
   * Sem contexto — offline, ou obra sem parque cadastrado — a tela não pode
   * dar a entender que não há saída.
   */
  it("aponta a saída manual quando o parque não veio", () => {
    render(
      <RdoEquipmentPicker parque={[]} equipamentos={[]} onChange={vi.fn()} />,
    );

    expect(screen.getByText(/lançar a máquina à mão/)).toBeVisible();
  });
});

/**
 * A lista passou a trazer o parque da empresa, e não só o que alguém marcou
 * como desta obra — que era ninguém: o conector da Zeladoria escreve o cadastro
 * e não o vínculo, então toda obra abria com a lista vazia e o apontador diante
 * de um único caminho, cadastrar à mão o que já estava cadastrado.
 */
describe("o parque da empresa e o da obra na mesma lista", () => {
  const PARQUE_MISTO: RdoContextEquipment[] = [
    {
      id: "asset-obra",
      codigoExterno: "ROL-03",
      nome: "Rolo Compactador",
      categoria: "Pavimentação",
      naObra: true,
    },
    {
      id: "asset-empresa",
      codigoExterno: "PA-09",
      nome: "Pá Carregadeira",
      categoria: "Terraplenagem",
      naObra: false,
    },
  ];

  function titulosNaOrdem(): string[] {
    return within(lista())
      .getAllByRole("listitem")
      .map((item) => item.textContent ?? "");
  }

  it("mostra a máquina que não é da obra e diz de onde ela vem", () => {
    render(
      <RdoEquipmentPicker
        parque={PARQUE_MISTO}
        equipamentos={[]}
        onChange={vi.fn()}
      />,
    );

    expect(screen.getByText("No parque da empresa")).toBeVisible();
    expect(caixaDe(/Pá Carregadeira/)).toBeDefined();
  });

  it("põe quem já é da obra antes do resto do parque", () => {
    render(
      // Fora de ordem de propósito: quem ordena é a tela, não quem chamou.
      <RdoEquipmentPicker
        parque={[PARQUE_MISTO[1], PARQUE_MISTO[0]]}
        equipamentos={[]}
        onChange={vi.fn()}
      />,
    );

    const titulos = titulosNaOrdem();
    expect(titulos[0]).toContain("Rolo Compactador");
    expect(titulos[1]).toContain("Pá Carregadeira");
  });

  it("marca a máquina do parque como qualquer outra", async () => {
    const usuario = userEvent.setup();
    const onChange = vi.fn();
    render(
      <RdoEquipmentPicker
        parque={PARQUE_MISTO}
        equipamentos={[]}
        onChange={onChange}
      />,
    );

    await usuario.click(caixaDe(/Pá Carregadeira/));

    expect(onChange.mock.calls.at(-1)?.[0][0]).toMatchObject({
      assetId: "asset-empresa",
      prefixo: "PA-09",
      descricao: "Pá Carregadeira",
    });
  });

  /*
   * Contexto guardado antes desta versão não tem `naObra`. Ler a ausência como
   * "está na obra" preserva o significado que ele tinha: naquele contexto, toda
   * máquina que aparecia estava mesmo vinculada. Lê-la como `false` mandaria o
   * parque inteiro de quem está offline para debaixo do cabeçalho errado.
   */
  it("trata contexto antigo, sem a marca, como máquina da obra", () => {
    render(
      <RdoEquipmentPicker parque={PARQUE} equipamentos={[]} onChange={vi.fn()} />,
    );

    expect(screen.queryByText("No parque da empresa")).toBeNull();
  });
});
