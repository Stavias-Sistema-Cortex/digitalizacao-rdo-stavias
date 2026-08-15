import { useEffect, useRef, useState } from "react";

import { AUTH_SESSION_CHANGED_EVENT } from "../auth/authSession";
import { SYNC_COMPLETED_EVENT } from "../../lib/sync/syncEvents";
import {
  formatCurrencyDecimal,
  formatQuantityDecimal,
} from "./revenueDecimal";
import {
  decidePendingRevenueExecution,
  fetchPendingRevenueExecutions,
  type PendingRevenueExecution,
} from "./revenuePendingApi";

interface FinanceRevenuePendingValidationPanelProps {
  obraId: string;
  canApprove: boolean;
  onDecisionApplied?: () => void;
}

function formatCivilDate(value: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  return match ? `${match[3]}/${match[2]}/${match[1]}` : value;
}

function priceReasonLabel(reason: string): string {
  if (reason === "SERVICE_UNMAPPED") {
    return "o RDO não está ligado a um serviço do catálogo";
  }
  if (reason === "SERVICE_NOT_FOUND") {
    return "o serviço do RDO não existe mais no catálogo";
  }
  if (reason === "SERVICE_INACTIVE") {
    return "o serviço está inativo no catálogo";
  }
  if (reason === "UNIT_MISSING") {
    return "a unidade da execução não foi informada";
  }
  if (reason === "EXACT_PRICE_AMBIGUOUS") {
    return "há mais de um preço vigente compatível";
  }
  return "não há preço exato vigente para a data e unidade desta execução";
}

function online(): boolean {
  return typeof navigator === "undefined" || navigator.onLine;
}

function canValidate(row: PendingRevenueExecution): boolean {
  return row.approvalState === "REGISTERED"
    ? row.priceState === "EXACT_ACTIVE"
    : row.legacyEvidenceState === "VERIFIABLE";
}

function canShowValidation(row: PendingRevenueExecution): boolean {
  return row.approvalState === "REGISTERED" ||
    row.legacyEvidenceState === "VERIFIABLE";
}

export function FinanceRevenuePendingValidationPanel({
  obraId,
  canApprove,
  onDecisionApplied,
}: FinanceRevenuePendingValidationPanelProps) {
  const [rows, setRows] = useState<PendingRevenueExecution[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [refreshVersion, setRefreshVersion] = useState(0);
  const [decidingExecutionId, setDecidingExecutionId] = useState("");
  const [rejectingExecutionId, setRejectingExecutionId] = useState("");
  const [rejectionJustification, setRejectionJustification] = useState("");
  const requestSequence = useRef(0);

  const requestRefresh = () => {
    setRefreshVersion((version) => version + 1);
  };

  useEffect(() => {
    const requestId = requestSequence.current + 1;
    requestSequence.current = requestId;
    let active = true;
    queueMicrotask(() => {
      if (!active || requestId !== requestSequence.current) return;
      setError("");
      setRejectingExecutionId("");
      setRejectionJustification("");
      if (!online()) {
        setRows([]);
        setLoading(false);
        setError(
          "A fila de validação exige conexão com o servidor; nenhuma decisão local pode virar receita.",
        );
        return;
      }
      setLoading(true);
      void fetchPendingRevenueExecutions(obraId)
        .then((pending) => {
          if (active && requestId === requestSequence.current) {
            setRows(pending);
          }
        })
        .catch((reason: unknown) => {
          if (active && requestId === requestSequence.current) {
            setRows([]);
            setError(reason instanceof Error
              ? reason.message
              : "Não foi possível ler a fila de validação financeira.");
          }
        })
        .finally(() => {
          if (active && requestId === requestSequence.current) {
            setLoading(false);
          }
        });
    });
    return () => {
      active = false;
    };
  }, [obraId, refreshVersion]);

  useEffect(() => {
    const reset = () => {
      requestSequence.current += 1;
      setRows([]);
      setError("");
      setNotice("");
      setDecidingExecutionId("");
      setRejectingExecutionId("");
      setRejectionJustification("");
      requestRefresh();
    };
    window.addEventListener(SYNC_COMPLETED_EVENT, requestRefresh);
    window.addEventListener("online", requestRefresh);
    window.addEventListener("offline", requestRefresh);
    window.addEventListener(AUTH_SESSION_CHANGED_EVENT, reset);
    return () => {
      window.removeEventListener(SYNC_COMPLETED_EVENT, requestRefresh);
      window.removeEventListener("online", requestRefresh);
      window.removeEventListener("offline", requestRefresh);
      window.removeEventListener(AUTH_SESSION_CHANGED_EVENT, reset);
    };
  }, []);

  async function decide(
    row: PendingRevenueExecution,
    decision: "VALIDAR" | "REJEITAR",
    justification: string | null,
  ) {
    if (!canApprove || decidingExecutionId || !online()) return;
    if (decision === "VALIDAR" && !canValidate(row)) return;

    setDecidingExecutionId(row.executionId);
    setError("");
    setNotice("");
    try {
      await decidePendingRevenueExecution({
        rdoId: row.rdoId,
        executionId: row.executionId,
        decision,
        justification,
        baseVersion: row.rdoEntityVersion,
        clientMutationId: crypto.randomUUID(),
      });
      setNotice(
        decision === "VALIDAR"
          ? "Execução validada e registrada na receita. O rastro e o PDOR foram atualizados com a decisão confirmada."
          : "Execução rejeitada com justificativa. Ela não entra na receita nem no PDOR.",
      );
      setRejectingExecutionId("");
      setRejectionJustification("");
      requestRefresh();
      onDecisionApplied?.();
    } catch (reason: unknown) {
      setError(reason instanceof Error
        ? reason.message
        : "Não foi possível registrar a decisão financeira.");
    } finally {
      setDecidingExecutionId("");
    }
  }

  return (
    <section
      className="finance-revenue-pending"
      aria-labelledby="finance-pending-validation-title"
    >
      <header>
        <div>
          <span>Conferência financeira</span>
          <h2 id="finance-pending-validation-title">Execuções pendentes</h2>
          <p>
            Produção registrada só entra na receita e no PDOR depois da
            validação explícita. Registros legados só podem ser aceitos pela
            evidência de preço que já ficou persistida.
          </p>
        </div>
        <div className="finance-revenue-pending__count">
          <strong>{rows.length}</strong>
          <span>{rows.length === 1 ? "pendência" : "pendências"}</span>
        </div>
      </header>

      {!canApprove ? (
        <p className="finance-revenue-pending__read-only" role="status">
          Somente quem tem FINANCEIRO_APROVAR nesta obra pode confirmar ou
          rejeitar uma execução.
        </p>
      ) : null}
      {loading ? (
        <p className="finance-loading" role="status">
          Lendo execuções registradas…
        </p>
      ) : null}
      {error ? <p className="finance-error-state" role="alert">{error}</p> : null}
      {notice ? (
        <p className="finance-revenue-pending__notice" role="status">
          {notice}
        </p>
      ) : null}
      {!loading && !error && rows.length === 0 ? (
        <div className="finance-revenue-pending__empty">
          Não há execução registrada aguardando validação nesta obra.
        </div>
      ) : null}
      {!error && rows.length > 0 ? (
        <div className="finance-revenue-pending__list">
          {rows.map((row) => {
            const validationAllowed = canValidate(row);
            const showValidation = canShowValidation(row);
            const deciding = decidingExecutionId === row.executionId;
            const rejecting = rejectingExecutionId === row.executionId;
            return (
              <article
                key={row.executionId}
                className="finance-revenue-pending__row"
              >
                <div className="finance-revenue-pending__identity">
                  <time dateTime={row.executionDate}>
                    {formatCivilDate(row.executionDate)}
                  </time>
                  <strong>{row.rdoNumber ?? row.rdoId}</strong>
                </div>
                <div className="finance-revenue-pending__service">
                  <strong>{row.serviceName}</strong>
                  {row.serviceCode ? <code>{row.serviceCode}</code> : null}
                  <small>
                    {formatQuantityDecimal(row.quantity)} {row.unit}
                  </small>
                </div>
                <div className="finance-revenue-pending__price">
                  {row.approvalState === "LEGACY_UNVERIFIED" &&
                  row.legacyEvidenceState === "VERIFIABLE" ? (
                    <>
                      <span>Preço persistido na evidência</span>
                      <strong>
                        {formatCurrencyDecimal(row.evidenceUnitPrice!, 4)} / {row.unit}
                      </strong>
                      <small>
                        É o preço já registrado no legado; não é preço atual
                        nem preço vigente.
                      </small>
                    </>
                  ) : row.approvalState === "LEGACY_UNVERIFIED" ? (
                    <>
                      <span>Evidência histórica não é verificável</span>
                      <small>
                        O registro legado não pode ser aceito como receita.
                        Rejeite-o para mantê-lo fora do PDOR.
                      </small>
                    </>
                  ) : row.priceState === "EXACT_ACTIVE" ? (
                    <>
                      <span>Preço exato vigente</span>
                      <strong>
                        {formatCurrencyDecimal(row.currentUnitPrice!, 4)} / {row.unit}
                      </strong>
                    </>
                  ) : (
                    <>
                      <span>Não validável</span>
                      <small>{priceReasonLabel(row.priceReason)}</small>
                    </>
                  )}
                </div>
                {canApprove ? (
                  <div className="finance-revenue-pending__actions">
                    {showValidation ? (
                      <button
                        type="button"
                        onClick={() => void decide(row, "VALIDAR", null)}
                        disabled={
                          !validationAllowed ||
                          Boolean(decidingExecutionId) ||
                          !online()
                        }
                      >
                        {deciding && !rejecting ? "Validando…" : "Validar"}
                      </button>
                    ) : null}
                    <button
                      type="button"
                      className="is-secondary"
                      onClick={() => {
                        setRejectingExecutionId(row.executionId);
                        setRejectionJustification("");
                        setError("");
                      }}
                      disabled={Boolean(decidingExecutionId) || !online()}
                    >
                      Rejeitar
                    </button>
                  </div>
                ) : null}
                {canApprove && rejecting ? (
                  <form
                    className="finance-revenue-pending__rejection"
                    onSubmit={(event) => {
                      event.preventDefault();
                      const justification = rejectionJustification.trim();
                      if (!justification) return;
                      void decide(row, "REJEITAR", justification);
                    }}
                  >
                    <label>
                      Justificativa da rejeição
                      <textarea
                        value={rejectionJustification}
                        onChange={(event) => setRejectionJustification(
                          event.target.value,
                        )}
                        maxLength={2000}
                        disabled={Boolean(decidingExecutionId)}
                      />
                    </label>
                    <div>
                      <button
                        type="submit"
                        disabled={
                          !rejectionJustification.trim() ||
                          Boolean(decidingExecutionId) ||
                          !online()
                        }
                      >
                        {deciding ? "Rejeitando…" : "Confirmar rejeição"}
                      </button>
                      <button
                        type="button"
                        className="is-secondary"
                        onClick={() => {
                          setRejectingExecutionId("");
                          setRejectionJustification("");
                        }}
                        disabled={Boolean(decidingExecutionId)}
                      >
                        Cancelar
                      </button>
                    </div>
                  </form>
                ) : null}
              </article>
            );
          })}
        </div>
      ) : null}
    </section>
  );
}
