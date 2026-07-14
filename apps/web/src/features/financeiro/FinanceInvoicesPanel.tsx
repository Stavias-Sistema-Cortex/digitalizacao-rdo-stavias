import { useEffect, useState } from "react";

import {
  arquivarNotaFiscal,
  buscarDocumentosNota,
  salvarNotaFiscal,
  urlConteudoDocumento,
} from "./financeiroApi";
import type {
  FinanceCategory,
  FinanceCostCenter,
  FinanceInvoice,
  FinanceInvoiceDocument,
  FinanceInvoiceDraft,
  FinanceStatusDefinition,
  FinanceSupplier,
  FinancialPermission,
} from "./financeiro.types";
import {
  formatDate,
  formatMoney,
  hasFinancialPermission,
  statusTone,
} from "./financeiroView";
import { FinanceDrawer } from "./FinanceDrawer";

interface FinanceInvoicesPanelProps {
  obraId: string;
  invoices: FinanceInvoice[];
  suppliers: FinanceSupplier[];
  costCenters: FinanceCostCenter[];
  categories: FinanceCategory[];
  statuses: FinanceStatusDefinition[];
  permissions: FinancialPermission[];
  onChanged: () => void;
}

type InvoiceDrawer =
  | { type: "form"; invoice: FinanceInvoice | null }
  | { type: "documents"; invoice: FinanceInvoice }
  | null;

export function FinanceInvoicesPanel({
  obraId,
  invoices,
  suppliers,
  costCenters,
  categories,
  statuses,
  permissions,
  onChanged,
}: FinanceInvoicesPanelProps) {
  const [drawer, setDrawer] = useState<InvoiceDrawer>(null);
  const canOperate = hasFinancialPermission(permissions, "FINANCEIRO_OPERAR");
  const invoiceStatuses = statuses.filter((status) =>
    status.agregadoTipo === "NOTA_FISCAL" && status.ativo,
  );

  return (
    <section className="finance-workspace-section">
      <div className="finance-section-heading">
        <div>
          <p className="finance-kicker">Documentos fiscais</p>
          <h2>Notas fiscais</h2>
          <p>{invoices.length} notas no filtro atual</p>
        </div>
        {canOperate && (
          <button
            type="button"
            className="finance-primary-action"
            onClick={() => setDrawer({ type: "form", invoice: null })}
          >
            Nova nota fiscal
          </button>
        )}
      </div>

      {invoices.length === 0 ? (
        <div className="finance-row-empty">
          Nenhuma nota fiscal encontrada. Os números e valores só aparecem depois do cadastro real.
        </div>
      ) : (
        <div className="finance-table-wrap">
          <table className="finance-table">
            <thead>
              <tr>
                <th>Número</th>
                <th>Fornecedor</th>
                <th>Emissão</th>
                <th>Vencimento</th>
                <th>Status</th>
                <th className="is-number">Valor líquido</th>
                <th><span className="sr-only">Ações</span></th>
              </tr>
            </thead>
            <tbody>
              {invoices.map((invoice) => (
                <tr key={invoice.id}>
                  <td data-label="Número"><strong>{invoice.numero}</strong><span>{invoice.serie}</span></td>
                  <td data-label="Fornecedor">{invoice.fornecedorNome || invoice.fornecedorId}</td>
                  <td data-label="Emissão">{formatDate(invoice.dataEmissao)}</td>
                  <td data-label="Vencimento">{formatDate(invoice.vencimentoEm)}</td>
                  <td data-label="Status">
                    <span className={`finance-pill is-${statusTone(invoice.statusCodigo)}`}>
                      {invoice.statusNome || invoice.statusCodigo}
                    </span>
                  </td>
                  <td data-label="Valor líquido" className="is-number">
                    {formatMoney(invoice.valorLiquido, invoice.moeda)}
                  </td>
                  <td className="finance-row-actions">
                    <button
                      type="button"
                      onClick={() => setDrawer({ type: "documents", invoice })}
                    >
                      Documentos
                    </button>
                    {canOperate && (
                      <button
                        type="button"
                        onClick={() => setDrawer({ type: "form", invoice })}
                      >
                        Editar
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {drawer?.type === "form" && (
        <FinanceDrawer
          title={drawer.invoice ? "Editar nota fiscal" : "Registrar nota fiscal"}
          description="Identificação, vencimento e valores serão validados pelo servidor."
          onClose={() => setDrawer(null)}
          wide
        >
          <InvoiceForm
            obraId={obraId}
            invoice={drawer.invoice}
            suppliers={suppliers}
            costCenters={costCenters}
            categories={categories}
            statuses={invoiceStatuses}
            onSaved={() => {
              setDrawer(null);
              onChanged();
            }}
          />
        </FinanceDrawer>
      )}

      {drawer?.type === "documents" && (
        <InvoiceDocuments
          invoice={drawer.invoice}
          canArchive={canOperate}
          onClose={() => setDrawer(null)}
          onArchived={() => {
            setDrawer(null);
            onChanged();
          }}
        />
      )}
    </section>
  );
}

function InvoiceForm({
  obraId,
  invoice,
  suppliers,
  costCenters,
  categories,
  statuses,
  onSaved,
}: {
  obraId: string;
  invoice: FinanceInvoice | null;
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
    const bruto = number(form, "valorBruto");
    const desconto = number(form, "desconto");
    const acrescimo = number(form, "acrescimo");
    const retencoes = number(form, "retencoes");
    const draft: FinanceInvoiceDraft = {
      obraId,
      fornecedorId: text(form, "fornecedorId"),
      centroCustoId: nullableText(form, "centroCustoId"),
      categoriaId: nullableText(form, "categoriaId"),
      responsavelId: invoice?.responsavelId ?? null,
      statusId: text(form, "statusId"),
      numero: text(form, "numero"),
      serie: text(form, "serie"),
      chaveAcesso: nullableText(form, "chaveAcesso"),
      tipoDocumento: text(form, "tipoDocumento") || "NFE",
      dataEmissao: text(form, "dataEmissao"),
      dataCompetencia: nullableText(form, "dataCompetencia"),
      dataRecebimento: nullableText(form, "dataRecebimento"),
      vencimentoEm: text(form, "vencimentoEm"),
      moeda: text(form, "moeda").toUpperCase(),
      valorBruto: bruto,
      desconto,
      acrescimo,
      retencoes,
      valorLiquido: bruto - desconto + acrescimo - retencoes,
      observacoes: nullableText(form, "observacoes"),
    };
    if (!draft.fornecedorId || !draft.statusId || !draft.numero || !draft.serie ||
      !draft.dataEmissao || !draft.vencimentoEm || !draft.moeda || draft.valorLiquido < 0) {
      setError("Preencha a identificação, o fornecedor, o status, as datas, a moeda e valores válidos.");
      return;
    }
    setSaving(true);
    setError("");
    try {
      await salvarNotaFiscal(draft, invoice ?? undefined);
      onSaved();
    } catch (reason: unknown) {
      setError(reason instanceof Error ? reason.message : "Não foi possível salvar a nota fiscal.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <form className="finance-form" onSubmit={(event) => void submit(event)}>
      <div className="finance-form-grid">
        <label>Número<input required name="numero" defaultValue={invoice?.numero ?? ""} /></label>
        <label>Série<input required name="serie" defaultValue={invoice?.serie ?? ""} /></label>
        <label>Tipo<select name="tipoDocumento" defaultValue={invoice?.tipoDocumento ?? "NFE"}>
          <option value="NFE">NF-e</option><option value="NFS">NFS-e</option><option value="RECIBO">Recibo</option>
        </select></label>
        <label>Status<select required name="statusId" defaultValue={invoice?.statusId ?? statuses[0]?.id ?? ""}>
          <option value="">Selecione</option>{statuses.map((status) => <option key={status.id} value={status.id}>{status.nome}</option>)}
        </select></label>
        <label className="is-wide">Fornecedor<select required name="fornecedorId" defaultValue={invoice?.fornecedorId ?? ""}>
          <option value="">Selecione</option>{suppliers.map((supplier) => <option key={supplier.id} value={supplier.id}>{supplier.nomeFantasia || supplier.razaoSocial}</option>)}
        </select></label>
        <label>Centro de custo<select name="centroCustoId" defaultValue={invoice?.centroCustoId ?? ""}>
          <option value="">Não informado</option>{costCenters.map((center) => <option key={center.id} value={center.id}>{center.codigo} · {center.nome}</option>)}
        </select></label>
        <label>Categoria<select name="categoriaId" defaultValue={invoice?.categoriaId ?? ""}>
          <option value="">Não informada</option>{categories.map((category) => <option key={category.id} value={category.id}>{category.nome}</option>)}
        </select></label>
        <label>Emissão<input required type="date" name="dataEmissao" defaultValue={invoice?.dataEmissao ?? ""} /></label>
        <label>Competência<input type="date" name="dataCompetencia" defaultValue={invoice?.dataCompetencia ?? ""} /></label>
        <label>Recebimento<input type="date" name="dataRecebimento" defaultValue={invoice?.dataRecebimento ?? ""} /></label>
        <label>Vencimento<input required type="date" name="vencimentoEm" defaultValue={invoice?.vencimentoEm ?? ""} /></label>
        <label>Moeda<input required name="moeda" maxLength={3} defaultValue={invoice?.moeda ?? "BRL"} /></label>
        <label>Valor bruto<input required min="0" step="0.01" type="number" name="valorBruto" defaultValue={invoice?.valorBruto ?? ""} /></label>
        <label>Desconto<input min="0" step="0.01" type="number" name="desconto" defaultValue={invoice?.desconto ?? 0} /></label>
        <label>Acréscimo<input min="0" step="0.01" type="number" name="acrescimo" defaultValue={invoice?.acrescimo ?? 0} /></label>
        <label>Retenções<input min="0" step="0.01" type="number" name="retencoes" defaultValue={invoice?.retencoes ?? 0} /></label>
        <label className="is-wide">Chave de acesso<input name="chaveAcesso" defaultValue={invoice?.chaveAcesso ?? ""} /></label>
        <label className="is-wide">Observações<textarea name="observacoes" rows={3} defaultValue={invoice?.observacoes ?? ""} /></label>
      </div>
      {error ? <p className="finance-form-error" role="alert">{error}</p> : null}
      <footer><button className="finance-primary-action" disabled={saving} type="submit">{saving ? "Salvando…" : "Salvar nota fiscal"}</button></footer>
    </form>
  );
}

function InvoiceDocuments({
  invoice,
  canArchive,
  onClose,
  onArchived,
}: {
  invoice: FinanceInvoice;
  canArchive: boolean;
  onClose: () => void;
  onArchived: () => void;
}) {
  const [documents, setDocuments] = useState<FinanceInvoiceDocument[]>([]);
  const [selected, setSelected] = useState<FinanceInvoiceDocument | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    buscarDocumentosNota(invoice.id, invoice.obraId)
      .then((rows) => {
        if (!cancelled) {
          setDocuments(rows);
          setSelected(rows.find((row) => row.principal) ?? rows[0] ?? null);
        }
      })
      .catch((reason: unknown) => {
        if (!cancelled) setError(reason instanceof Error ? reason.message : "Documentos indisponíveis.");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [invoice]);

  async function archive() {
    if (!window.confirm("Arquivar esta nota fiscal? Os documentos e o histórico serão preservados.")) return;
    try {
      await arquivarNotaFiscal(invoice);
      onArchived();
    } catch (reason: unknown) {
      setError(reason instanceof Error ? reason.message : "Não foi possível arquivar a nota.");
    }
  }

  return (
    <FinanceDrawer title={`Nota fiscal ${invoice.numero}`} description={invoice.fornecedorNome || undefined} onClose={onClose} wide>
      <dl className="finance-detail-list is-horizontal">
        <div><dt>Valor líquido</dt><dd>{formatMoney(invoice.valorLiquido, invoice.moeda)}</dd></div>
        <div><dt>Vencimento</dt><dd>{formatDate(invoice.vencimentoEm)}</dd></div>
        <div><dt>OCR</dt><dd>{invoice.ocrStatus === "NAO_CONFIGURADO" ? "Não configurado" : invoice.ocrStatus}</dd></div>
      </dl>
      <section className="finance-document-viewer">
        <nav aria-label="Documentos da nota">
          {documents.map((document) => (
            <button key={document.id} type="button" className={selected?.id === document.id ? "is-active" : ""} onClick={() => setSelected(document)}>
              <strong>{document.nomeOriginal}</strong>
              <span>{document.tipoDocumento} · {Math.ceil(document.tamanhoBytes / 1024)} KB</span>
            </button>
          ))}
        </nav>
        <div>
          {loading ? <p>Carregando documentos…</p> : selected ? (
            <>
              <object
                data={urlConteudoDocumento(invoice.id, selected.id, invoice.obraId)}
                type={selected.mediaType}
                aria-label={`Pré-visualização de ${selected.nomeOriginal}`}
              >
                <a href={urlConteudoDocumento(invoice.id, selected.id, invoice.obraId)}>
                  Abrir {selected.nomeOriginal}
                </a>
              </object>
              <a className="finance-secondary-action" href={urlConteudoDocumento(invoice.id, selected.id, invoice.obraId)}>
                Abrir documento
              </a>
            </>
          ) : <p>Nenhum documento vinculado a esta nota fiscal.</p>}
        </div>
      </section>
      {error ? <p className="finance-form-error" role="alert">{error}</p> : null}
      {canArchive && <button type="button" className="finance-danger-action" onClick={() => void archive()}>Arquivar nota fiscal</button>}
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
