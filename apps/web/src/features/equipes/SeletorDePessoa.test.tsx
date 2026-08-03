// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { ColaboradorLocalRecord } from "../../lib/db/db.types";
import { SeletorDePessoa } from "./SeletorDePessoa";

function pessoa(id: string, nome: string): ColaboradorLocalRecord {
  return {
    id,
    nome,
    cpfMascarado: null,
    nomePerfil: null,
    ativo: true,
    updatedAt: null,
    cachedAt: "",
  };
}

const TRES = [
  pessoa("1", "Rita Ferreira"),
  pessoa("2", "Ricardo Alves"),
  pessoa("3", "Ana Paula"),
];

afterEach(cleanup);

describe("SeletorDePessoa", () => {
  it("estreita a lista conforme o nome é digitado", async () => {
    const user = userEvent.setup();
    render(
      <SeletorDePessoa
        pessoas={TRES}
        selecionadaId=""
        onSelecionar={() => {}}
      />,
    );

    await user.click(screen.getByRole("combobox"));
    expect(screen.getAllByRole("option")).toHaveLength(3);

    await user.type(screen.getByRole("combobox"), "ri");
    expect(screen.getAllByRole("option")).toHaveLength(2);

    await user.type(screen.getByRole("combobox"), "t");
    const restantes = screen.getAllByRole("option");
    expect(restantes).toHaveLength(1);
    expect(restantes[0]).toHaveTextContent("Rita Ferreira");
  });

  it("escolhe pelo teclado sem tocar no mouse", async () => {
    const user = userEvent.setup();
    const escolhida = vi.fn();
    render(
      <SeletorDePessoa
        pessoas={TRES}
        selecionadaId=""
        onSelecionar={escolhida}
      />,
    );

    await user.click(screen.getByRole("combobox"));
    await user.keyboard("{ArrowDown}{ArrowDown}{Enter}");

    expect(escolhida).toHaveBeenCalledTimes(1);
    expect(escolhida.mock.calls[0][0]).toBe("2");
  });

  /**
   * O Enter pertence ao formulário quando não há opção destacada. Roubá-lo
   * sempre impediria de salvar pelo teclado.
   */
  it("não intercepta o Enter quando nada está destacado", async () => {
    const user = userEvent.setup();
    const enviou = vi.fn((evento: React.FormEvent) => evento.preventDefault());
    render(
      <form onSubmit={enviou}>
        <SeletorDePessoa
          pessoas={TRES}
          selecionadaId=""
          onSelecionar={() => {}}
        />
        <button type="submit">Salvar</button>
      </form>,
    );

    await user.click(screen.getByRole("combobox"));
    await user.keyboard("{Enter}");

    expect(enviou).toHaveBeenCalledTimes(1);
  });

  /**
   * O ponto da correção. O cadastro responde no máximo cinquenta pessoas por
   * consulta, então filtrar só o que já está carregado deixaria invisível quem
   * nunca chegou ao dispositivo — que era exatamente o defeito. A digitação
   * precisa alcançar o servidor.
   */
  it("leva o que foi digitado até a busca do servidor", async () => {
    const user = userEvent.setup();
    const buscar = vi.fn().mockResolvedValue(undefined);
    render(
      <SeletorDePessoa
        pessoas={TRES}
        selecionadaId=""
        onSelecionar={() => {}}
        onBuscar={buscar}
      />,
    );

    await user.click(screen.getByRole("combobox"));
    await user.type(screen.getByRole("combobox"), "zeca");

    await waitFor(() => expect(buscar).toHaveBeenCalledWith("zeca"));
  });

  /** Uma letra casaria com quase todo mundo: seria rede gasta para devolver
   * o mesmo recorte arbitrário que este seletor existe para eliminar. */
  it("não consulta o servidor por uma letra solta", async () => {
    const user = userEvent.setup();
    const buscar = vi.fn().mockResolvedValue(undefined);
    render(
      <SeletorDePessoa
        pessoas={TRES}
        selecionadaId=""
        onSelecionar={() => {}}
        onBuscar={buscar}
      />,
    );

    await user.click(screen.getByRole("combobox"));
    await user.type(screen.getByRole("combobox"), "r");
    // Folga confortável sobre os 300ms do debounce: se fosse disparar, teria
    // disparado.
    await new Promise((resolve) => setTimeout(resolve, 500));

    expect(buscar).not.toHaveBeenCalled();
  });

  it("mostra quem está escolhido e deixa trocar", async () => {
    const user = userEvent.setup();
    const escolhida = vi.fn();
    render(
      <SeletorDePessoa
        pessoas={TRES}
        selecionadaId="1"
        onSelecionar={escolhida}
      />,
    );

    expect(screen.getByText("Rita Ferreira")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /Trocar Rita/ }));

    expect(escolhida).toHaveBeenCalledWith("");
    expect(screen.getByRole("combobox")).toBeInTheDocument();
  });

  it("avisa quando há mais gente do que cabe na lista", async () => {
    const user = userEvent.setup();
    const muitas = Array.from({ length: 60 }, (_, indice) =>
      pessoa(String(indice), `Pessoa ${String(indice).padStart(2, "0")}`),
    );
    render(
      <SeletorDePessoa
        pessoas={muitas}
        selecionadaId=""
        onSelecionar={() => {}}
      />,
    );

    await user.click(screen.getByRole("combobox"));

    await waitFor(() =>
      expect(
        screen.getByText(/Há mais pessoas do que cabe na lista/),
      ).toBeInTheDocument(),
    );
  });

  it("diz quando não encontrou ninguém", async () => {
    const user = userEvent.setup();
    render(
      <SeletorDePessoa
        pessoas={TRES}
        selecionadaId=""
        onSelecionar={() => {}}
      />,
    );

    await user.click(screen.getByRole("combobox"));
    await user.type(screen.getByRole("combobox"), "xyz");

    expect(
      screen.getByText("Ninguém encontrado com esse nome."),
    ).toBeInTheDocument();
  });
});
