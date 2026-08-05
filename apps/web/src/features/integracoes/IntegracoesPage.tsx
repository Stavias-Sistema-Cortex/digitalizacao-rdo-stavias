import {
  useCallback,
  useEffect,
  useState,
} from "react";

import { OperationalWorkspace } from "../../components/workspace/OperationalWorkspace";

import { SYNC_COMPLETED_EVENT } from "../../lib/sync/syncEvents";
import {
  desfechoRecusado,
  descricaoDaEspera,
  listarDesfechosIntegracao,
  listarIntegracoes,
  listarSolicitacoesIntegracaoPendentes,
  sincronizarIntegracao,
  testarConexaoIntegracao,
} from "./integracoesApi";
import type {
  IntegracaoPendingRequest,
  IntegracaoRequestOutcome,
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
    case "PARTIAL":
      return "Parcial";
    case "FAILED":
      return "Falha";
    case "RUNNING":
      return "Sincronizando";
    case "DISABLED":
      return "Desativada";
    // Respondeu bem, mas há tempo demais. Antes isso não tinha nome na tela:
    // derrubava a API inteira.
    case "ATRASADA":
      return "Atrasada";
    case "SEM_SINCRONIZACAO":
      return "Sem sincronização";
    default:
      return status;
  }
}

/**
 * O desfecho de uma solicitação, que não é o mesmo que o estado da integração.
 *
 * `statusLabel` descreve como a fonte está; aqui se descreve o que aconteceu com
 * o pedido. Chamar uma sincronização recusada de "Desativada" descreveria o
 * ambiente e deixaria de responder à única pergunta de quem clicou.
 */
function desfechoLabel(estado: string): string {
  switch (estado) {
    case "SUCCESS":
      return "Concluída";
    case "PARTIAL":
      return "Concluída em parte";
    case "FAILED":
      return "Falhou";
    case "RUNNING":
      return "Em andamento";
    case "DISABLED":
      return "Recusada";
    case "SEM_RESPOSTA":
      return "Sem resposta do servidor";
    default:
      return estado;
  }
}

function acaoLabel(acao: string): string {
  return acao === "TESTAR" ? "Teste de conexão" : "Sincronização";
}

export function IntegracoesPage({
  onBack,
}: IntegracoesPageProps) {
  const [integracoes, setIntegracoes] =
    useState<IntegracaoStatus[]>([]);
  const [pendingRequests, setPendingRequests] =
    useState<IntegracaoPendingRequest[]>([]);
  const [outcomes, setOutcomes] =
    useState<IntegracaoRequestOutcome[]>([]);
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
      // O que está guardado neste aparelho vem primeiro: sem rede, a consulta
      // abaixo estoura, e a fila e os desfechos já respondidos continuam sendo
      // a parte da tela que ainda tem o que dizer.
      setPendingRequests(
        await listarSolicitacoesIntegracaoPendentes(),
      );
      setOutcomes(await listarDesfechosIntegracao());
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

  // A solicitação sobe na mesma janela de qualquer lançamento. Sem ouvir o fim
  // da sincronização, a tela continuava anunciando como pendente algo que o
  // servidor já tinha executado — e só um "Atualizar" manual desmentia.
  useEffect(() => {
    function aoConcluirSincronizacao() {
      void load();
    }

    window.addEventListener(
      SYNC_COMPLETED_EVENT,
      aoConcluirSincronizacao,
    );

    return () => {
      window.removeEventListener(
        SYNC_COMPLETED_EVENT,
        aoConcluirSincronizacao,
      );
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
      if (action === "testar") {
        await testarConexaoIntegracao(id);
      } else {
        await sincronizarIntegracao(id);
      }

      setPendingRequests(
        await listarSolicitacoesIntegracaoPendentes(),
      );
      setOutcomes(await listarDesfechosIntegracao());
      setMessage(
        `Solicitação registrada — ${descricaoDaEspera(navigator.onLine)}.` +
        " O desfecho aparece abaixo quando o servidor responder.",
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
      eyebrow="Administração"
      title="Integrações"
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
                {descricaoDaEspera(navigator.onLine)}
              </li>
            ))}
          </ul>
        </section>
      )}

      {outcomes.length > 0 && (
        <section className="form-card" aria-label="Desfecho das solicitações">
          <h2>Desfecho das solicitações</h2>
          <ul className="integracoes-desfechos">
            {outcomes.map((outcome) => (
              <li
                key={outcome.id}
                className={
                  desfechoRecusado(outcome.estado)
                    ? "integracoes-desfecho integracoes-desfecho-recusado"
                    : "integracoes-desfecho"
                }
                {...(desfechoRecusado(outcome.estado)
                  ? { role: "alert" as const }
                  : {})}
              >
                <div>
                  <strong>{outcome.integracaoId}</strong>
                  {" · "}
                  {acaoLabel(outcome.acao)}
                  {" · "}
                  <span className="status-badge">
                    {desfechoLabel(outcome.estado)}
                  </span>
                  {" · "}
                  {outcome.executedAt
                    ? formatDateTime(outcome.executedAt)
                    : "sem horário informado"}
                </div>

                {outcome.mensagem && (
                  <div className="integracoes-desfecho-mensagem">
                    {outcome.mensagem}
                  </div>
                )}
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
                  {/*
                    * Sem a consulta, não se sabe quantas fontes existem. Afirmar
                    * "nenhuma" seria relatar como fato algo que ninguém apurou.
                    */}
                  {error
                    ? "Não foi possível consultar as fontes."
                    : "Nenhuma integração encontrada."}
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
