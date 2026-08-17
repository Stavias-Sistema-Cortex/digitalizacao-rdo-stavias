// @vitest-environment jsdom

import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

const autenticarPorCpf = vi.hoisted(() => vi.fn());

vi.mock("./authService", () => ({ autenticarPorCpf }));

import { OfflineGrantRenewalPrompt } from "./OfflineGrantRenewalPrompt";

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("OfflineGrantRenewalPrompt", () => {
  it("reauthenticates with CPF and password, clears the field, and closes only on success", async () => {
    autenticarPorCpf.mockResolvedValue({ offlineGrant: "READY" });
    const onSuccess = vi.fn();
    const onCancel = vi.fn();
    const user = userEvent.setup();
    render(
      <OfflineGrantRenewalPrompt
        onSuccess={onSuccess}
        onCancel={onCancel}
      />,
    );

    await user.type(screen.getByLabelText("CPF para renovar"), "11144477735");
    const password = screen.getByLabelText("Senha para renovar");
    await user.type(password, "Senha individual forte 123!");
    await user.click(screen.getByRole("button", { name: "Renovar acesso offline" }));

    await waitFor(() => {
      expect(autenticarPorCpf).toHaveBeenCalledWith(
        "11144477735",
        "Senha individual forte 123!",
      );
      expect(onSuccess).toHaveBeenCalledTimes(1);
    });
    expect(password).toHaveValue("");
    expect(screen.queryByText("Senha individual forte 123!"))
      .not.toBeInTheDocument();
    expect(onCancel).not.toHaveBeenCalled();
  });

  it("stays open on failure without echoing the password", async () => {
    autenticarPorCpf.mockRejectedValue(new Error("Senha individual forte 123!"));
    const onSuccess = vi.fn();
    const user = userEvent.setup();
    render(
      <OfflineGrantRenewalPrompt
        onSuccess={onSuccess}
        onCancel={vi.fn()}
      />,
    );

    await user.type(screen.getByLabelText("CPF para renovar"), "11144477735");
    const password = screen.getByLabelText("Senha para renovar");
    await user.type(password, "Senha individual forte 123!");
    await user.click(screen.getByRole("button", { name: "Renovar acesso offline" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Não foi possível renovar o acesso offline deste aparelho.",
    );
    expect(password).toHaveValue("");
    expect(screen.queryByText("Senha individual forte 123!"))
      .not.toBeInTheDocument();
    expect(onSuccess).not.toHaveBeenCalled();
  });

  it("allows cancellation without authenticating", async () => {
    const onCancel = vi.fn();
    const user = userEvent.setup();
    render(
      <OfflineGrantRenewalPrompt
        onSuccess={vi.fn()}
        onCancel={onCancel}
      />,
    );

    await user.click(screen.getByRole("button", { name: "Agora não" }));

    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(autenticarPorCpf).not.toHaveBeenCalled();
  });
});
