// @vitest-environment jsdom

import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { LoginPage } from "./LoginPage";

afterEach(() => {
  cleanup();
  vi.unstubAllEnvs();
});

describe("LoginPage access methods", () => {
  it("does not offer CPF-only authentication in the production view", () => {
    vi.stubEnv("DEV", false);
    vi.stubEnv("PROD", true);

    render(<LoginPage />);

    expect(
      screen.queryByRole("button", { name: "Entrar com CPF" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Confirmar com passkey" }),
    ).toBeInTheDocument();
  });

  it("offers CPF first and passkey as fallback in local development", () => {
    vi.stubEnv("DEV", true);
    vi.stubEnv("PROD", false);

    render(<LoginPage />);

    expect(
      screen.getByRole("button", { name: "Entrar com CPF" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Entrar com passkey" }),
    ).toBeInTheDocument();
  });
});
