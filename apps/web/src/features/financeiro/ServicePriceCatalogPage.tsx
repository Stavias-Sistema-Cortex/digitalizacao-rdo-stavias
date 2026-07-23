import { useCallback, useEffect, useMemo, useState } from "react";

import { LOCAL_MUTATION_QUEUED_EVENT } from "../../lib/sync/localMutationCoordinator";
import { SYNC_COMPLETED_EVENT } from "../../lib/sync/syncEvents";
import type { FinancialPermission } from "./financeiro.types";
import { fetchCompleteServiceCatalog } from "./servicePriceApi";
import {
  hydrateServiceCatalog,
  listLocalServiceCatalog,
  queueCancelPrice,
  queueCreatePrice,
  queueCreateService,
  queueSupersedePrice,
  type LocalServiceCatalogRow,
} from "./servicePriceRepository";

interface ServicePriceCatalogPageProps {
  obraId: string;
  permissions: readonly FinancialPermission[];
}

type EditorState =
  | { type: "service" }
  | { type: "price"; serviceId: string }
  | { type: "supersede"; priceId: string; serviceId: string }
  | { type: "cancel"; priceId: string; serviceId: string }
  | null;

function syncLabel(value: string): string {
  if (value === "SYNCED") return "Sincronizado";
  if (value === "SYNCING") return "Sincronizando";
  if (value === "CONFLICT") return "Conflito";
  if (value === "ERROR") return "Falha";
  return "Na fila";
}

function formatMoney(value: string, currency: string): string {
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return `${currency} ${value}`;
  try {
    return new Intl.NumberFormat("pt-BR", {
      style: "currency",
      currency,
      minimumFractionDigits: 2,
      maximumFractionDigits: 4,
    }).format(numeric).replace(/\u00a0/g, " ");
  } catch {
    return `${currency} ${value}`;
  }
}

function readText(form: FormData, key: string): string {
  return String(form.get(key) ?? "").trim();
}

export function ServicePriceCatalogPage({
  obraId,
  permissions,
}: ServicePriceCatalogPageProps) {
  const [rows, setRows] = useState<LocalServiceCatalogRow[]>([]);
  const [query, setQuery] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [editor, setEditor] = useState<EditorState>(null);
  const [saving, setSaving] = useState(false);
  const canAdmin = permissions.includes("FINANCEIRO_ADMINISTRAR");

  const loadLocal = useCallback(async (search = query) => {
    const local = await listLocalServiceCatalog(obraId, search);
    setRows(local);
    return local;
  }, [obraId, query]);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError("");
    let remoteFailure: unknown = null;
    try {
      if (typeof navigator === "undefined" || navigator.onLine) {
        try {
          const remote = await fetchCompleteServiceCatalog(obraId);
          await hydrateServiceCatalog(obraId, remote, {
            replaceCompleteSnapshot: true,
          });
        } catch (reason: unknown) {
          remoteFailure = reason;
        }
      }
      const local = await loadLocal();
      if (remoteFailure && local.length === 0) throw remoteFailure;
      if (remoteFailure) {
        setNotice("Sem conexão com o servidor. Exibindo dados locais preservados.");
      } else {
        setNotice("");
      }
    } catch (reason: unknown) {
      setError(reason instanceof Error
        ? reason.message
        : "Não foi possível abrir o catálogo desta obra.");
    } finally {
      setLoading(false);
    }
  }, [obraId, loadLocal]);

  useEffect(() => {
    let cancelled = false;
    queueMicrotask(() => {
      if (!cancelled) void refresh();
    });
    const requestRefresh = () => { void refresh(); };
    window.addEventListener(SYNC_COMPLETED_EVENT, requestRefresh);
    window.addEventListener(LOCAL_MUTATION_QUEUED_EVENT, requestRefresh);
    window.addEventListener("online", requestRefresh);
    window.addEventListener("offline", requestRefresh);
    return () => {
      cancelled = true;
      window.removeEventListener(SYNC_COMPLETED_EVENT, requestRefresh);
      window.removeEventListener(LOCAL_MUTATION_QUEUED_EVENT, requestRefresh);
      window.removeEventListener("online", requestRefresh);
      window.removeEventListener("offline", requestRefresh);
    };
  }, [refresh]);

  useEffect(() => {
    let cancelled = false;
    void listLocalServiceCatalog(obraId, query)
      .then((local) => { if (!cancelled) setRows(local); })
      .catch((reason: unknown) => {
        if (!cancelled) setError(reason instanceof Error
          ? reason.message
          : "Não foi possível pesquisar o catálogo local.");
      });
    return () => { cancelled = true; };
  }, [obraId, query]);

  const selectedRow = useMemo(() => {
    if (!editor || editor.type === "service") return null;
    return rows.find((row) => row.service.id === editor.serviceId) ?? null;
  }, [editor, rows]);

  async function submit(
    action: () => Promise<unknown>,
    successMessage: string,
  ) {
    setSaving(true);
    setError("");
    try {
      await action();
      setEditor(null);
      setNotice(successMessage);
      await loadLocal();
    } catch (reason: unknown) {
      setError(reason instanceof Error
        ? reason.message
        : "Não foi possível registrar a alteração.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="finance-service-catalog" aria-labelledby="service-catalog-title">
      <header className="finance-service-catalog__header">
        <div>
          <span>Catálogo operacional versionado</span>
          <h2 id="service-catalog-title">Serviços e preços</h2>
          <p>
            A receita usa o serviço executado no RDO e a versão de preço válida
            na data da execução. Alterações ficam na fila quando não há rede.
          </p>
        </div>
        {canAdmin ? (
          <button type="button" onClick={() => setEditor({ type: "service" })}>
            Novo serviço
          </button>
        ) : null}
      </header>

      <div className="finance-service-catalog__toolbar">
        <label>
          <span>Pesquisar na memória local</span>
          <input
            type="search"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Código, serviço ou descrição"
          />
        </label>
        <div className="finance-service-catalog__connection" role="status">
          <strong>{typeof navigator !== "undefined" && !navigator.onLine
            ? "Dados locais"
            : "Sincronização automática"}</strong>
          <span>{rows.length} {rows.length === 1 ? "serviço visível" : "serviços visíveis"}</span>
        </div>
      </div>

      {notice ? <p className="finance-service-catalog__notice" role="status">{notice}</p> : null}
      {error ? <p className="finance-error-state" role="alert">{error}</p> : null}
      {loading && rows.length === 0 ? (
        <p className="finance-loading" role="status">Carregando catálogo preservado…</p>
      ) : null}

      {editor?.type === "service" && canAdmin ? (
        <form
          className="finance-catalog-editor"
          onSubmit={(event) => {
            event.preventDefault();
            const form = new FormData(event.currentTarget);
            void submit(
              () => queueCreateService(obraId, {
                code: readText(form, "code"),
                name: readText(form, "name"),
                description: readText(form, "description"),
              }),
              "Serviço salvo localmente e incluído na sincronização automática.",
            );
          }}
        >
          <header><div><span>Novo registro</span><h3>Criar serviço</h3></div><button type="button" onClick={() => setEditor(null)}>Fechar</button></header>
          <div className="finance-catalog-editor__grid">
            <label>Código do serviço<input name="code" required maxLength={80} autoFocus /></label>
            <label>Nome do serviço<input name="name" required maxLength={160} /></label>
            <label className="is-wide">Descrição<textarea name="description" maxLength={500} rows={2} /></label>
          </div>
          <button type="submit" disabled={saving}>Salvar offline</button>
        </form>
      ) : null}

      {editor?.type === "price" && canAdmin && selectedRow ? (
        <form
          className="finance-catalog-editor"
          onSubmit={(event) => {
            event.preventDefault();
            const form = new FormData(event.currentTarget);
            void submit(
              () => queueCreatePrice(obraId, selectedRow.service.id, {
                unit: readText(form, "unit"),
                currency: readText(form, "currency"),
                unitPrice: readText(form, "unitPrice"),
                validFrom: readText(form, "validFrom"),
                validTo: readText(form, "validTo"),
                source: readText(form, "source"),
              }),
              "Preço salvo localmente e incluído na sincronização automática.",
            );
          }}
        >
          <header><div><span>{selectedRow.service.code}</span><h3>Publicar primeiro preço</h3></div><button type="button" onClick={() => setEditor(null)}>Fechar</button></header>
          <div className="finance-catalog-editor__grid">
            <label>Unidade<input name="unit" required maxLength={30} placeholder="M2, M3, H" /></label>
            <label>Moeda<input name="currency" required maxLength={3} placeholder="BRL" /></label>
            <label>Valor unitário<input name="unitPrice" inputMode="decimal" required /></label>
            <label>Início da vigência<input name="validFrom" type="date" required /></label>
            <label>Fim da vigência<input name="validTo" type="date" /></label>
            <label>Fonte do preço<input name="source" required maxLength={80} placeholder="Contrato, medição ou aditivo" /></label>
          </div>
          <button type="submit" disabled={saving}>Salvar offline</button>
        </form>
      ) : null}

      {editor?.type === "supersede" && canAdmin && selectedRow ? (
        <form
          className="finance-catalog-editor"
          onSubmit={(event) => {
            event.preventDefault();
            const form = new FormData(event.currentTarget);
            void submit(
              () => queueSupersedePrice(obraId, editor.priceId, {
                unitPrice: readText(form, "unitPrice"),
                validFrom: readText(form, "validFrom"),
                validTo: readText(form, "validTo"),
                source: readText(form, "source"),
              }),
              "Nova versão salva localmente e incluída na sincronização automática.",
            );
          }}
        >
          <header><div><span>{selectedRow.service.code}</span><h3>Substituir preço ativo</h3></div><button type="button" onClick={() => setEditor(null)}>Fechar</button></header>
          <div className="finance-catalog-editor__grid">
            <label>Novo valor unitário<input name="unitPrice" inputMode="decimal" required /></label>
            <label>Início da nova vigência<input name="validFrom" type="date" required /></label>
            <label>Fim da vigência<input name="validTo" type="date" /></label>
            <label>Fonte da revisão<input name="source" required maxLength={80} /></label>
          </div>
          <button type="submit" disabled={saving}>Salvar nova versão offline</button>
        </form>
      ) : null}

      {editor?.type === "cancel" && canAdmin && selectedRow ? (
        <form
          className="finance-catalog-editor"
          onSubmit={(event) => {
            event.preventDefault();
            const form = new FormData(event.currentTarget);
            void submit(
              () => queueCancelPrice(obraId, editor.priceId, {
                effectiveAt: readText(form, "effectiveAt"),
                reason: readText(form, "reason"),
              }),
              "Cancelamento salvo localmente e incluído na sincronização automática.",
            );
          }}
        >
          <header><div><span>{selectedRow.service.code}</span><h3>Cancelar versão ativa</h3></div><button type="button" onClick={() => setEditor(null)}>Fechar</button></header>
          <div className="finance-catalog-editor__grid">
            <label>Data efetiva<input name="effectiveAt" type="date" required /></label>
            <label className="is-wide">Motivo<textarea name="reason" required maxLength={500} rows={2} /></label>
          </div>
          <button type="submit" disabled={saving}>Salvar cancelamento offline</button>
        </form>
      ) : null}

      {!loading && rows.length === 0 ? (
        <div className="finance-empty">
          <div className="finance-empty-mark" aria-hidden="true">∅</div>
          <div><h3>Nenhum serviço encontrado</h3><p>O catálogo permanece vazio até um registro real ser criado ou sincronizado.</p></div>
        </div>
      ) : null}

      <div className="finance-service-list">
        {rows.map((row) => (
          <article key={row.service.id} className="finance-service-row">
            <header>
              <div>
                <code>{row.service.code}</code>
                <h3>{row.service.name}</h3>
                {row.service.description ? <p>{row.service.description}</p> : null}
              </div>
              <div className="finance-service-row__actions">
                <span data-sync={row.service.syncStatus}>{syncLabel(row.service.syncStatus)}</span>
                {canAdmin ? (
                  <button type="button" onClick={() => setEditor({ type: "price", serviceId: row.service.id })}>
                    Novo preço para {row.service.name}
                  </button>
                ) : null}
              </div>
            </header>
            {row.priceVersions.length === 0 ? (
              <p className="finance-service-row__empty">Sem preço registrado para esta obra.</p>
            ) : (
              <div className="finance-price-history">
                {row.priceVersions.map((price) => (
                  <div key={price.id} className="finance-price-version">
                    <div><span>Versão {price.version}</span><strong>{formatMoney(price.unitPrice, price.currency)}</strong><small>por {price.unit}</small></div>
                    <div><span>Vigência</span><strong>{price.validFrom}</strong><small>{price.effectiveValidTo ? `até ${price.effectiveValidTo}` : "sem término registrado"}</small></div>
                    <div><span>Estado</span><strong>{price.status}</strong><small>{syncLabel(price.syncStatus)}</small></div>
                    {canAdmin && price.status === "ACTIVE" && price.syncStatus === "SYNCED" ? (
                      <div className="finance-price-version__actions">
                        <button type="button" onClick={() => setEditor({ type: "supersede", priceId: price.id, serviceId: row.service.id })}>Substituir</button>
                        <button type="button" onClick={() => setEditor({ type: "cancel", priceId: price.id, serviceId: row.service.id })}>Cancelar</button>
                      </div>
                    ) : null}
                  </div>
                ))}
              </div>
            )}
          </article>
        ))}
      </div>
    </section>
  );
}
