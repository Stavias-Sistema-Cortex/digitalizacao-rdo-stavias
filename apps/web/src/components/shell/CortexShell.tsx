import { useRef, useState } from "react";
import type {
  CSSProperties,
  KeyboardEvent as ReactKeyboardEvent,
  PointerEvent as ReactPointerEvent,
  ReactNode,
} from "react";
import { useNavigate } from "react-router-dom";

import staviasTile from "../../assets/stavias-s-tile.png";
import { SyncStatusBanner } from "../SyncStatusBanner";
import { getSession, isAlfa } from "../../features/auth/authSession";
import { encerrarSessao } from "../../features/auth/authService";
import {
  SIDEBAR_WIDTH_DEFAULT,
  SIDEBAR_WIDTH_KEY,
  SIDEBAR_WIDTH_MAX,
  SIDEBAR_WIDTH_MIN,
  clampSidebarWidth,
  readStoredSidebarWidth,
} from "./sidebarWidth";

const SIDEBAR_COLLAPSED_KEY = "cortex.ui.sidebarRecolhida";

export type ShellActiveItem =
  | "home"
  | "rdos"
  | "obras"
  | "mensagens"
  | "tarefas"
  | "integracoes"
  | null;

interface CortexShellProps {
  active: ShellActiveItem;
  onRefresh?: () => void;
  isRefreshing?: boolean;
  children: ReactNode;
}

function sessionInitials(nome: string | null): string {
  if (!nome?.trim()) {
    return "US";
  }

  const parts = nome.trim().split(/\s+/);
  const first = parts[0]?.[0] ?? "";
  const last =
    parts.length > 1 ? parts[parts.length - 1][0] : "";

  return `${first}${last}`.toUpperCase() || "US";
}

export function CortexShell({
  active,
  onRefresh,
  isRefreshing = false,
  children,
}: CortexShellProps) {
  const navigate = useNavigate();
  const session = getSession();
  const alfa = isAlfa(session);

  const [isSidebarCollapsed, setIsSidebarCollapsed] =
    useState(
      () =>
        localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === "1",
    );
  const [sidebarWidth, setSidebarWidth] = useState(() =>
    readStoredSidebarWidth(
      localStorage.getItem(SIDEBAR_WIDTH_KEY),
    ),
  );
  const [isResizingSidebar, setIsResizingSidebar] =
    useState(false);
  const [isProfileMenuOpen, setIsProfileMenuOpen] =
    useState(false);
  const [isLoggingOut, setIsLoggingOut] = useState(false);
  const [logoutError, setLogoutError] = useState("");
  const resizeStartRef = useRef<{
    pointerId: number;
    startX: number;
    startWidth: number;
  } | null>(null);

  function toggleSidebar() {
    setIsSidebarCollapsed((collapsed) => {
      localStorage.setItem(
        SIDEBAR_COLLAPSED_KEY,
        collapsed ? "0" : "1",
      );
      return !collapsed;
    });
  }

  function persistSidebarWidth(width: number) {
    localStorage.setItem(SIDEBAR_WIDTH_KEY, String(width));
  }

  function handleResizerPointerDown(
    event: ReactPointerEvent<HTMLDivElement>,
  ) {
    if (isSidebarCollapsed) {
      return;
    }
    event.currentTarget.setPointerCapture(event.pointerId);
    resizeStartRef.current = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startWidth: sidebarWidth,
    };
    setIsResizingSidebar(true);
  }

  function handleResizerPointerMove(
    event: ReactPointerEvent<HTMLDivElement>,
  ) {
    const start = resizeStartRef.current;
    if (!start || start.pointerId !== event.pointerId) {
      return;
    }
    setSidebarWidth(
      clampSidebarWidth(
        start.startWidth + (event.clientX - start.startX),
      ),
    );
  }

  function handleResizerPointerUp(
    event: ReactPointerEvent<HTMLDivElement>,
  ) {
    const start = resizeStartRef.current;
    if (!start || start.pointerId !== event.pointerId) {
      return;
    }
    const width = clampSidebarWidth(
      start.startWidth + (event.clientX - start.startX),
    );
    resizeStartRef.current = null;
    setIsResizingSidebar(false);
    setSidebarWidth(width);
    persistSidebarWidth(width);
  }

  function handleResizerKeyDown(
    event: ReactKeyboardEvent<HTMLDivElement>,
  ) {
    let next: number | null = null;
    if (event.key === "ArrowLeft") {
      next = clampSidebarWidth(sidebarWidth - 16);
    }
    if (event.key === "ArrowRight") {
      next = clampSidebarWidth(sidebarWidth + 16);
    }
    if (next === null) {
      return;
    }
    event.preventDefault();
    setSidebarWidth(next);
    persistSidebarWidth(next);
  }

  function handleResizerDoubleClick() {
    setSidebarWidth(SIDEBAR_WIDTH_DEFAULT);
    persistSidebarWidth(SIDEBAR_WIDTH_DEFAULT);
  }

  async function handleLogout() {
    if (isLoggingOut) {
      return;
    }
    setIsLoggingOut(true);
    setLogoutError("");
    try {
      await encerrarSessao();
      window.location.assign("/");
    } catch {
      setLogoutError(
        "Não foi possível encerrar a sessão no servidor. Verifique a conexão e tente novamente.",
      );
      setIsLoggingOut(false);
    }
  }

  return (
    <div
      className={[
        "cortex-shell",
        isSidebarCollapsed ? "cortex-shell--collapsed" : "",
        isResizingSidebar ? "is-resizing" : "",
      ]
        .filter(Boolean)
        .join(" ")}
      style={
        {
          "--sidebar-width": `${sidebarWidth}px`,
        } as CSSProperties
      }
    >
      <aside className="cortex-sidebar">
        <button
          type="button"
          className="sidebar-toggle"
          onClick={toggleSidebar}
          aria-expanded={!isSidebarCollapsed}
          aria-label={
            isSidebarCollapsed
              ? "Expandir menu"
              : "Recolher menu"
          }
          title={
            isSidebarCollapsed
              ? "Expandir menu"
              : "Recolher menu"
          }
        >
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2.4"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="M14.5 6 9 12l5.5 6" />
          </svg>
        </button>

        <div
          className="sidebar-resizer"
          role="separator"
          aria-orientation="vertical"
          aria-label="Ajustar largura do menu"
          aria-valuemin={SIDEBAR_WIDTH_MIN}
          aria-valuemax={SIDEBAR_WIDTH_MAX}
          aria-valuenow={sidebarWidth}
          tabIndex={0}
          onPointerDown={handleResizerPointerDown}
          onPointerMove={handleResizerPointerMove}
          onPointerUp={handleResizerPointerUp}
          onPointerCancel={handleResizerPointerUp}
          onKeyDown={handleResizerKeyDown}
          onDoubleClick={handleResizerDoubleClick}
        />

        <div className="sidebar-brand">
          <img
            className="sidebar-brand-lockup"
            src="/stavias-cortex-logo.png"
            alt="Stavias Córtex"
            draggable={false}
          />
          <img
            className="sidebar-brand-mark"
            src={staviasTile}
            alt="Stavias Córtex"
            draggable={false}
          />
        </div>

        <nav
          className="sidebar-nav"
          aria-label="Navegação principal"
        >
          <button
            type="button"
            className={
              active === "home"
                ? "sidebar-nav-item active"
                : "sidebar-nav-item"
            }
            title="Home"
            onClick={() => navigate("/home")}
          >
            <img
              src="/icons8/home.png"
              alt=""
              draggable={false}
            />
            <span className="sidebar-label">Home</span>
          </button>
          <button
            type="button"
            className={
              active === "rdos"
                ? "sidebar-nav-item active"
                : "sidebar-nav-item"
            }
            title="RDO"
            onClick={() => navigate("/rdos")}
          >
            <img
              src="/icons8/edit-pencil.png"
              alt=""
              draggable={false}
            />
            <span className="sidebar-label">RDO</span>
          </button>
          <button
            type="button"
            className={
              active === "obras"
                ? "sidebar-nav-item active"
                : "sidebar-nav-item"
            }
            title="Obras"
            onClick={() => navigate("/obras")}
          >
            <img
              src="/icons8/location.png"
              alt=""
              draggable={false}
            />
            <span className="sidebar-label">Obras</span>
          </button>
          <button
            type="button"
            className="sidebar-nav-item"
            title="Equipes"
          >
            <img
              src="/icons8/user.png"
              alt=""
              draggable={false}
            />
            <span className="sidebar-label">Equipes</span>
          </button>
          <button
            type="button"
            className={
              active === "mensagens"
                ? "sidebar-nav-item active"
                : "sidebar-nav-item"
            }
            title="Mensagens"
            onClick={() => navigate("/mensagens")}
          >
            <img
              src="/icons8/star.png"
              alt=""
              draggable={false}
            />
            <span className="sidebar-label">Mensagens</span>
          </button>
          <button
            type="button"
            className={
              active === "tarefas"
                ? "sidebar-nav-item active"
                : "sidebar-nav-item"
            }
            title="Tarefas"
            onClick={() => navigate("/tarefas")}
          >
            <img
              src="/icons8/done.png"
              alt=""
              draggable={false}
            />
            <span className="sidebar-label">Tarefas</span>
          </button>
          <button
            type="button"
            className="sidebar-nav-item"
            title="Relatórios"
          >
            <img
              src="/icons8/file.png"
              alt=""
              draggable={false}
            />
            <span className="sidebar-label">Relatórios</span>
          </button>
        </nav>

        <div className="sidebar-footer">
          {alfa && (
            <button
              type="button"
              onClick={() => navigate("/obras/gestao")}
              title="Gerir obras e vínculos"
            >
              <img
                src="/icons8/location.png"
                alt=""
                draggable={false}
              />
              <span className="sidebar-label">Gerir obras</span>
            </button>
          )}
          {alfa && (
            <button
              type="button"
              onClick={() => navigate("/integracoes")}
              title="Integrações"
            >
              <img
                src="/icons8/settings.png"
                alt=""
                draggable={false}
              />
              <span className="sidebar-label">Integrações</span>
            </button>
          )}
          <button
            type="button"
            onClick={onRefresh}
            disabled={!onRefresh || isRefreshing}
            title="Atualizar dados"
          >
            <img
              src="/icons8/restart.png"
              alt=""
              draggable={false}
            />
            <span className="sidebar-label">
              Atualizar dados
            </span>
          </button>
        </div>
      </aside>

      <div className="floating-controls">
        <SyncStatusBanner />
        <div className="profile-menu-anchor">
          <button
            type="button"
            className="avatar-button"
            onClick={() =>
              setIsProfileMenuOpen((open) => !open)
            }
            aria-expanded={isProfileMenuOpen}
            aria-haspopup="menu"
            title={session?.nome ?? "Perfil"}
          >
            {sessionInitials(session?.nome ?? null)}
          </button>
          {isProfileMenuOpen && (
            <div className="profile-menu" role="menu">
              <p className="profile-menu-name">
                {session?.nome ?? "Colaborador"}
              </p>
              <p className="profile-menu-scope">
                {alfa
                  ? "Escopo global (Alfa)"
                  : "Escopo das obras vinculadas (Beta)"}
              </p>
              <button
                type="button"
                className="profile-menu-security"
                role="menuitem"
                onClick={() => {
                  setIsProfileMenuOpen(false);
                  navigate("/seguranca");
                }}
              >
                Segurança do dispositivo
              </button>
              <button
                type="button"
                className="profile-menu-logout"
                role="menuitem"
                onClick={() => {
                  void handleLogout();
                }}
                disabled={isLoggingOut}
              >
                {isLoggingOut ? "Saindo..." : "Sair"}
              </button>
              {logoutError ? (
                <p className="profile-menu-error" role="alert">
                  {logoutError}
                </p>
              ) : null}
            </div>
          )}
        </div>
      </div>

      {children}
    </div>
  );
}
