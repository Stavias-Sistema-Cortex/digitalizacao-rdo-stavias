// @vitest-environment jsdom

import {
  act,
  cleanup,
  render,
  screen,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import type { Root } from "react-dom/client";
import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from "vitest";

type RegistrationOptions = {
  immediate?: boolean;
  onNeedRefresh?: () => void;
  onRegisterError?: (error: unknown) => void;
};

const bootstrapMocks = vi.hoisted(() => ({
  initializeAuthSession: vi.fn(),
  initializeCortexDb: vi.fn(),
  registerSW: vi.fn(),
  updateServiceWorker: vi.fn(),
}));

vi.mock("virtual:pwa-register", () => ({
  registerSW: bootstrapMocks.registerSW,
}));

vi.mock("../App", () => ({
  default: ({
    initialAuthUnavailable = false,
  }: {
    initialAuthUnavailable?: boolean;
  }) => (
    <main data-auth-unavailable={String(initialAuthUnavailable)}>
      Aplicação
    </main>
  ),
}));

vi.mock("../features/auth/authService", () => ({
  initializeAuthSession: bootstrapMocks.initializeAuthSession,
}));

vi.mock("../lib/db/cortexDb", () => ({
  initializeCortexDb: bootstrapMocks.initializeCortexDb,
}));

import { mountNormalCortex } from "./normalBootstrap";

let registrationOptions: RegistrationOptions | undefined;

function createTestingRoot(): Root {
  return {
    render(children: ReactNode) {
      render(children);
    },
    unmount() {
      cleanup();
    },
  } as Root;
}

beforeEach(() => {
  registrationOptions = undefined;
  bootstrapMocks.initializeAuthSession.mockResolvedValue(null);
  bootstrapMocks.initializeCortexDb.mockResolvedValue(undefined);
  bootstrapMocks.updateServiceWorker.mockResolvedValue(undefined);
  bootstrapMocks.registerSW.mockImplementation(
    (options: RegistrationOptions) => {
      registrationOptions = options;
      return bootstrapMocks.updateServiceWorker;
    },
  );
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("normal PWA update bootstrap", () => {
  it.each([
    ["login", null],
    ["acesso offline", new Error("auth unavailable")],
  ])(
    "keeps the update prompt mounted during %s",
    async (_mode, authResult) => {
      if (authResult instanceof Error) {
        bootstrapMocks.initializeAuthSession.mockRejectedValue(
          authResult,
        );
      } else {
        bootstrapMocks.initializeAuthSession.mockResolvedValue(
          authResult,
        );
      }

      await mountNormalCortex(createTestingRoot());

      expect(
        screen.queryByRole("status", {
          name: "Atualização disponível",
        }),
      ).not.toBeInTheDocument();
      expect(
        registrationOptions?.onNeedRefresh,
      ).toBeTypeOf("function");

      act(() => {
        registrationOptions?.onNeedRefresh?.();
      });

      expect(
        screen.getByRole("status", {
          name: "Atualização disponível",
        }),
      ).toHaveTextContent(
        "Nova versão pronta.",
      );
      expect(
        bootstrapMocks.updateServiceWorker,
      ).not.toHaveBeenCalled();
    },
  );

  it("updates only after the explicit action", async () => {
    const user = userEvent.setup();

    await mountNormalCortex(createTestingRoot());
    act(() => {
      registrationOptions?.onNeedRefresh?.();
    });

    await user.click(
      screen.getByRole("button", {
        name: "Atualizar",
      }),
    );

    expect(
      bootstrapMocks.updateServiceWorker,
    ).toHaveBeenCalledOnce();
    expect(
      bootstrapMocks.updateServiceWorker,
    ).toHaveBeenCalledWith(true);
  });
});
