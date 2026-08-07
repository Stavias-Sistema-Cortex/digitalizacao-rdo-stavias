import {
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";

import { Link } from "react-router";

import {
  useSyncStatus,
  type SyncUiStatus,
} from "../lib/sync/useSyncStatus";
import { syncNow } from "../lib/sync/syncEngine";
import { isSyncLeaseContentionError } from "../lib/sync/syncLeaseContention";
import {
  descartarMutacoesMortas,
  requeueMutationsInReview,
} from "../lib/sync/syncStorage";
import {
  apagarBaseLocal,
  contarTrabalhoQueSeriaPerdido,
  type TrabalhoNaoEnviado,
} from "../lib/sync/recomecoDoZero";
import "./SyncStatusBanner.css";

interface StatusContent {
  title: string;
  description: string;
}

function pluralize(
  count: number,
  singular: string,
  plural: string,
): string {
  return `${count} ${count === 1 ? singular : plural}`;
}

interface StatusInput {
  status: SyncUiStatus;
  pendingCount: number;
  syncingCount: number;
  errorCount: number;
  conflictCount: number;
  reviewCount: number;
  insistindoCount: number;
  travadasCount: number;
  travaMotivo: string | null;
}

function getStatusContent({
  status,
  pendingCount,
  syncingCount,
  errorCount,
  conflictCount,
  reviewCount,
  insistindoCount,
  travadasCount,
  travaMotivo,
}: StatusInput): StatusContent {
  const localChangesCount =
    pendingCount +
    syncingCount +
    errorCount +
    conflictCount +
    reviewCount;

  switch (status) {
    case "OFFLINE":
      return {
        title: "Sem conexão",
        description:
          reviewCount > 0
            ? `${pluralize(
                reviewCount,
                "registro exige",
                "registros exigem",
              )} revisão neste dispositivo. Esses itens não serão reenviados automaticamente.`
            : localChangesCount > 0
            ? `${pluralize(
                localChangesCount,
                "alteração permanece",
                "alterações permanecem",
              )} salva${
                localChangesCount === 1 ? "" : "s"
              } neste dispositivo e será${
                localChangesCount === 1 ? "" : "ão"
              } sincronizada${
                localChangesCount === 1 ? "" : "s"
              } quando a conexão retornar.`
            : "Os dados continuam disponíveis e podem ser salvos neste dispositivo.",
      };

    /*
     * "O sistema tentará sincronizar automaticamente" é verdade e é inútil
     * depois da centésima tentativa. A retentativa devolve a linha a PENDING,
     * então quem insiste há horas era contado junto com o que entrou na fila há
     * três segundos, e a tela dizia a mesma frase tranquilizadora para os dois.
     * Numa fila real havia geometrias com 261 tentativas, e a única maneira de
     * descobrir isso foi despejar o IndexedDB no console.
     *
     * Continua sem teto de tentativas — desistir apagaria trabalho que ainda
     * vai subir. O que muda é a tela parar de chamar de espera o que já virou
     * sintoma.
     */
    /*
     * Antes de "insistindo", porque é pior: insistir é tentar e falhar, e o
     * motor de envio nem chega a pegar estas linhas. Um RDO ficou assim — a
     * fila local marcava 1, a tarja prometia envio automático, e a aba de rede
     * não registrava um único `push`, porque `selectReadyOutboxMutations` já o
     * havia descartado antes de qualquer requisição existir. Sem nome e sem
     * motivo na tela, o único caminho até a causa era abrir o IndexedDB.
     */
    case "PENDING":
      if (travadasCount > 0) {
        return {
          title: "Sincronização parada",
          description: `${pluralize(
            travadasCount,
            "alteração não vai subir",
            "alterações não vão subir",
          )} sozinha${travadasCount === 1 ? "" : "s"}. Nada se perdeu: ${
            travadasCount === 1 ? "ela continua salva" : "elas continuam salvas"
          } aqui.${travaMotivo ? ` ${travaMotivo}` : ""}`,
        };
      }

      if (insistindoCount > 0) {
        return {
          title: "Sincronização insistindo",
          description: `${pluralize(
            insistindoCount,
            "alteração vem tentando subir",
            "alterações vêm tentando subir",
          )} há bastante tempo sem conseguir. Nada se perdeu: ${
            insistindoCount === 1 ? "ela continua" : "elas continuam"
          } salva${insistindoCount === 1 ? "" : "s"} aqui e o envio segue.`,
        };
      }

      return {
        title: "Aguardando sincronização",
        description: `${pluralize(
          pendingCount,
          "alteração está pendente",
          "alterações estão pendentes",
        )}. O sistema tentará sincronizar automaticamente.`,
      };

    case "SYNCING":
      return {
        title: "Sincronizando",
        description:
          "Enviando alterações locais e buscando atualizações do servidor.",
      };

    case "ERROR":
      if (errorCount === 0) {
        return {
          title: "Falha na sincronização",
          description:
            "Não foi possível confirmar a sincronização com o servidor. Os dados locais continuam salvos neste dispositivo.",
        };
      }

      return {
        title: "Falha na sincronização",
        description: `${pluralize(
          errorCount,
          "alteração não pôde ser sincronizada",
          "alterações não puderam ser sincronizadas",
        )}. Os dados continuam salvos neste dispositivo.`,
      };

    /*
     * O texto dizia "temporariamente bloqueado", e não era verdade: quando os
     * dois lados mexem no mesmo campo, nenhuma retentativa desfaz o impasse —
     * ele fica até alguém decidir qual versão vale. Prometer que passa sozinho
     * é o que fazia a pessoa apertar "Sincronizar agora" indefinidamente.
     */
    case "CONFLICT":
      return {
        title: "Conflito de versão",
        description: `${pluralize(
          conflictCount,
          // "Registro", não "RDO": conflito de versão acontece com equipe e
          // com obra também, e chamar tudo de RDO mandava a pessoa procurar
          // o problema na tela errada — aconteceu.
          "registro foi alterado",
          "registros foram alterados",
        )} no servidor depois da sua edição. ${
          conflictCount === 1
            ? "Nada se perdeu: a sua versão está guardada e precisa"
            : "Nada se perdeu: as suas versões estão guardadas e precisam"
        } de uma decisão sua sobre qual vale.`,
      };

    case "REVIEW":
      return {
        title: "Revisão necessária",
        description: `${pluralize(
          reviewCount,
          "registro exige",
          "registros exigem",
        )} revisão antes de qualquer novo envio.`,
      };

    case "SYNCED":
      return {
        title: "Sincronizado",
        description:
          "As alterações locais estão alinhadas com o servidor.",
      };
  }
}

function formatLastSync(
  value: string | null,
): string | null {
  if (!value) {
    return null;
  }

  const date = new Date(value);

  if (Number.isNaN(date.getTime())) {
    return null;
  }

  return new Intl.DateTimeFormat(
    "pt-BR",
    {
      dateStyle: "short",
      timeStyle: "medium",
    },
  ).format(date);
}

export function SyncStatusBanner() {
  const { snapshot, refresh } =
    useSyncStatus();
  const [manualSyncError, setManualSyncError] =
    useState("");
  const [isManualSyncing, setIsManualSyncing] =
    useState(false);
  const [isOpen, setIsOpen] = useState(false);
  const [zerarPedido, setZerarPedido] = useState(false);
  const [perdaAoZerar, setPerdaAoZerar] =
    useState<TrabalhoNaoEnviado | null>(null);
  const rootRef = useRef<HTMLDivElement>(null);

  const displayedStatus: SyncUiStatus =
    manualSyncError ? "ERROR" : snapshot.status;
  const chipVisualStatus =
    displayedStatus === "REVIEW"
      ? "CONFLICT"
      : displayedStatus;

  const content = useMemo(
    () =>
      getStatusContent({
        status: displayedStatus,
        pendingCount: snapshot.pendingCount,
        syncingCount: snapshot.syncingCount,
        errorCount: snapshot.errorCount,
        conflictCount: snapshot.conflictCount,
        reviewCount: snapshot.reviewCount,
        insistindoCount: snapshot.insistindoCount,
        travadasCount: snapshot.travadasCount,
        travaMotivo: snapshot.travaMotivo,
      }),
    [
      displayedStatus,
      snapshot.pendingCount,
      snapshot.syncingCount,
      snapshot.errorCount,
      snapshot.conflictCount,
      snapshot.reviewCount,
      snapshot.insistindoCount,
      snapshot.travadasCount,
      snapshot.travaMotivo,
    ],
  );

  const lastSyncText = formatLastSync(
    snapshot.lastSyncCompletedAt,
  );

  const visibleSyncError =
    manualSyncError ||
    (displayedStatus === "REVIEW"
      ? snapshot.reviewReason
      : displayedStatus === "ERROR" || displayedStatus === "CONFLICT"
      ? snapshot.lastSyncError
      : null);

  const attentionCount =
    snapshot.pendingCount +
    snapshot.errorCount +
    snapshot.conflictCount +
    snapshot.reviewCount;

  useEffect(() => {
    if (
      manualSyncError &&
      !snapshot.lastSyncError &&
      snapshot.status !== "ERROR"
    ) {
      const resetId = window.setTimeout(() => {
        setManualSyncError("");
      }, 0);

      return () => window.clearTimeout(resetId);
    }

    return undefined;
  }, [
    manualSyncError,
    snapshot.lastSyncError,
    snapshot.status,
  ]);

  // Fecha o popover ao clicar fora ou pressionar Escape.
  useEffect(() => {
    if (!isOpen) {
      return undefined;
    }

    function handlePointerDown(event: PointerEvent) {
      if (
        rootRef.current &&
        event.target instanceof Node &&
        !rootRef.current.contains(event.target)
      ) {
        setIsOpen(false);
      }
    }

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        setIsOpen(false);
      }
    }

    document.addEventListener("pointerdown", handlePointerDown);
    document.addEventListener("keydown", handleKeyDown);

    return () => {
      document.removeEventListener("pointerdown", handlePointerDown);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [isOpen]);

  async function handleSyncNow(): Promise<void> {
    setIsManualSyncing(true);
    setManualSyncError("");

    try {
      await syncNow();
    } catch (error: unknown) {
      // Outra aba já está sincronizando: o trabalho está sendo feito, e
      // anunciar falha aqui faria quem está em campo achar que o apontamento
      // não subiu. O `refresh` abaixo mostra o que a outra aba conseguiu.
      if (!isSyncLeaseContentionError(error)) {
        setManualSyncError(
          error instanceof Error
            ? error.message
            : "Falha ao sincronizar agora.",
        );
      }
    } finally {
      setIsManualSyncing(false);
      await refresh();
    }
  }

  // "Sincronizar agora" não toca em registro recusado, por definição. Sem esta
  // ação, quem chega em "revisão necessária" não tem o que fazer na tela.
  async function handleDiscardDead(): Promise<void> {
    setIsManualSyncing(true);
    setManualSyncError("");
    try {
      const descartadas = await descartarMutacoesMortas();
      setManualSyncError(
        descartadas === 0
          ? "Nada foi descartado: tudo o que resta ainda tem como subir."
          : `${descartadas} ${
              descartadas === 1 ? "registro descartado" : "registros descartados"
            }. A versão do servidor volta a valer.`,
      );
    } catch (error: unknown) {
      if (!isSyncLeaseContentionError(error)) {
        setManualSyncError(
          error instanceof Error
            ? error.message
            : "Falha ao descartar os registros sem saída.",
        );
      }
    } finally {
      setIsManualSyncing(false);
      await refresh();
    }
  }

  /*
   * A conta vem antes do gesto. Perguntar "tem certeza?" sem dizer o tamanho do
   * prejuízo é pedir uma decisão às cegas.
   */
  async function pedirParaZerar(): Promise<void> {
    setZerarPedido(true);
    setPerdaAoZerar(null);
    setManualSyncError("");
    try {
      setPerdaAoZerar(await contarTrabalhoQueSeriaPerdido());
    } catch {
      // Sem a conta, o botão de confirmar segue desabilitado — melhor travar
      // do que apagar sem saber o quanto.
      setManualSyncError("Não foi possível conferir o que ainda não subiu.");
    }
  }

  async function handleStartOver(): Promise<void> {
    setIsManualSyncing(true);
    setManualSyncError("");
    try {
      await apagarBaseLocal();
      // Recarregar é parte do gesto: os dados em memória apontam para uma base
      // que não existe mais, e seguir navegando sobre eles é o caminho curto
      // para um erro que não diz nada.
      window.location.reload();
    } catch (error: unknown) {
      setIsManualSyncing(false);
      setManualSyncError(
        error instanceof Error
          ? error.message
          : "Falha ao apagar os dados locais.",
      );
    }
  }

  async function handleReleaseReview(): Promise<void> {
    setIsManualSyncing(true);
    setManualSyncError("");

    try {
      await requeueMutationsInReview();
      await syncNow();
    } catch (error: unknown) {
      // O reenvio já aconteceu antes do envio: se a disputa de aba interrompe
      // aqui, dizer "falha ao reenviar" nega um reenvio que de fato ocorreu, e
      // convida a repetir um gesto que já surtiu efeito.
      if (!isSyncLeaseContentionError(error)) {
        setManualSyncError(
          error instanceof Error
            ? error.message
            : "Falha ao reenviar os registros em revisão.",
        );
      }
    } finally {
      setIsManualSyncing(false);
      await refresh();
    }
  }

  const chipTitle = snapshot.isLoading
    ? "Verificando sincronização"
    : content.title;

  return (
    <div
      ref={rootRef}
      className={`sync-chip sync-chip--${chipVisualStatus.toLowerCase()}`}
    >
      <button
        type="button"
        className="sync-chip__button"
        onClick={() => setIsOpen((open) => !open)}
        aria-expanded={isOpen}
        aria-haspopup="dialog"
        aria-label={`Sincronização: ${chipTitle}`}
        title={chipTitle}
      >
        <svg
          className="sync-chip__icon"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          <path d="M21 12a9 9 0 0 1-15.3 6.4L3 16" />
          <path d="M3 12a9 9 0 0 1 15.3-6.4L21 8" />
          <path d="M3 21v-5h5" />
          <path d="M21 3v5h-5" />
        </svg>
        <span className="sync-chip__dot" aria-hidden="true" />
        {attentionCount > 0 ? (
          <span className="sync-chip__count" aria-hidden="true">
            {attentionCount > 9 ? "9+" : attentionCount}
          </span>
        ) : null}
      </button>

      <span className="visually-hidden" role="status" aria-live="polite">
        {chipTitle}
      </span>

      {isOpen ? (
        <div
          className="sync-chip__popover"
          role="dialog"
          aria-label="Estado da sincronização"
        >
          <div className="sync-chip__header">
            <span className="sync-chip__header-dot" aria-hidden="true" />
            <strong>{chipTitle}</strong>
          </div>

          <p className="sync-chip__description">
            {snapshot.isLoading
              ? "Consultando o estado local."
              : content.description}
          </p>

          <p className="sync-chip__meta">
            {lastSyncText
              ? `Última sincronização: ${lastSyncText}`
              : "Ainda não sincronizado"}
          </p>

          {visibleSyncError && (
            <p className="sync-chip__error">
              {visibleSyncError}
            </p>
          )}

          <button
            type="button"
            className="sync-chip__action"
            onClick={() => {
              void handleSyncNow();
            }}
            disabled={isManualSyncing}
          >
            {isManualSyncing
              ? "Sincronizando..."
              : "Sincronizar agora"}
          </button>

          {/*
            Conflito não se resolve insistindo: a saída é a tela onde a versão
            do servidor e a local aparecem lado a lado, com o motivo de cada
            impasse. Sem este caminho, o aviso era um beco — badge vermelho
            permanente e nenhuma ação capaz de apagá-lo.
          */}
          {snapshot.conflictCount > 0 ? (
            <Link
              className="sync-chip__action sync-chip__action--secondary"
              to="/home?tab=memory&pendencias=1"
            >
              {`Resolver ${pluralize(
                snapshot.conflictCount,
                "registro em conflito",
                "registros em conflito",
              )}`}
            </Link>
          ) : null}

          {snapshot.reviewCount > 0 ? (
            <>
              {/*
                O reenvio só aparece para quem ele pode resolver.
                Oferecê-lo para conflito de versão era um laço com aparência de
                conserto: 345 reenviados, 345 recusados, mesmo número de volta —
                porque o reenvio troca a identidade e preserva a versão-base,
                que é exatamente o que o servidor recusou. Ficava acima do
                descarte, que é a saída real, e consumia a tentativa de quem
                estava tentando resolver.
              */}
              {snapshot.reenviaveisCount > 0 ? (
                <>
                  <button
                    type="button"
                    className="sync-chip__action sync-chip__action--secondary"
                    onClick={() => {
                      void handleReleaseReview();
                    }}
                    disabled={isManualSyncing}
                  >
                    {`Reenviar ${pluralize(
                      snapshot.reenviaveisCount,
                      "registro em revisão",
                      "registros em revisão",
                    )}`}
                  </button>
                  <p className="sync-chip__hint">
                    Nada se perde: o que não subir volta para revisão.
                  </p>
                </>
              ) : (
                <p className="sync-chip__hint">
                  Reenviar não resolveria: o servidor recusou pela versão, e o
                  reenvio manda a mesma versão de novo.
                </p>
              )}
              {/*
                Reenviar não resolve recusa por versão — o reenvio troca a
                identidade da mutação, não a versão-base que o servidor recusou.
                Sem esta saída, quem acumulava recusas girava entre dois botões
                que não diminuíam a pilha.
              */}
              <button
                type="button"
                className="sync-chip__action sync-chip__action--secondary"
                onClick={() => {
                  void handleDiscardDead();
                }}
                disabled={isManualSyncing}
              >
                Descartar o que não tem mais como subir
              </button>
              <p className="sync-chip__hint">
                Apaga a sua versão destes registros e aceita a do servidor.
                Não mexe no que ainda tem substituta a caminho.
              </p>

              {/*
                A saída de último caso. O descarte acima resolve o comum: linhas
                recusadas, uma a uma. Quando nem isso destrava, o honesto é
                declarar bancarrota do que é local e reconstruir do servidor —
                que é a verdade de qualquer jeito.

                O preço aparece antes do gesto e com número, não como "alguns
                registros": a pessoa precisa saber se está jogando fora três
                apontamentos ou trinta.
              */}
              {zerarPedido ? (
                <div className="sync-chip__confirmacao" role="alertdialog">
                  <p>
                    {perdaAoZerar === null
                      ? "Conferindo o que ainda não subiu…"
                      : perdaAoZerar.aindaPodemSubir === 0
                        ? "Nada que ainda tenha como subir será perdido."
                        : `${perdaAoZerar.aindaPodemSubir} ${
                            perdaAoZerar.aindaPodemSubir === 1
                              ? "registro que ainda tinha como subir vai sumir"
                              : "registros que ainda tinham como subir vão sumir"
                          } para sempre.`}
                  </p>
                  <div className="sync-chip__confirmacao-acoes">
                    <button
                      type="button"
                      className="sync-chip__action sync-chip__action--danger"
                      onClick={() => {
                        void handleStartOver();
                      }}
                      disabled={isManualSyncing || perdaAoZerar === null}
                    >
                      Apagar e recarregar
                    </button>
                    <button
                      type="button"
                      className="sync-chip__action sync-chip__action--secondary"
                      onClick={() => setZerarPedido(false)}
                      disabled={isManualSyncing}
                    >
                      Cancelar
                    </button>
                  </div>
                </div>
              ) : (
                <button
                  type="button"
                  className="sync-chip__action sync-chip__action--secondary"
                  onClick={() => {
                    void pedirParaZerar();
                  }}
                  disabled={isManualSyncing}
                >
                  Recomeçar do zero neste aparelho
                </button>
              )}
            </>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
