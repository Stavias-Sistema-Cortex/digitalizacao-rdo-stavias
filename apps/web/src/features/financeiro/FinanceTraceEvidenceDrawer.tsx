import { useEffect, useState } from "react";

import {
  buscarPdorAtual,
  type FinancePdorSnapshot,
  type FinanceRevenueTrace,
} from "./financeiroApi";
import { formatMoney } from "./financeiroView";

type Service = FinanceRevenueTrace["tiposServico"][number];

export function FinanceTraceEvidenceDrawer({
  service,
  onClose,
}: {
  service: Service | null;
  onClose: () => void;
}) {
  const [evidence, setEvidence] = useState<Record<string, FinancePdorSnapshot | null>>({});

  useEffect(() => {
    if (!service) {
      queueMicrotask(() => setEvidence({}));
      return;
    }
    let cancelled = false;
    void Promise.all(service.obras.map(async (obra) => [
      obra.obraId,
      await buscarPdorAtual(obra.obraId).catch(() => null),
    ] as const)).then((entries) => {
      if (!cancelled) setEvidence(Object.fromEntries(entries));
    });
    return () => {
      cancelled = true;
    };
  }, [service]);

  if (!service) return null;

  return (
    <div
      className="finance-evidence-backdrop"
      onMouseDown={(event) => {
        if (event.currentTarget === event.target) onClose();
      }}
    >
      <aside
        className="finance-evidence-drawer"
        role="dialog"
        aria-modal="true"
        aria-label="Evidências da composição da receita"
      >
        <header>
          <div>
            <p className="finance-kicker">Ontologia operacional</p>
            <h2>{service.nome}</h2>
            <p>{service.unidade} · {service.quantidadeRdos} RDOs de origem</p>
          </div>
          <button type="button" onClick={onClose} aria-label="Fechar evidências">×</button>
        </header>
        <p className="finance-inline-note">
          A composição preserva obra, item contratual, RDO, estados de receita e snapshot PDOR.
          Modificações ontológicas permanecem centralizadas em Home &gt; Memória.
        </p>
        <ol>
          {service.obras.map((obra) => {
            const pdor = evidence[obra.obraId];
            const pdorLoaded = Object.hasOwn(evidence, obra.obraId);
            return (
              <li key={obra.obraId}>
                <strong>{obra.obraNome}</strong>
                <span>
                  Produção {obra.totais.producao} {service.unidade} · custo {formatMoney(obra.totais.custo, "BRL")} · receita estimada {obra.receitaDisponivel ? formatMoney(obra.totais.receitaEstimada, "BRL") : "indisponível"}
                </span>
                <small>
                  Item contratual: {obra.codigoItemContratual ?? obra.itemContratualId ?? "não vinculado"}
                </small>
                <small>RDOs: {obra.rdoIds.join(", ") || "nenhum identificador disponível"}</small>
                <small>
                  Estados: medida {formatMoney(obra.totais.receitaMedida, "BRL")}, aprovada {formatMoney(obra.totais.receitaAprovada, "BRL")}, faturada {formatMoney(obra.totais.receitaFaturada, "BRL")}, recebida {formatMoney(obra.totais.receitaRecebida, "BRL")}
                </small>
                <small>
                  PDOR: {!pdorLoaded
                    ? "carregando evidência…"
                    : pdor
                      ? `snapshot ${pdor.dataReferencia} · receita prevista final ${pdor.receitaPrevistaFinal === null ? "indisponível" : formatMoney(pdor.receitaPrevistaFinal, "BRL")}`
                      : "sem snapshot disponível"}
                </small>
              </li>
            );
          })}
        </ol>
      </aside>
    </div>
  );
}
