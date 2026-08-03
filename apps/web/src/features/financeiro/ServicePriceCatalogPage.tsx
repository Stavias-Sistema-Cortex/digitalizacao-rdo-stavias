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
import {
  normalizarUnidade,
  reconhecerServico,
  sugerirCodigoDoServico,
} from "./servicoCatalogoEntrada";

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

function hojeIso(): string {
  return new Date().toISOString().slice(0, 10);
}

interface NovoServicoState {
  code: string;
  name: string;
  description: string;
  unit: string;
  unitPrice: string;
  contractedQuantity: string;
  validFrom: string;
  source: string;
}

const NOVO_SERVICO_VAZIO: NovoServicoState = {
  code: "",
  name: "",
  description: "",
  unit: "",
  unitPrice: "",
  contractedQuantity: "",
  validFrom: "",
  source: "CONTRATO_MEDIDO",
};

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
  const [novoServico, setNovoServico] =
    useState<NovoServicoState>(NOVO_SERVICO_VAZIO);
  // O código só é sugerido enquanto ninguém o escreveu à mão: a sugestão
  // ajuda quem não tem a convenção na cabeça e nunca sobrescreve uma decisão.
  const [codigoEditadoAMao, setCodigoEditadoAMao] = useState(false);
  const [comPrecoInicial, setComPrecoInicial] = useState(false);
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

  const catalogoConhecido = useMemo(
    () => rows.map((row) => ({
      id: row.service.id,
      code: row.service.code,
      name: row.service.name,
    })),
    [rows],
  );
  /** Serviço que já existe com o nome/código que está sendo digitado. */
  const servicoJaExistente = useMemo(
    () => reconhecerServico(catalogoConhecido, novoServico.name) ??
      reconhecerServico(catalogoConhecido, novoServico.code),
    [catalogoConhecido, novoServico.code, novoServico.name],
  );

  function abrirNovoServico() {
    setNovoServico({ ...NOVO_SERVICO_VAZIO, validFrom: hojeIso() });
    setCodigoEditadoAMao(false);
    setComPrecoInicial(false);
    setEditor({ type: "service" });
  }

  /**
   * Cria o serviço e, quando o custo foi informado, já publica o primeiro
   * preço na mesma ação.
   *
   * O preço sai como uma segunda mutação, que a fila já sabe encadear atrás da
   * criação do serviço. Se o preço falhar na validação, o serviço permanece
   * criado e a tela leva direto ao editor de preço, em vez de perder o que foi
   * digitado.
   */
  async function criarServico() {
    const servico = await queueCreateService(obraId, {
      code: novoServico.code,
      name: novoServico.name,
      description: novoServico.description,
    });
    if (!comPrecoInicial) return;
    await queueCreatePrice(obraId, servico.entityId, {
      unit: normalizarUnidade(novoServico.unit),
      currency: "BRL",
      unitPrice: novoServico.unitPrice,
      contractedQuantity: novoServico.contractedQuantity,
      validFrom: novoServico.validFrom,
      validTo: "",
      source: novoServico.source,
    });
  }

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
          <button type="button" onClick={abrirNovoServico}>
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
            void submit(
              criarServico,
              comPrecoInicial
                ? "Serviço e primeiro preço salvos localmente e incluídos na sincronização automática."
                : "Serviço salvo localmente e incluído na sincronização automática.",
            );
          }}
        >
          <header><div><span>Novo registro</span><h3>Criar serviço</h3></div><button type="button" onClick={() => setEditor(null)}>Fechar</button></header>
          <div className="finance-catalog-editor__grid">
            <label>
              Nome do serviço
              <input
                name="name"
                aria-label="Nome do serviço"
                required
                maxLength={160}
                autoFocus
                value={novoServico.name}
                placeholder="Fresagem"
                onChange={(event) => {
                  const name = event.target.value;
                  setNovoServico((atual) => ({
                    ...atual,
                    name,
                    code: codigoEditadoAMao
                      ? atual.code
                      : sugerirCodigoDoServico(name, catalogoConhecido),
                  }));
                }}
              />
              <small>
                Escreva o nome do serviço. O código é proposto a partir dele.
              </small>
            </label>
            <label>
              Código do serviço
              <input
                name="code"
                required
                maxLength={80}
                value={novoServico.code}
                onChange={(event) => {
                  setCodigoEditadoAMao(true);
                  setNovoServico((atual) => ({
                    ...atual,
                    code: event.target.value,
                  }));
                }}
              />
            </label>
            <label className="is-wide">
              Descrição
              <textarea
                name="description"
                maxLength={500}
                rows={2}
                value={novoServico.description}
                onChange={(event) =>
                  setNovoServico((atual) => ({
                    ...atual,
                    description: event.target.value,
                  }))}
              />
            </label>
          </div>

          {servicoJaExistente ? (
            <p className="finance-service-catalog__notice" role="status">
              <strong>{servicoJaExistente.code}</strong> — “
              {servicoJaExistente.name}” já está no catálogo desta obra.
              Publique um preço para ele em vez de criar um segundo registro
              com o mesmo nome.{" "}
              <button
                type="button"
                onClick={() => setEditor({
                  type: "price",
                  serviceId: servicoJaExistente.id,
                })}
              >
                Abrir preço deste serviço
              </button>
            </p>
          ) : null}

          <label className="finance-catalog-editor__toggle">
            <input
              type="checkbox"
              checked={comPrecoInicial}
              onChange={(event) => setComPrecoInicial(event.target.checked)}
            />
            <span>Informar o custo agora</span>
          </label>

          {comPrecoInicial ? (
            <div className="finance-catalog-editor__grid">
              <label>
                Unidade
                <input
                  name="unit"
                  aria-label="Unidade"
                  required
                  maxLength={30}
                  value={novoServico.unit}
                  placeholder="m2, m3, ton, h"
                  onChange={(event) =>
                    setNovoServico((atual) => ({
                      ...atual,
                      unit: event.target.value,
                    }))}
                  onBlur={(event) =>
                    setNovoServico((atual) => ({
                      ...atual,
                      unit: normalizarUnidade(event.target.value),
                    }))}
                />
                <small>
                  {novoServico.unit &&
                    normalizarUnidade(novoServico.unit) !== novoServico.unit
                    ? `Será gravado como ${normalizarUnidade(novoServico.unit)}.`
                    : "Escreva como preferir: m2 vira M², ton vira T."}
                </small>
              </label>
              <label>
                Valor unitário
                <input
                  name="unitPrice"
                  inputMode="decimal"
                  required
                  value={novoServico.unitPrice}
                  onChange={(event) =>
                    setNovoServico((atual) => ({
                      ...atual,
                      unitPrice: event.target.value,
                    }))}
                />
              </label>
              <label>
                Quantidade contratada
                <input
                  name="contractedQuantity"
                  inputMode="decimal"
                  required
                  value={novoServico.contractedQuantity}
                  onChange={(event) =>
                    setNovoServico((atual) => ({
                      ...atual,
                      contractedQuantity: event.target.value,
                    }))}
                />
              </label>
              <label>
                Início da vigência
                <input
                  name="validFrom"
                  aria-label="Início da vigência"
                  type="date"
                  required
                  value={novoServico.validFrom}
                  onChange={(event) =>
                    setNovoServico((atual) => ({
                      ...atual,
                      validFrom: event.target.value,
                    }))}
                />
                <small>
                  A receita só reconhece execução a partir desta data.
                </small>
              </label>
              <label>
                Fonte do preço
                <input
                  name="source"
                  required
                  maxLength={80}
                  value={novoServico.source}
                  onChange={(event) =>
                    setNovoServico((atual) => ({
                      ...atual,
                      source: event.target.value,
                    }))}
                />
                <small>Use letras, números e apenas . _ : -</small>
              </label>
            </div>
          ) : null}

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
                unit: normalizarUnidade(readText(form, "unit")),
                currency: readText(form, "currency"),
                unitPrice: readText(form, "unitPrice"),
                contractedQuantity: readText(form, "contractedQuantity"),
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
            <label>
              Unidade
              <input
                name="unit"
                aria-label="Unidade"
                required
                maxLength={30}
                placeholder="m2, m3, ton, h"
              />
              <small>Escreva como preferir: m2 vira M², ton vira T.</small>
            </label>
            <label>Moeda<input name="currency" required value="BRL" readOnly /></label>
            <label>Valor unitário<input name="unitPrice" inputMode="decimal" required /></label>
            <label>Quantidade contratada<input name="contractedQuantity" inputMode="decimal" required /></label>
            <label>Início da vigência<input name="validFrom" type="date" required /></label>
            <label>Fim da vigência<input name="validTo" type="date" /></label>
            <label>
              Fonte do preço
              <input name="source" required maxLength={80} placeholder="CONTRATO_MEDIDO" />
              <small>Use letras, números e apenas . _ : -</small>
            </label>
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
                contractedQuantity: readText(form, "contractedQuantity"),
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
            <label>Nova quantidade contratada<input name="contractedQuantity" inputMode="decimal" required /></label>
            <label>Início da nova vigência<input name="validFrom" type="date" required /></label>
            <label>Fim da vigência<input name="validTo" type="date" /></label>
            <label>
              Fonte da revisão
              <input name="source" required maxLength={80} placeholder="ADITIVO_01" />
              <small>Use letras, números e apenas . _ : -</small>
            </label>
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
                    <div>
                      <span>Versão {price.version}</span>
                      <strong>{formatMoney(price.unitPrice, price.currency)}</strong>
                      <small>
                        por {price.unit}
                        {price.contractedQuantity
                          ? ` · ${price.contractedQuantity} ${price.unit} contratados`
                          : " · quantidade contratada histórica indisponível"}
                      </small>
                    </div>
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
