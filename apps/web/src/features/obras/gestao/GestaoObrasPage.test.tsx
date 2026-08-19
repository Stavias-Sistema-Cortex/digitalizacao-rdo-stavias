// @vitest-environment jsdom

import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { GestaoObrasPage } from "./GestaoObrasPage";
import type { ColaboradorApi, ObraAdminApi, VinculoApi } from "./gestaoObrasApi";

const api = vi.hoisted(() => ({
  listarObrasAdmin: vi.fn(),
  listarVinculos: vi.fn(),
  listarColaboradores: vi.fn(),
  queueVinculoColaborador: vi.fn(),
  queueRevogarVinculo: vi.fn(),
  alterarPapelColaborador: vi.fn(),
  emitirCodigoSenha: vi.fn(),
}));

vi.mock("./gestaoObrasApi", async () => {
  const real = await vi.importActual<typeof import("./gestaoObrasApi")>(
    "./gestaoObrasApi",
  );
  return { ...real, ...api };
});

function obra(id: string, nome: string): ObraAdminApi {
  return {
    id,
    codigoContrato: `CTR-${id}`,
    codigoCw: null,
    codigoInterno: null,
    nome,
    cliente: null,
    cidade: null,
    uf: null,
    rodovia: null,
    status: "ATIVA",
    atualizadoEm: "2026-08-01T12:00:00.000Z",
    arquivadoEm: null,
    versaoLinha: 1,
  };
}

function vinculo(id: string, obraId: string, nome: string): VinculoApi {
  return {
    id,
    obraId,
    colaboradorId: `colab-${id}`,
    colaboradorNome: nome,
    status: "ATIVO",
    papelNaObra: null,
    atribuidoEm: "2026-08-01T12:00:00.000Z",
    atribuidoPor: "alfa",
    revogadoEm: null,
    revogadoPor: null,
    versaoEntidade: 1,
  };
}

function colaborador(id: string, nome: string): ColaboradorApi {
  return {
    id,
    nome,
    cpfMascarado: null,
    nomeGrupo: null,
    nomePerfil: null,
    papelAcesso: "BETA",
    ativo: true,
  };
}

beforeEach(() => {
  for (const mock of Object.values(api)) mock.mockReset();
  api.listarVinculos.mockResolvedValue([]);
  api.listarColaboradores.mockResolvedValue([]);
});

afterEach(() => {
  cleanup();
});

describe("a gestão de obras", () => {
  /* "1 obras no escopo global" é o que a tela dizia com uma obra só. */
  it("conta a obra no singular quando há uma só", async () => {
    api.listarObrasAdmin.mockResolvedValue([obra("obra-1", "Quarta intervenção")]);

    render(<GestaoObrasPage />);

    expect(
      await screen.findByText("1 obra no escopo global"),
    ).toBeInTheDocument();
  });

  it("conta no plural a partir de duas", async () => {
    api.listarObrasAdmin.mockResolvedValue([
      obra("obra-1", "Quarta intervenção"),
      obra("obra-2", "Quinta intervenção"),
    ]);

    render(<GestaoObrasPage />);

    expect(
      await screen.findByText("2 obras no escopo global"),
    ).toBeInTheDocument();
  });

  /*
   * O vínculo da obra anterior sob o título da obra nova, com o botão de
   * revogar ativo: quem clicasse revogaria um vínculo de uma obra que nem
   * estava mais na tela.
   */
  it("não deixa os vínculos da obra anterior sob o título da obra nova", async () => {
    const user = userEvent.setup();
    api.listarObrasAdmin.mockResolvedValue([
      obra("obra-1", "Quarta intervenção"),
      obra("obra-2", "Quinta intervenção"),
    ]);
    api.listarVinculos.mockImplementation(async (obraId: string) => {
      if (obraId === "obra-1") return [vinculo("v1", "obra-1", "ADAO LEITE")];
      throw new Error("Falha ao ler os vínculos.");
    });

    render(<GestaoObrasPage />);

    await user.click(
      await screen.findByRole("button", { name: /Quarta intervenção/ }),
    );
    expect(await screen.findByText("ADAO LEITE")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /Quinta intervenção/ }));

    expect(
      await screen.findByText("Falha ao ler os vínculos."),
    ).toBeInTheDocument();
    expect(screen.queryByText("ADAO LEITE")).toBeNull();
    expect(
      screen.queryByRole("button", { name: "Revogar" }),
    ).toBeNull();
  });

  /*
   * O servidor recusa o segundo vínculo com 409, mas a recusa só chega no
   * push seguinte do sync — até lá a linha otimista ficava ao lado da que já
   * estava lá.
   */
  it("recusa vincular quem já está vinculado, sem enfileirar mutação", async () => {
    const user = userEvent.setup();
    api.listarObrasAdmin.mockResolvedValue([obra("obra-1", "Quarta intervenção")]);
    api.listarVinculos.mockResolvedValue([
      vinculo("v1", "obra-1", "ADAO LEITE"),
    ]);
    api.listarColaboradores.mockResolvedValue([
      colaborador("colab-v1", "ADAO LEITE"),
    ]);

    render(<GestaoObrasPage />);
    await user.click(
      await screen.findByRole("button", { name: /Quarta intervenção/ }),
    );
    await user.type(
      screen.getByRole("searchbox", { name: "Buscar colaborador" }),
      "ADAO{Enter}",
    );
    await user.selectOptions(
      await screen.findByRole("combobox", { name: "Colaborador" }),
      "colab-v1",
    );
    await user.click(screen.getByRole("button", { name: "Vincular" }));

    expect(
      await screen.findByText("Este colaborador já está vinculado a esta obra."),
    ).toBeInTheDocument();
    expect(api.queueVinculoColaborador).not.toHaveBeenCalled();
  });

  it("enfileira o vínculo de quem ainda não está na obra", async () => {
    const user = userEvent.setup();
    api.listarObrasAdmin.mockResolvedValue([obra("obra-1", "Quarta intervenção")]);
    api.listarVinculos.mockResolvedValue([
      vinculo("v1", "obra-1", "ADAO LEITE"),
    ]);
    api.listarColaboradores.mockResolvedValue([
      colaborador("colab-novo", "BENTO SOUZA"),
    ]);
    api.queueVinculoColaborador.mockResolvedValue({
      ...vinculo("v2", "obra-1", "BENTO SOUZA"),
      colaboradorId: "colab-novo",
      status: "PENDENTE",
      syncStatus: "PENDING_SYNC",
      pendingMutationId: "v2",
    });

    render(<GestaoObrasPage />);
    await user.click(
      await screen.findByRole("button", { name: /Quarta intervenção/ }),
    );
    await user.type(
      screen.getByRole("searchbox", { name: "Buscar colaborador" }),
      "BENTO{Enter}",
    );
    await user.selectOptions(
      await screen.findByRole("combobox", { name: "Colaborador" }),
      "colab-novo",
    );
    await user.click(screen.getByRole("button", { name: "Vincular" }));

    await waitFor(() =>
      expect(api.queueVinculoColaborador).toHaveBeenCalledWith(
        "obra-1",
        "colab-novo",
        undefined,
        undefined,
      ),
    );
    // O nome também está na lista de busca; a asserção é sobre a linha do
    // vínculo, que é a que carrega o botão de revogar.
    await waitFor(() =>
      expect(
        screen
          .getAllByRole("listitem")
          .some((linha) => linha.textContent?.includes("BENTO SOUZA")),
      ).toBe(true),
    );
  });

  it("revincula usando a identidade do vínculo histórico revogado", async () => {
    const user = userEvent.setup();
    const historico: VinculoApi = {
      ...vinculo("vinculo-historico", "obra-1", "BENTO SOUZA"),
      colaboradorId: "colab-revogado",
      status: "REVOGADO",
      revogadoEm: "2026-08-02T12:00:00.000Z",
      revogadoPor: "alfa",
    };
    api.listarObrasAdmin.mockResolvedValue([
      obra("obra-1", "Quarta intervenção"),
    ]);
    api.listarVinculos.mockResolvedValue([historico]);
    api.listarColaboradores.mockResolvedValue([
      colaborador("colab-revogado", "BENTO SOUZA"),
    ]);
    api.queueVinculoColaborador.mockResolvedValue({
      ...historico,
      status: "PENDENTE",
      revogadoEm: null,
      revogadoPor: null,
      syncStatus: "PENDING_SYNC",
      pendingMutationId: "mutacao-reativacao",
    });

    render(<GestaoObrasPage />);
    await user.click(
      await screen.findByRole("button", { name: /Quarta intervenção/ }),
    );
    await user.type(
      screen.getByRole("searchbox", { name: "Buscar colaborador" }),
      "BENTO{Enter}",
    );
    await user.selectOptions(
      await screen.findByRole("combobox", { name: "Colaborador" }),
      "colab-revogado",
    );
    await user.click(screen.getByRole("button", { name: "Vincular" }));

    await waitFor(() =>
      expect(api.queueVinculoColaborador).toHaveBeenCalledWith(
        "obra-1",
        "colab-revogado",
        undefined,
        "vinculo-historico",
      ),
    );
    expect(
      screen.queryByRole("heading", { name: "Histórico de revogações" }),
    ).toBeNull();
  });

  it("gera um código temporário individual sem criar perfil MySQL", async () => {
    const user = userEvent.setup();
    const targetId = "20000000-0000-4000-8000-000000000002";
    api.listarObrasAdmin.mockResolvedValue([]);
    api.listarColaboradores.mockResolvedValue([
      colaborador(targetId, "PESSOA ALVO"),
    ]);
    api.emitirCodigoSenha.mockResolvedValue({
      collaboratorId: targetId,
      name: "PESSOA ALVO",
      code: "12345678",
      purpose: "FIRST_ACCESS",
      expiresAt: "2026-08-17T13:30:00Z",
    });

    render(<GestaoObrasPage />);
    await user.type(
      screen.getByRole("searchbox", {
        name: "Buscar colaborador para alterar papel",
      }),
      "PESSOA ALVO{Enter}",
    );
    await user.click(await screen.findByRole("button", {
      name: "Gerar código de acesso para PESSOA ALVO",
    }));

    expect(api.emitirCodigoSenha).toHaveBeenCalledWith(targetId);
    expect(await screen.findByText("12345678")).toBeInTheDocument();
    expect(screen.getByText("Código temporário")).toBeInTheDocument();
    expect(
      screen.getByText("Primeiro acesso · expira em 30 min"),
    ).toBeInTheDocument();
    expect(
      screen.getByText("Exibido uma vez. Entregue ao colaborador."),
    ).toBeInTheDocument();
  });
});
