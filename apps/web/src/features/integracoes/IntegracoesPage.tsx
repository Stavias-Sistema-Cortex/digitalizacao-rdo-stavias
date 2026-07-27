import {
  useCallback,
  useEffect,
  useState,
} from "react";

import { OperationalWorkspace } from "../../components/workspace/OperationalWorkspace";

import {
  listarIntegracoes,
  listarSolicitacoesIntegracaoPendentes,
  sincronizarIntegracao,
  testarConexaoIntegracao,
} from "./integracoesApi";
import type { IntegracaoPendingRequest } from "./integracoesApi";
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
    case "PARTIAL":
      return "Parcial";
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
  const [pendingRequests, setPendingRequests] =
    useState<IntegracaoPendingRequest[]>([]);
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
      setPendingRequests(
        await listarSolicitacoesIntegracaoPendentes(),
      );
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
      const request =
        action === "testar"
          ? await testarConexaoIntegracao(id)
          : await sincronizarIntegracao(id);

      setPendingRequests(
        await listarSolicitacoesIntegracaoPendentes(),
      );
      setMessage(
        `Solicitação ${request.id} pendente (${request.motivo}). `
          + "Ela será executada automaticamente após a reconexão.",
      );
    } catch (actionError: unknown) {
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
    <OperationalWorkspace
      className="integracoes-page"
      eyebrow="Administração · Transporte de dados"
      title="Integrações"
      description="Fontes autorizadas, execução real e estado de sincronização do espelho operacional."
      actions={(
        <>
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
        </>
      )}
      status={{
        code: isLoading ? "SYNCING" : error ? "REJECTED" : "SYNCED",
        label: isLoading
          ? "Consultando integrações"
          : error
            ? "Consulta indisponível"
            : `${integracoes.length} fontes autorizadas`,
      }}
    >

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

      {pendingRequests.length > 0 && (
        <section className="form-card" aria-label="Solicitações pendentes">
          <h2>Solicitações pendentes</h2>
          <ul>
            {pendingRequests.map((request) => (
              <li key={request.id}>
                <strong>{request.integracaoId}</strong>
                {" · "}
                {request.acao}
                {" · "}
                {request.estado}
                {" · "}
                {request.motivo}
                {" · "}
                <code>{request.id}</code>
              </li>
            ))}
          </ul>
        </section>
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
              <h2>{selected.nome}</h2>
            </div>
          </div>

          <dl>
            <div>
              <dt>Estado</dt>
              <dd>{statusLabel(selected.estado)}</dd>
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
    </OperationalWorkspace>
  );
}
