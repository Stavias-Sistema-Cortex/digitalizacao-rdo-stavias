import {
  useCallback,
  useEffect,
  useState,
} from "react";

import {
  listarIntegracoes,
  sincronizarIntegracao,
  testarConexaoIntegracao,
} from "./integracoesApi";
import type { IntegracaoStatus } from "./integracoes.types";
import "./IntegracoesPage.css";

interface IntegracoesPageProps {
  onBack: () => void;
}

function formatDateTime(
  value: string | null,
): string {
  if (!value) {
    return "Sem sincronização";
  }

  const date = new Date(value);

  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return new Intl.DateTimeFormat("pt-BR", {
    dateStyle: "short",
    timeStyle: "medium",
  }).format(date);
}

function formatDuration(
  value: number | null,
): string {
  if (value === null) {
    return "-";
  }

  if (value < 60) {
    return `${value}s`;
  }

  return `${Math.floor(value / 60)}min ${value % 60}s`;
}

function statusLabel(status: string) {
  switch (status) {
    case "SUCCESS":
      return "Sucesso";
    case "FAILED":
      return "Falha";
    case "RUNNING":
      return "Sincronizando";
    case "DISABLED":
      return "Desativada";
    case "SEM_SINCRONIZACAO":
      return "Sem sincronização";
    default:
      return status;
  }
}

export function IntegracoesPage({
  onBack,
}: IntegracoesPageProps) {
  const [integracoes, setIntegracoes] =
    useState<IntegracaoStatus[]>([]);
  const [selected, setSelected] =
    useState<IntegracaoStatus | null>(null);
  const [isLoading, setIsLoading] =
    useState(true);
  const [actionId, setActionId] =
    useState("");
  const [message, setMessage] =
    useState("");
  const [error, setError] =
    useState("");

  const load = useCallback(async () => {
    setIsLoading(true);
    setError("");

    try {
      const data = await listarIntegracoes();
      setIntegracoes(data);
      setSelected((current) => {
        if (!current) {
          return data[0] ?? null;
        }

        return (
          data.find(
            (item) => item.id === current.id,
          ) ?? data[0] ?? null
        );
      });
    } catch (loadError: unknown) {
      setError(
        loadError instanceof Error
          ? loadError.message
          : "Não foi possível carregar as integrações.",
      );
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    const timeoutId = window.setTimeout(() => {
      void load();
    }, 0);

    return () => {
      window.clearTimeout(timeoutId);
    };
  }, [load]);

  async function runAction(
    id: string,
    action:
      | "testar"
      | "sincronizar",
  ) {
    setActionId(`${id}:${action}`);
    setMessage("");
    setError("");

    try {
      const result =
        action === "testar"
          ? await testarConexaoIntegracao(id)
          : await sincronizarIntegracao(id);

      await load();

      if (result.status === "SUCCESS") {
        setMessage(result.mensagem);
      } else {
        setError(result.mensagem);
      }
    } catch (actionError: unknown) {
      await load();

      setError(
        actionError instanceof Error
          ? actionError.message
          : "A ação da integração falhou.",
      );
    } finally {
      setActionId("");
    }
  }

  return (
    <main className="page-shell integracoes-page">
      <header className="topbar">
        <div>
          <p className="eyebrow">
            Córtex · Administração
          </p>

          <h1>Integrações</h1>

          <p className="subtitle">
            Academy e Zeladoria sincronizadas para
            o espelho operacional do Córtex.
          </p>
        </div>

        <div className="workspace-actions">
          <button
            type="button"
            className="secondary-button"
            onClick={() => {
              void load();
            }}
            disabled={isLoading}
          >
            Atualizar
          </button>

          <button
            type="button"
            className="secondary-button"
            onClick={onBack}
          >
            Voltar aos RDOs
          </button>
        </div>
      </header>

      {message && (
        <div className="notice">
          {message}
        </div>
      )}

      {error && (
        <div
          className="notice notice-error"
          role="alert"
        >
          {error}
        </div>
      )}

      <section className="form-card integracoes-table-card">
        <table className="integracoes-table">
          <thead>
            <tr>
              <th>Fonte</th>
              <th>Estado</th>
              <th>Última sincronização</th>
              <th>Duração</th>
              <th>Lidos</th>
              <th>Criados</th>
              <th>Atualizados</th>
              <th>Erros</th>
              <th>Próxima</th>
              <th>Defasagem</th>
              <th>Ações</th>
            </tr>
          </thead>

          <tbody>
            {integracoes.map((item) => (
              <tr key={item.id}>
                <td>{item.nome}</td>
                <td>
                  <span className="status-badge">
                    {statusLabel(item.estado)}
                  </span>
                </td>
                <td>
                  {formatDateTime(
                    item.ultimaSincronizacao,
                  )}
                </td>
                <td>
                  {formatDuration(
                    item.duracaoSegundos,
                  )}
                </td>
                <td>{item.registrosLidos}</td>
                <td>{item.registrosCriados}</td>
                <td>{item.registrosAtualizados}</td>
                <td>
                  {item.erro ? "1" : "0"}
                </td>
                <td>{item.proximaSincronizacao}</td>
                <td>{item.defasagem}</td>
                <td>
                  <div className="integracoes-actions">
                    <button
                      type="button"
                      className="secondary-button"
                      onClick={() => {
                        void runAction(
                          item.id,
                          "testar",
                        );
                      }}
                      disabled={
                        actionId ===
                        `${item.id}:testar`
                      }
                    >
                      Testar
                    </button>

                    <button
                      type="button"
                      className="secondary-button"
                      onClick={() => {
                        void runAction(
                          item.id,
                          "sincronizar",
                        );
                      }}
                      disabled={
                        actionId ===
                        `${item.id}:sincronizar`
                      }
                    >
                      Sincronizar
                    </button>

                    <button
                      type="button"
                      className="secondary-button"
                      onClick={() => {
                        setSelected(item);
                      }}
                    >
                      Relatório
                    </button>
                  </div>
                </td>
              </tr>
            ))}

            {!isLoading &&
              integracoes.length === 0 && (
              <tr>
                <td colSpan={11}>
                  Nenhuma integração encontrada.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </section>

      {selected && (
        <section className="form-card integracoes-report">
          <div className="section-heading">
            <div>
              <span className="section-number">
                {selected.nome.slice(0, 1)}
              </span>

              <h2>{selected.nome}</h2>
            </div>
          </div>

          <dl>
            <div>
              <dt>Estado</dt>
              <dd>{selected.estado}</dd>
            </div>
            <div>
              <dt>Última sincronização</dt>
              <dd>
                {formatDateTime(
                  selected.ultimaSincronizacao,
                )}
              </dd>
            </div>
            <div>
              <dt>Registros lidos</dt>
              <dd>{selected.registrosLidos}</dd>
            </div>
            <div>
              <dt>Registros criados</dt>
              <dd>{selected.registrosCriados}</dd>
            </div>
            <div>
              <dt>Registros atualizados</dt>
              <dd>{selected.registrosAtualizados}</dd>
            </div>
            <div>
              <dt>Registros desativados</dt>
              <dd>{selected.registrosDesativados}</dd>
            </div>
            <div>
              <dt>Defasagem</dt>
              <dd>{selected.defasagem}</dd>
            </div>
            <div>
              <dt>Erro</dt>
              <dd>{selected.erro ?? "-"}</dd>
            </div>
          </dl>
        </section>
      )}
    </main>
  );
}
