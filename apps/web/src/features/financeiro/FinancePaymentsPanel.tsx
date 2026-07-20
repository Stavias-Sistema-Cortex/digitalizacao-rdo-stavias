import { useState } from "react";

import {
  arquivarLancamento,
  enviarCobranca,
  previsualizarCobranca,
  registrarLiquidacao,
  salvarLancamento,
} from "./financeiroApi";
import type {
  FinanceCategory,
  FinanceCharge,
  FinanceChargePreview,
  FinanceCostCenter,
  FinanceLedgerDraft,
  FinanceLedgerEntry,
  FinanceStatusDefinition,
  FinanceSupplier,
  FinancialPermission,
} from "./financeiro.types";
import {
  formatDate,
  formatDateTime,
  formatMoney,
  hasFinancialPermission,
  statusTone,
} from "./financeiroView";
import { FinanceDrawer } from "./FinanceDrawer";

interface FinancePaymentsPanelProps {
  obraId: string;
  ledger: FinanceLedgerEntry[];
  charges: FinanceCharge[];
  suppliers: FinanceSupplier[];
  costCenters: FinanceCostCenter[];
  categories: FinanceCategory[];
  statuses: FinanceStatusDefinition[];
  permissions: FinancialPermission[];
  onChanged: () => void;
}

type PaymentDrawer =
  | { type: "ledger"; ledger: FinanceLedgerEntry | null }
  | { type: "settlement"; ledger: FinanceLedgerEntry }
  | { type: "charge"; charge: FinanceCharge }
  | null;

export function FinancePaymentsPanel({
  obraId,
  ledger,
  charges,
  suppliers,
  costCenters,
  categories,
  statuses,
  permissions,
  onChanged,
}: FinancePaymentsPanelProps) {
  const [drawer, setDrawer] = useState<PaymentDrawer>(null);
  const canOperate = hasFinancialPermission(permissions, "FINANCEIRO_OPERAR");
  const ledgerStatuses = statuses.filter((status) =>
    status.agregadoTipo === "LANCAMENTO" && status.ativo,
  );

  return (
    <div className="finance-payments-layout">
      <section className="finance-workspace-section">
        <div className="finance-section-heading">
          <div>
            <p className="finance-kicker">Pagamentos e recebimentos</p>
            <h2>Lançamentos</h2>
            <p>{ledger.length} movimentos no filtro atual</p>
          </div>
          {canOperate && (
            <button type="button" className="finance-primary-action" onClick={() => setDrawer({ type: "ledger", ledger: null })}>
              Novo lançamento
            </button>
          )}
        </div>
        {ledger.length === 0 ? (
          <div className="finance-row-empty">Nenhum lançamento encontrado.</div>
        ) : (
          <div className="finance-table-wrap">
            <table className="finance-table">
              <thead><tr><th>Vencimento</th><th>Descrição</th><th>Tipo</th><th>Status</th><th className="is-number">Aberto</th><th><span className="sr-only">Ações</span></th></tr></thead>
              <tbody>
                {ledger.map((entry) => (
                  <tr key={entry.id}>
                    <td data-label="Vencimento">{formatDate(entry.vencimentoEm)}</td>
                    <td data-label="Descrição"><strong>{entry.descricao}</strong><span>{entry.fornecedorNome || entry.numeroDocumento || "Sem fornecedor"}</span></td>
                    <td data-label="Tipo">{entry.tipo === "RECEBER" ? "A receber" : "A pagar"}</td>
                    <td data-label="Status"><span className={`finance-pill is-${statusTone(entry.vencido ? "VENCIDO" : entry.statusCodigo)}`}>{entry.vencido ? "Vencido" : entry.statusNome || entry.statusCodigo}</span></td>
                    <td data-label="Aberto" className="is-number">{formatMoney(entry.valorAberto, entry.moeda)}</td>
                    <td className="finance-row-actions">
                      {canOperate && entry.valorAberto > 0 && <button type="button" onClick={() => setDrawer({ type: "settlement", ledger: entry })}>Liquidar</button>}
                      {canOperate && <button type="button" onClick={() => setDrawer({ type: "ledger", ledger: entry })}>Editar</button>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="finance-workspace-section finance-charge-section">
        <div className="finance-section-heading">
          <div>
            <p className="finance-kicker">E-mail rastreável</p>
            <h2>Cobranças</h2>
            <p>Pré-visualize antes de enviar pelo remetente configurado.</p>
          </div>
        </div>
        {charges.length === 0 ? (
          <div className="finance-row-empty">
            Nenhuma cobrança configurada para esta obra. Regras automáticas e modelos precisam ser ativados por um administrador.
          </div>
        ) : (
          <ul className="finance-charge-list">
            {charges.map((charge) => (
              <li key={charge.id}>
                <div>
                  <span className={`finance-pill is-${statusTone(charge.status)}`}>{charge.status.replaceAll("_", " ")}</span>
                  <strong>{charge.destinatario}</strong>
                  <small>{charge.modo} · {formatDateTime(charge.agendadaPara || charge.ocorrenciaPrevistaEm)}</small>
                </div>
                <span>{charge.tentativas} tentativa(s)</span>
                <button type="button" onClick={() => setDrawer({ type: "charge", charge })}>Revisar</button>
              </li>
            ))}
          </ul>
        )}
      </section>

      {drawer?.type === "ledger" && (
        <FinanceDrawer title={drawer.ledger ? "Editar lançamento" : "Novo lançamento"} onClose={() => setDrawer(null)} wide>
          <LedgerForm
            obraId={obraId}
            ledger={drawer.ledger}
            suppliers={suppliers}
            costCenters={costCenters}
            categories={categories}
            statuses={ledgerStatuses}
            onSaved={() => {
              setDrawer(null);
              onChanged();
            }}
          />
        </FinanceDrawer>
      )}

      {drawer?.type === "settlement" && (
        <FinanceDrawer title="Registrar liquidação" description={drawer.ledger.descricao} onClose={() => setDrawer(null)}>
          <SettlementForm ledger={drawer.ledger} onSaved={() => {
            setDrawer(null);
            onChanged();
          }} />
        </FinanceDrawer>
      )}

      {drawer?.type === "charge" && (
        <ChargePreview
          charge={drawer.charge}
          canSend={canOperate}
          onClose={() => setDrawer(null)}
          onChanged={onChanged}
        />
      )}
    </div>
  );
}

function LedgerForm({
  obraId,
  ledger,
  suppliers,
  costCenters,
  categories,
  statuses,
  onSaved,
}: {
  obraId: string;
  ledger: FinanceLedgerEntry | null;
  suppliers: FinanceSupplier[];
  costCenters: FinanceCostCenter[];
  categories: FinanceCategory[];
  statuses: FinanceStatusDefinition[];
  onSaved: () => void;
}) {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const valorOriginal = number(form, "valorOriginal");
    const desconto = number(form, "desconto");
    const juros = number(form, "juros");
    const multa = number(form, "multa");
    const draft: FinanceLedgerDraft = {
      obraId,
      notaFiscalId: ledger?.notaFiscalId ?? null,
      fornecedorId: nullableText(form, "fornecedorId"),
      centroCustoId: nullableText(form, "centroCustoId"),
      categoriaId: nullableText(form, "categoriaId"),
      responsavelId: ledger?.responsavelId ?? null,
      statusId: text(form, "statusId"),
      tipo: text(form, "tipo"),
      origem: ledger?.origem ?? "MANUAL",
      numeroDocumento: nullableText(form, "numeroDocumento"),
      descricao: text(form, "descricao"),
      dataCompetencia: text(form, "dataCompetencia"),
      dataEmissao: nullableText(form, "dataEmissao"),
      vencimentoEm: text(form, "vencimentoEm"),
      moeda: text(form, "moeda").toUpperCase(),
      valorOriginal,
      desconto,
      juros,
      multa,
      valorLiquido: valorOriginal - desconto + juros + multa,
    };
    if (!draft.statusId || !draft.tipo || !draft.descricao || !draft.dataCompetencia ||
      !draft.vencimentoEm || !draft.moeda || draft.valorLiquido < 0) {
      setError("Preencha status, tipo, descrição, competência, vencimento, moeda e valores válidos.");
      return;
    }
    setSaving(true);
    setError("");
    try {
      await salvarLancamento(draft, ledger ?? undefined);
      onSaved();
    } catch (reason: unknown) {
      setError(reason instanceof Error ? reason.message : "Não foi possível salvar o lançamento.");
    } finally {
      setSaving(false);
    }
  }

  async function archive() {
    if (!ledger || !window.confirm("Arquivar este lançamento? O histórico será preservado.")) return;
    setSaving(true);
    try {
      await arquivarLancamento(ledger);
      onSaved();
    } catch (reason: unknown) {
      setError(reason instanceof Error ? reason.message : "Não foi possível arquivar o lançamento.");
      setSaving(false);
    }
  }

  return (
    <form className="finance-form" onSubmit={(event) => void submit(event)}>
      <div className="finance-form-grid">
        <label className="is-wide">Descrição<input required autoFocus name="descricao" defaultValue={ledger?.descricao ?? ""} /></label>
        <label>Tipo<select required name="tipo" defaultValue={ledger?.tipo ?? "PAGAR"}><option value="PAGAR">A pagar</option><option value="RECEBER">A receber</option></select></label>
        <label>Status<select required name="statusId" defaultValue={ledger?.statusId ?? statuses[0]?.id ?? ""}><option value="">Selecione</option>{statuses.map((status) => <option key={status.id} value={status.id}>{status.nome}</option>)}</select></label>
        <label>Fornecedor<select name="fornecedorId" defaultValue={ledger?.fornecedorId ?? ""}><option value="">Não informado</option>{suppliers.map((supplier) => <option key={supplier.id} value={supplier.id}>{supplier.nomeFantasia || supplier.razaoSocial}</option>)}</select></label>
        <label>Centro de custo<select name="centroCustoId" defaultValue={ledger?.centroCustoId ?? ""}><option value="">Não informado</option>{costCenters.map((center) => <option key={center.id} value={center.id}>{center.codigo} · {center.nome}</option>)}</select></label>
        <label>Categoria<select name="categoriaId" defaultValue={ledger?.categoriaId ?? ""}><option value="">Não informada</option>{categories.map((category) => <option key={category.id} value={category.id}>{category.nome}</option>)}</select></label>
        <label>Número do documento<input name="numeroDocumento" defaultValue={ledger?.numeroDocumento ?? ""} /></label>
        <label>Competência<input required type="date" name="dataCompetencia" defaultValue={ledger?.dataCompetencia ?? ""} /></label>
        <label>Emissão<input type="date" name="dataEmissao" defaultValue={ledger?.dataEmissao ?? ""} /></label>
        <label>Vencimento<input required type="date" name="vencimentoEm" defaultValue={ledger?.vencimentoEm ?? ""} /></label>
        <label>Moeda<input required name="moeda" maxLength={3} defaultValue={ledger?.moeda ?? "BRL"} /></label>
        <label>Valor original<input required min="0" step="0.01" type="number" name="valorOriginal" defaultValue={ledger?.valorOriginal ?? ""} /></label>
        <label>Desconto<input min="0" step="0.01" type="number" name="desconto" defaultValue={ledger?.desconto ?? 0} /></label>
        <label>Juros<input min="0" step="0.01" type="number" name="juros" defaultValue={ledger?.juros ?? 0} /></label>
        <label>Multa<input min="0" step="0.01" type="number" name="multa" defaultValue={ledger?.multa ?? 0} /></label>
      </div>
      {error ? <p className="finance-form-error" role="alert">{error}</p> : null}
      <footer>
        {ledger && <button type="button" className="finance-danger-action" onClick={() => void archive()} disabled={saving}>Arquivar</button>}
        <button className="finance-primary-action" type="submit" disabled={saving}>{saving ? "Salvando…" : "Salvar lançamento"}</button>
      </footer>
    </form>
  );
}

function SettlementForm({ ledger, onSaved }: { ledger: FinanceLedgerEntry; onSaved: () => void }) {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const value = number(form, "valor");
    if (value <= 0 || value > ledger.valorAberto) {
      setError(`Informe um valor entre 0 e ${formatMoney(ledger.valorAberto, ledger.moeda)}.`);
      return;
    }
    setSaving(true);
    try {
      await registrarLiquidacao(ledger, value, text(form, "data"), text(form, "meio"));
      onSaved();
    } catch (reason: unknown) {
      setError(reason instanceof Error ? reason.message : "Não foi possível registrar a liquidação.");
      setSaving(false);
    }
  }
  return (
    <form className="finance-form" onSubmit={(event) => void submit(event)}>
      <p className="finance-inline-note">Em aberto: {formatMoney(ledger.valorAberto, ledger.moeda)}</p>
      <label>Valor<input autoFocus required type="number" min="0.01" max={ledger.valorAberto} step="0.01" name="valor" defaultValue={ledger.valorAberto} /></label>
      <label>Data<input required type="date" name="data" defaultValue={new Date().toISOString().slice(0, 10)} /></label>
      <label>Meio<input required name="meio" placeholder="Transferência, boleto, PIX…" /></label>
      {error ? <p className="finance-form-error" role="alert">{error}</p> : null}
      <footer><button className="finance-primary-action" type="submit" disabled={saving}>{saving ? "Registrando…" : "Confirmar liquidação"}</button></footer>
    </form>
  );
}

function ChargePreview({
  charge,
  canSend,
  onClose,
  onChanged,
}: {
  charge: FinanceCharge;
  canSend: boolean;
  onClose: () => void;
  onChanged: () => void;
}) {
  const [preview, setPreview] = useState<FinanceChargePreview | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  async function loadPreview() {
    setLoading(true);
    setError("");
    try {
      setPreview(await previsualizarCobranca(charge.id, charge.obraId));
    } catch (reason: unknown) {
      setError(reason instanceof Error ? reason.message : "Não foi possível gerar a pré-visualização.");
    } finally {
      setLoading(false);
    }
  }

  async function send() {
    if (!preview || !window.confirm(`Enviar esta cobrança para ${preview.destinatario}?`)) return;
    setLoading(true);
    try {
      await enviarCobranca(charge.id, charge.obraId);
      onClose();
      onChanged();
    } catch (reason: unknown) {
      setError(reason instanceof Error ? reason.message : "Não foi possível enviar a cobrança.");
      setLoading(false);
    }
  }

  return (
    <FinanceDrawer title="Revisar cobrança" description={`Status: ${charge.status.replaceAll("_", " ")}`} onClose={onClose}>
      {!preview ? (
        <div className="finance-preview-prompt">
          <p>A pré-visualização é gerada no servidor com os dados atuais do fornecedor e do lançamento.</p>
          <button type="button" className="finance-primary-action" disabled={loading || !canSend} onClick={() => void loadPreview()}>
            {loading ? "Gerando…" : "Gerar pré-visualização"}
          </button>
        </div>
      ) : (
        <article className="finance-email-preview">
          <dl>
            <div><dt>Para</dt><dd>{preview.destinatario}</dd></div>
            <div><dt>Responder para</dt><dd>{preview.replyTo || "Remetente configurado"}</dd></div>
            <div><dt>Assunto</dt><dd>{preview.assunto}</dd></div>
          </dl>
          <pre>{preview.corpoTexto}</pre>
          <small>Confirmação {formatDateTime(preview.confirmadaEm)} · hash {preview.hashConteudo.slice(0, 12)}…</small>
          <button type="button" className="finance-primary-action" disabled={loading || !canSend} onClick={() => void send()}>
            {loading ? "Enviando…" : "Enviar agora"}
          </button>
        </article>
      )}
      {charge.erroSanitizado ? <p className="finance-form-error">Última falha: {charge.erroSanitizado}</p> : null}
      {error ? <p className="finance-form-error" role="alert">{error}</p> : null}
    </FinanceDrawer>
  );
}

function text(form: FormData, key: string): string {
  return String(form.get(key) ?? "").trim();
}

function nullableText(form: FormData, key: string): string | null {
  return text(form, key) || null;
}

function number(form: FormData, key: string): number {
  const value = Number(form.get(key) || 0);
  return Number.isFinite(value) ? value : 0;
}
