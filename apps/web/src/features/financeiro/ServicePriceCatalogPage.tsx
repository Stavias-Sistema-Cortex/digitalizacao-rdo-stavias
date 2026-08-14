import {
  useCallback,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
} from "react";

import { LOCAL_MUTATION_QUEUED_EVENT } from "../../lib/sync/localMutationCoordinator";
import { SYNC_COMPLETED_EVENT } from "../../lib/sync/syncEvents";
import { dataHojeEmBrasilia } from "../../lib/tempo/fusoBrasilia";
import type { FinancialPermission } from "./financeiro.types";
import { fetchCompleteServiceCatalog } from "./servicePriceApi";
import {
  hydrateServiceCatalog,
  listLocalServiceCatalog,
  queueCancelPrice,
  queueCreatePrice,
  queueCreateService,
  apagarServicoNuncaAceito,
  queueExcluirServico,
  queueRestaurarServico,
  queueSupersedePrice,
  queueUpdatePrice,
  queueUpdateService,
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
  | { type: "editService"; serviceId: string }
  | { type: "price"; serviceId: string }
  | { type: "editPrice"; priceId: string; serviceId: string }
  | { type: "supersede"; priceId: string; serviceId: string }
  | { type: "cancel"; priceId: string; serviceId: string }
  | null;

/** Um serviço fora de circulação: existe, mas não se lança nele. */
function excluido(service: { status: string }): boolean {
  return service.status === "EXCLUIDO";
}

/*
 * Traço só, sem preenchimento: a lixeira precisa ler como ação e não como
 * ilustração, e herdar a cor de quem a contém é o que a faz mudar de tom
 * junto com o botão em foco ou desabilitado.
 */
const LIXEIRA_ICONE = (
  <svg viewBox="0 0 16 16" width="15" height="15" fill="none"
    stroke="currentColor" strokeWidth="1.4" strokeLinecap="round"
    aria-hidden="true">
    <path d="M2.8 4.3h10.4M6.4 4.3V3.1a.8.8 0 0 1 .8-.8h1.6a.8.8 0 0 1 .8.8v1.2" />
    <path d="M4.2 4.3l.6 8.3a1 1 0 0 0 1 .9h4.4a1 1 0 0 0 1-.9l.6-8.3" />
    <path d="M6.7 6.9v3.9M9.3 6.9v3.9" />
  </svg>
);

const RESTAURAR_ICONE = (
  <svg viewBox="0 0 16 16" width="15" height="15" fill="none"
    stroke="currentColor" strokeWidth="1.4" strokeLinecap="round"
    strokeLinejoin="round" aria-hidden="true">
    <path d="M3 8a5 5 0 1 1 1.6 3.7" />
    <path d="M2.6 4.6v3h3" />
  </svg>
);

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

/**
 * As unidades que o contrato usa, oferecidas já no símbolo correto.
 *
 * <p>Elas continuam digitáveis à mão — a lista é conveniência, não restrição —
 * mas M² e M³ deixam de depender de alguém acertar o expoente no teclado do
 * celular.
 */
const UNIDADES_SUGERIDAS = [
  "M", "M²", "M³", "KM", "T", "KG", "L", "H", "UN", "VB",
] as const;

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
  const [confirmandoExclusao, setConfirmandoExclusao] =
    useState<string | null>(null);
  const [editor, setEditor] = useState<EditorState>(null);
  const [saving, setSaving] = useState(false);
  const [novoServico, setNovoServico] =
    useState<NovoServicoState>(NOVO_SERVICO_VAZIO);
  // O código só é sugerido enquanto ninguém o escreveu à mão: a sugestão
  // ajuda quem não tem a convenção na cabeça e nunca sobrescreve uma decisão.
  const [codigoEditadoAMao, setCodigoEditadoAMao] = useState(false);
  const [comPrecoInicial, setComPrecoInicial] = useState(false);
  // Excluído é excluído: sai da lista. Quem precisar trazer de volta pede para
  // ver, e aí ele reaparece com o botão de restaurar.
  const [mostrarExcluidos, setMostrarExcluidos] = useState(false);
  const unidadesId = useId();
  const editorRef = useRef<HTMLDivElement | null>(null);
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

  /*
   * O que a lista mostra. Um serviço excluído sai de vista — foi o que se
   * pediu ao clicar na lixeira —, e continua existindo para o histórico que os
   * RDOs citam. Quem precisar restaurá-lo pede para ver os excluídos.
   */
  const visiveis = useMemo(
    () => mostrarExcluidos ? rows : rows.filter((row) => !excluido(row.service)),
    [rows, mostrarExcluidos],
  );
  const quantosExcluidos = useMemo(
    () => rows.filter((row) => excluido(row.service)).length,
    [rows],
  );

  /*
   * Os formulários abrem acima da lista. Com meia dúzia de serviços, clicar em
   * "Editar" no terceiro deles abria um formulário fora da tela, e o botão
   * passava por morto — nada acontecia onde a pessoa estava olhando.
   */
  useEffect(() => {
    if (!editor) return;
    // Opcional porque o DOM dos testes não implementa rolagem: sem rolar, o
    // formulário continua lá e correto — só não vem ao encontro dos olhos.
    editorRef.current?.scrollIntoView?.({ block: "nearest" });
  }, [editor]);

  /** A versão de preço que o editor aberto está corrigindo. */
  const selectedPrice = useMemo(() => {
    if (!editor || !("priceId" in editor) || !selectedRow) return null;
    return selectedRow.priceVersions.find(
      (price) => price.id === editor.priceId,
    ) ?? null;
  }, [editor, selectedRow]);

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
    setNovoServico({
      ...NOVO_SERVICO_VAZIO,
      validFrom: dataHojeEmBrasilia(),
    });
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

  /*
   * A lixeira tira o serviço de circulação; o histórico fica. As versões de
   * preço e o RDO que já o executou continuam onde estavam — o que muda é que
   * ele deixa de ser oferecido para lançamento novo.
   */
  async function alternarExclusao(serviceId: string, excluir: boolean) {
    setSaving(true);
    setError("");
    try {
      if (excluir) {
        /*
         * O serviço cuja criação o servidor recusou sai do aparelho de vez.
         * Enfileirar uma exclusão para o que não existe do outro lado é um
         * beco: a transição volta recusada, o serviço fica pendente, e ficar
         * pendente é justamente o que faz a reconciliação preservá-lo. Cada
         * clique na lixeira aprofundava o buraco.
         */
        if (!(await apagarServicoNuncaAceito(serviceId))) {
          await queueExcluirServico(obraId, serviceId);
        }
      } else {
        await queueRestaurarServico(obraId, serviceId);
      }
      setConfirmandoExclusao(null);
      await loadLocal();
    } catch (caught: unknown) {
      setError(
        caught instanceof Error
          ? caught.message
          : "Não foi possível alterar o serviço.",
      );
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="finance-service-catalog" aria-labelledby="service-catalog-title">
      <datalist id={unidadesId}>
        {UNIDADES_SUGERIDAS.map((unidade) => (
          <option key={unidade} value={unidade} />
        ))}
      </datalist>
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
          <button
            type="button"
            className="finance-service-catalog__novo"
            onClick={abrirNovoServico}
          >
            <span aria-hidden="true">+</span>
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
          <span>{visiveis.length} {visiveis.length === 1 ? "serviço visível" : "serviços visíveis"}</span>
          {quantosExcluidos > 0 ? (
            <button
              type="button"
              className="finance-service-catalog__excluidos"
              onClick={() => setMostrarExcluidos((atual) => !atual)}
            >
              {mostrarExcluidos
                ? "Ocultar excluídos"
                : `Ver ${quantosExcluidos} ${
                    quantosExcluidos === 1 ? "excluído" : "excluídos"
                  }`}
            </button>
          ) : null}
        </div>
      </div>

      {notice ? <p className="finance-service-catalog__notice" role="status">{notice}</p> : null}
      {error ? <p className="finance-error-state" role="alert">{error}</p> : null}
      {loading && rows.length === 0 ? (
        <p className="finance-loading" role="status">Carregando catálogo preservado…</p>
      ) : null}

      <div ref={editorRef} className="finance-catalog-editors">
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
                  list={unidadesId}
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
                  aria-label="Quantidade contratada"
                  inputMode="decimal"
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

      {/* Corrigir o cadastro não cria registro novo: o identificador continua o
          mesmo, e é ele que os RDOs, os preços e as medições já citam. Antes
          disso, consertar um nome errado só era possível excluindo e
          cadastrando de novo — que troca justamente esse identificador. */}
      {editor?.type === "editService" && canAdmin && selectedRow ? (
        <form
          className="finance-catalog-editor"
          onSubmit={(event) => {
            event.preventDefault();
            const form = new FormData(event.currentTarget);
            void submit(
              () => queueUpdateService(obraId, selectedRow.service.id, {
                code: readText(form, "code"),
                name: readText(form, "name"),
                description: readText(form, "description"),
              }),
              "Correção salva localmente e incluída na sincronização automática.",
            );
          }}
        >
          <header>
            <div>
              <span>{selectedRow.service.code}</span>
              <h3>Editar serviço</h3>
            </div>
            <button type="button" onClick={() => setEditor(null)}>Fechar</button>
          </header>
          <p className="finance-catalog-editor__aviso" role="status">
            O serviço continua sendo o mesmo: os preços já publicados e os RDOs
            que o executaram seguem apontando para ele.
          </p>
          <div className="finance-catalog-editor__grid">
            <label>
              Nome do serviço
              <input
                name="name"
                aria-label="Nome do serviço"
                required
                maxLength={160}
                autoFocus
                defaultValue={selectedRow.service.name}
              />
            </label>
            <label>
              Código do serviço
              <input
                name="code"
                required
                maxLength={80}
                defaultValue={selectedRow.service.code}
              />
            </label>
            <label className="is-wide">
              Descrição
              <textarea
                name="description"
                maxLength={500}
                rows={2}
                defaultValue={selectedRow.service.description ?? ""}
              />
            </label>
          </div>
          <button type="submit" disabled={saving}>Salvar offline</button>
        </form>
      ) : null}

      {/* Corrigir e substituir respondem a perguntas diferentes. Substituir é o
          aditivo que mudou o valor a partir de uma data, e guarda as duas
          versões. Corrigir é o zero a mais digitado ontem: guardar as duas
          inventaria uma revisão de contrato que nunca houve, e a receita
          passaria a medir dois períodos por causa de um erro de digitação. */}
      {editor?.type === "editPrice" && canAdmin && selectedRow && selectedPrice ? (
        <form
          className="finance-catalog-editor"
          onSubmit={(event) => {
            event.preventDefault();
            const form = new FormData(event.currentTarget);
            void submit(
              () => queueUpdatePrice(obraId, selectedPrice.id, {
                unit: normalizarUnidade(readText(form, "unit")),
                unitPrice: readText(form, "unitPrice"),
                contractedQuantity: readText(form, "contractedQuantity"),
                validFrom: readText(form, "validFrom"),
                validTo: readText(form, "validTo"),
                source: readText(form, "source"),
              }),
              "Correção salva localmente e incluída na sincronização automática.",
            );
          }}
        >
          <header>
            <div>
              <span>{selectedRow.service.code}</span>
              <h3>Corrigir preço · versão {selectedPrice.version}</h3>
            </div>
            <button type="button" onClick={() => setEditor(null)}>Fechar</button>
          </header>
          <p className="finance-catalog-editor__aviso" role="status">
            A correção reescreve esta versão, sem criar outra. Ela só é aceita
            enquanto nenhuma execução usou este preço — depois disso, o caminho é{" "}
            <strong>Substituir</strong>. Trocar a unidade renumera a versão, que
            é contada por unidade; a moeda continua sendo BRL.
          </p>
          <div className="finance-catalog-editor__grid">
            <label>
              Unidade
              <input
                name="unit"
                aria-label="Unidade"
                required
                maxLength={30}
                list={unidadesId}
                defaultValue={selectedPrice.unit}
              />
              <small>
                Corrija aqui o que entrou como M antes de o cadastro aceitar
                M² e M³.
              </small>
            </label>
            <label>
              Valor unitário
              <input
                name="unitPrice"
                aria-label="Valor unitário"
                inputMode="decimal"
                required
                defaultValue={selectedPrice.unitPrice}
              />
            </label>
            <label>
              Quantidade contratada
              <input
                name="contractedQuantity"
                aria-label="Quantidade contratada"
                inputMode="decimal"
                defaultValue={selectedPrice.contractedQuantity ?? ""}
              />
              <small>Opcional — o que o contrato prevê, quando se sabe.</small>
            </label>
            <label>
              Início da vigência
              <input
                name="validFrom"
                aria-label="Início da vigência"
                type="date"
                required
                defaultValue={selectedPrice.validFrom}
              />
            </label>
            <label>
              Fim da vigência
              <input
                name="validTo"
                type="date"
                defaultValue={selectedPrice.validTo ?? ""}
              />
            </label>
            <label>
              Fonte do preço
              <input
                name="source"
                required
                maxLength={80}
                defaultValue={selectedPrice.source ?? "CONTRATO_MEDIDO"}
              />
              <small>Use letras, números e apenas . _ : -</small>
            </label>
          </div>
          <button type="submit" disabled={saving}>Salvar correção offline</button>
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
          {/* O título dizia "Publicar primeiro preço" mesmo quando o serviço já
              tinha preço publicado. Quem acabara de cadastrar serviço e custo
              juntos abria este formulário e lia que o primeiro preço ainda
              estava por fazer — então cadastrava de novo, e o catálogo ficava
              com duas versões do mesmo valor. */}
          <header>
            <div>
              <span>{selectedRow.service.code}</span>
              <h3>
                {selectedRow.priceVersions.length === 0
                  ? "Publicar primeiro preço"
                  : `Novo preço para ${selectedRow.service.name}`}
              </h3>
            </div>
            <button type="button" onClick={() => setEditor(null)}>Fechar</button>
          </header>
          {selectedRow.priceVersions.length > 0 ? (
            <p className="finance-catalog-editor__aviso" role="status">
              Este serviço já tem {selectedRow.priceVersions.length}{" "}
              {selectedRow.priceVersions.length === 1 ? "preço" : "preços"} no
              catálogo. Um preço novo não corrige o anterior — para consertar o
              que foi digitado errado use <strong>Corrigir</strong>, e para
              registrar uma revisão de contrato use <strong>Substituir</strong>,
              no histórico abaixo.
            </p>
          ) : null}
          <div className="finance-catalog-editor__grid">
            <label>
              Unidade
              <input
                name="unit"
                aria-label="Unidade"
                required
                maxLength={30}
                list={unidadesId}
                placeholder="m2, m3, ton, h"
              />
              <small>Escreva como preferir: m2 vira M², ton vira T.</small>
            </label>
            <label>Moeda<input name="currency" required value="BRL" readOnly /></label>
            <label>Valor unitário<input name="unitPrice" inputMode="decimal" required /></label>
            <label>
              Quantidade contratada
              <input
                name="contractedQuantity"
                aria-label="Quantidade contratada"
                inputMode="decimal"
              />
              <small>Opcional — o que o contrato prevê, quando se sabe.</small>
            </label>
            <label>Início da vigência<input name="validFrom" type="date" required /></label>
            <label>Fim da vigência<input name="validTo" type="date" /></label>
            <label>
              Fonte do preço
              <input name="source" required maxLength={80} defaultValue="CONTRATO_MEDIDO" />
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

      </div>

      {!loading && visiveis.length === 0 ? (
        <div className="finance-empty">
          <div>
            <h3>Nenhum serviço encontrado</h3>
            <p>
              {quantosExcluidos > 0
                ? "Todos os serviços deste catálogo estão excluídos. Use “Ver excluídos” para trazer algum de volta."
                : "O catálogo permanece vazio até um registro real ser criado ou sincronizado."}
            </p>
          </div>
        </div>
      ) : null}

      <div className="finance-service-list">
        {visiveis.map((row) => (
          <article key={row.service.id} className="finance-service-row">
            <header>
              <div>
                <code>{row.service.code}</code>
                <h3>{row.service.name}</h3>
                {row.service.description ? <p>{row.service.description}</p> : null}
              </div>
              <div className="finance-service-row__actions">
                {excluido(row.service) ? (
                  <span className="finance-service-row__excluido">Excluído</span>
                ) : null}
                <span data-sync={row.service.syncStatus}>{syncLabel(row.service.syncStatus)}</span>
                {canAdmin && !excluido(row.service) ? (
                  <>
                    <button
                      type="button"
                      onClick={() => setEditor({
                        type: "editService",
                        serviceId: row.service.id,
                      })}
                    >
                      Editar
                    </button>
                    <button type="button" onClick={() => setEditor({ type: "price", serviceId: row.service.id })}>
                      Novo preço para {row.service.name}
                    </button>
                  </>
                ) : null}
                {canAdmin ? (
                  <button
                    type="button"
                    className="finance-service-row__lixeira"
                    title={excluido(row.service)
                      ? `Restaurar ${row.service.name}`
                      : `Excluir ${row.service.name}`}
                    aria-label={excluido(row.service)
                      ? `Restaurar ${row.service.name}`
                      : `Excluir ${row.service.name}`}
                    disabled={saving}
                    onClick={() => {
                      if (excluido(row.service)) {
                        void alternarExclusao(row.service.id, false);
                      } else {
                        setConfirmandoExclusao(row.service.id);
                      }
                    }}
                  >
                    {excluido(row.service) ? RESTAURAR_ICONE : LIXEIRA_ICONE}
                  </button>
                ) : null}
              </div>
              {confirmandoExclusao === row.service.id ? (
                <p className="finance-service-row__confirma">
                  Excluir <strong>{row.service.name}</strong>? Ele sai do
                  catálogo para lançamentos novos; os preços e os RDOs que já o
                  usaram continuam como estão.
                  <span>
                    <button
                      type="button"
                      disabled={saving}
                      onClick={() => void alternarExclusao(row.service.id, true)}
                    >
                      Excluir
                    </button>
                    <button
                      type="button"
                      disabled={saving}
                      onClick={() => setConfirmandoExclusao(null)}
                    >
                      Manter
                    </button>
                  </span>
                </p>
              ) : null}
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
                    {/* Corrigir aparece antes de sincronizar, porque é aí que o
                        erro de digitação costuma ser notado. Substituir e
                        cancelar precisam da versão que o servidor conhece. */}
                    {canAdmin && price.status === "ACTIVE" ? (
                      <div className="finance-price-version__actions">
                        <button type="button" onClick={() => setEditor({ type: "editPrice", priceId: price.id, serviceId: row.service.id })}>Corrigir</button>
                        {price.syncStatus === "SYNCED" ? (
                          <>
                            <button type="button" onClick={() => setEditor({ type: "supersede", priceId: price.id, serviceId: row.service.id })}>Substituir</button>
                            <button type="button" onClick={() => setEditor({ type: "cancel", priceId: price.id, serviceId: row.service.id })}>Cancelar</button>
                          </>
                        ) : null}
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
