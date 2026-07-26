// @vitest-environment jsdom

import type { PropsWithChildren } from "react";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { TeamDto, TeamPageDto } from "./teamApi";

const mocks = vi.hoisted(() => ({
  fetchTeams: vi.fn(),
  fetchOperationalRoles: vi.fn(),
  hydrateObrasRelacionadas: vi.fn(),
  listLocalTeams: vi.fn(),
  listLocalOperationalRoles: vi.fn(),
  replaceLocalTeams: vi.fn(),
  replaceLocalOperationalRoles: vi.fn(),
}));

vi.mock("../../components/shell/CortexShell", () => ({
  CortexShell: ({ children }: PropsWithChildren) => <>{children}</>,
}));

vi.mock("../auth/authSession", () => ({
  AUTH_SESSION_CHANGED_EVENT: "cortex:test-session-changed",
  getSession: () => null,
  hasOnlineSession: () => true,
  isAlfa: () => false,
}));

vi.mock("../home/homeHydration", () => ({
  hydrateObrasRelacionadas: mocks.hydrateObrasRelacionadas,
}));

vi.mock("../../lib/db/obraLocalRepository", () => ({
  listObrasLocais: vi.fn().mockResolvedValue([]),
}));

vi.mock("./teamApi", () => ({
  addTeamMember: vi.fn(),
  archiveTeam: vi.fn(),
  createTeam: vi.fn(),
  endTeamMember: vi.fn(),
  fetchOperationalRoles: mocks.fetchOperationalRoles,
  fetchTeam: vi.fn(),
  fetchTeamHistory: vi.fn(),
  fetchTeams: mocks.fetchTeams,
  fetchTeamWorksites: vi.fn(),
  updateTeam: vi.fn(),
  updateTeamMember: vi.fn(),
}));

vi.mock("./teamLocalRepository", () => ({
  getLocalTeam: vi.fn(),
  listLocalOperationalRoles: mocks.listLocalOperationalRoles,
  listLocalTeamHistory: vi.fn(),
  listLocalTeams: mocks.listLocalTeams,
  listLocalTeamWorksites: vi.fn(),
  putLocalTeam: vi.fn(),
  replaceLocalOperationalRoles: mocks.replaceLocalOperationalRoles,
  replaceLocalTeamHistory: vi.fn(),
  replaceLocalTeams: mocks.replaceLocalTeams,
  replaceLocalTeamWorksites: vi.fn(),
}));

import { EquipesPage } from "./EquipesPage";

const cachedTeam: TeamDto = {
  id: "EQUIPE:1",
  obraPrincipalId: "OBRA:1",
  obraNome: "Obra real",
  nome: "Equipe cacheada",
  descricao: null,
  status: "ATIVA",
  inicioValidadeEm: "2026-07-01T00:00:00.000Z",
  fimValidadeEm: null,
  versaoEntidade: 1,
  criadoEm: "2026-07-01T00:00:00.000Z",
  atualizadoEm: "2026-07-25T12:00:00.000Z",
  membros: [],
};

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/equipes"]}>
      <EquipesPage />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  Object.defineProperty(navigator, "onLine", {
    configurable: true,
    value: true,
  });
  vi.clearAllMocks();
  mocks.listLocalTeams.mockResolvedValue([cachedTeam]);
  mocks.listLocalOperationalRoles.mockResolvedValue([]);
  mocks.fetchOperationalRoles.mockResolvedValue([]);
  mocks.hydrateObrasRelacionadas.mockResolvedValue(0);
  mocks.replaceLocalTeams.mockResolvedValue(undefined);
  mocks.replaceLocalOperationalRoles.mockResolvedValue(undefined);
});

afterEach(() => {
  cleanup();
});

describe("EquipesPage sync truth", () => {
  it("stays local until the remote catalog has actually hydrated", async () => {
    const remote = deferred<TeamPageDto>();
    mocks.fetchTeams.mockReturnValueOnce(remote.promise);

    renderPage();

    await screen.findByText("Equipe cacheada");
    expect(screen.getByRole("status")).toHaveAttribute(
      "data-status",
      "LOCAL",
    );

    await act(async () => {
      remote.resolve({
        items: [cachedTeam],
        totalElements: 1,
        page: 0,
        size: 100,
        totalPages: 1,
      });
      await remote.promise;
    });

    await waitFor(() =>
      expect(screen.getByRole("status")).toHaveAttribute(
        "data-status",
        "SYNCED",
      ),
    );
  });

  it("keeps a cached catalog local when remote hydration fails", async () => {
    mocks.fetchTeams.mockRejectedValueOnce(new Error("API indisponível"));

    renderPage();

    await screen.findByText("Equipe cacheada");
    await waitFor(() =>
      expect(screen.getByRole("status")).toHaveAttribute(
        "data-status",
        "LOCAL",
      ),
    );
  });
});
