import { processObjectUploads } from "../../features/mensagens/objectUploadSync";
import { processRdoPhotoUploads } from "../../features/rdos/rdoPhotoSync";
import { refreshMessagingAfterPull } from "../../features/mensagens/mensagensHydration";
import {
  hydrateBlockedRdoCreationContextsForSync,
  recoverErroredWorkforceRdoMutationsForSync,
  releaseBlockedRdoUpdatesForSync,
  recoverRejectedRdoMutationsForSync,
  repairRdoCreateMutationsForSync,
} from "../db/localRdoService";
import { updateSyncState } from "../db/syncStateRepository";
import { acknowledgeCurrentCursor } from "./ackCursor";
import { pullEvents } from "./pullEvents";
import { pushOutbox } from "./pushOutbox";
import { ensureRegisteredDevice } from "./registerDevice";
import {
  contarMutacoesDaOutbox,
  desamarrarCitacoesFantasmas,
  podarMutacoesJaAplicadas,
  queueErroredMutationsForRetry,
  recoverInterruptedMutations,
  recoverCanonicalConflictReconciliations,
  recoverRejectedArchivedObraMutationsForSync,
  recoverRejectedGeometryMutationsForSync,
  reidentificarObrasInexistentesForSync,
  repairMissingMaoObraReferencesForSync,
  repairMissingObraReferencesForSync,
  resolveCanonicalUploadReplacements,
} from "./syncStorage";
import {
  assertSyncSession,
  captureOnlineSyncSession,
  type SyncSessionGuard,
} from "./syncSession";
import {
  runWithSyncExecutionLease,
  type SyncExecutionLease,
} from "./syncExecutionLease";
import type { SyncRunSummary } from "./sync.types";
import { announceSyncCompleted } from "./syncEvents";

export { SYNC_COMPLETED_EVENT } from "./syncEvents";

const activeSyncPromises = new Map<
  string,
  Promise<SyncRunSummary>
>();

async function executeSync(
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
): Promise<SyncRunSummary> {
  if (!navigator.onLine) {
    throw new Error(
      "O dispositivo está offline. O RDO continua salvo localmente.",
    );
  }
  await assertSyncExecution(guard, lease);
  await updateSyncState({
    isSyncing: true,
    lastSyncStartedAt: new Date().toISOString(),
    lastSyncError: null,
  }, guard);
  await assertSyncExecution(guard, lease);

  /*
   * Reparo é manutenção da fila, não é o trabalho. Uma linha podre — gravada
   * por uma versão antiga do app, ou corrompida de qualquer outro jeito — fazia
   * o reparo dela estourar e levava o ciclo inteiro junto: o aparelho parava de
   * enviar e de receber TUDO, para sempre, por causa de uma linha. E só aquele
   * aparelho: nas outras máquinas a sincronização seguia normal, que é a
   * assinatura do defeito mais difícil de reproduzir.
   *
   * O passo que falha é pulado e anotado; o envio, o recebimento e os demais
   * reparos continuam. A exceção é a sessão trocada ou o lease perdido no meio
   * do passo: a reconferência abaixo relança exatamente esses, porque aí quem
   * deve morrer é o ciclo mesmo.
   */
  const reparosFalharam: string[] = [];
  const reparoSemDerrubarOCiclo = async (
    nome: string,
    passo: () => Promise<unknown>,
  ): Promise<void> => {
    try {
      await passo();
    } catch (error: unknown) {
      await assertSyncExecution(guard, lease);
      reparosFalharam.push(nome);
      console.warn(
        `[sync] O reparo "${nome}" falhou e foi pulado neste ciclo.`,
        error,
      );
    }
    await assertSyncExecution(guard, lease);
  };

  try {
    /*
     * A fila vazia dispensa a manutenção da fila. Todos os reparos pré-envio
     * leem a outbox e só a outbox; num aparelho em dia — o estado normal —
     * eles eram uma dúzia de varreduras a cada trinta segundos para concluir
     * que não havia nada. Um count responde antes de qualquer uma delas.
     */
    const filaTemLinhas = (await contarMutacoesDaOutbox(guard)) > 0;
    await assertSyncExecution(guard, lease);

    if (filaTemLinhas) {
      await reparoSemDerrubarOCiclo("mutações interrompidas", () =>
        recoverInterruptedMutations(guard));
      await reparoSemDerrubarOCiclo("referências de obra", () =>
        repairMissingObraReferencesForSync(guard));
      await reparoSemDerrubarOCiclo("referências de mão de obra", () =>
        repairMissingMaoObraReferencesForSync(guard));
      // Antes da hidratação de contexto: as criações reidentificadas voltam à
      // fila bloqueadas pelo recibo, e é a hidratação deste mesmo ciclo que as
      // destrava contra a obra viva.
      await reparoSemDerrubarOCiclo("obras reidentificadas", () =>
        reidentificarObrasInexistentesForSync(guard));
      await reparoSemDerrubarOCiclo("contextos de criação", () =>
        hydrateBlockedRdoCreationContextsForSync(guard));
      /*
       * Faxina das linhas presas por uma regra que já não existe: a edição de
       * rascunho não é mais barrada pelo recibo de contexto, mas a fila de quem
       * já tinha uma bloqueada não se corrige sozinha, e o envio a descartaria
       * em silêncio para sempre.
       */
      await reparoSemDerrubarOCiclo("edições retidas", () =>
        releaseBlockedRdoUpdatesForSync(guard));
      await reparoSemDerrubarOCiclo("criações de RDO", () =>
        repairRdoCreateMutationsForSync(guard));
      // Antes do reenvio genérico: o vínculo de mão de obra recusado tem
      // reparo próprio, e reenviar sem repará-lo só repetiria a mesma recusa.
      await reparoSemDerrubarOCiclo("vínculos de mão de obra", () =>
        recoverErroredWorkforceRdoMutationsForSync(guard));
      await reparoSemDerrubarOCiclo("RDOs recusados", () =>
        recoverRejectedRdoMutationsForSync(guard, {
          executionLease: lease,
        }));
      await reparoSemDerrubarOCiclo("geometrias recusadas", () =>
        recoverRejectedGeometryMutationsForSync(guard));
      await reparoSemDerrubarOCiclo("obras arquivadas recusadas", () =>
        recoverRejectedArchivedObraMutationsForSync(guard));
      // Nenhuma alteração de campo fica parada sem retentativa: o que sobrou
      // em ERROR volta à fila com espera escalonada, em vez de morrer ali.
      await reparoSemDerrubarOCiclo("retentativas escalonadas", () =>
        queueErroredMutationsForRetry(guard));
    }

    const deviceId = await ensureRegisteredDevice(guard);
    await assertSyncExecution(guard, lease);
    const uploadSummary = await processObjectUploads(20, guard);
    await assertSyncExecution(guard, lease);
    /*
     * As fotos de RDO sobem no mesmo ponto do ciclo em que os objetos de
     * mensagem sobem, e pela mesma razão: é trabalho de binário, não de
     * mutação. Uma foto que falha não derruba o ciclo — ela fica no aparelho
     * e tenta de novo na próxima janela.
     */
    await reparoSemDerrubarOCiclo("fotos de RDO", () =>
      processRdoPhotoUploads(guard));
    // Fora do portão da fila vazia de propósito: os uploads deste mesmo ciclo
    // podem ter acabado de criar as linhas que estes dois passos resolvem.
    await reparoSemDerrubarOCiclo("substituições de upload", () =>
      resolveCanonicalUploadReplacements(guard));
    await reparoSemDerrubarOCiclo("reconciliações de conflito", () =>
      recoverCanonicalConflictReconciliations(guard));
    /*
     * O reparo vem antes do push. Uma mutação que cita uma dependência já
     * inexistente seria recusada pelo servidor em todo envio, para sempre —
     * DEPENDENCY_NOT_APPLIED não prescreve. Reparar depois do push gastaria
     * mais uma rodada inteira com uma recusa que já se sabia inevitável.
     */
    try {
      await desamarrarCitacoesFantasmas();
    } catch {
      // Sem reparo o push ainda vale para as mutações sãs.
    }
    const pushSummary = await pushOutbox(deviceId, guard);
    await assertSyncExecution(guard, lease);
    const recoveredReplacementIds = new Set<string>();
    const recoveredReplacementByOriginalId =
      new Map<string, string>();
    /*
     * O reparo pós-push é tão manutenção quanto os pré-push, e falha do mesmo
     * jeito: uma linha podre aqui derrubava o ciclo já com o push feito, e o
     * pull — que traria o que os outros aparelhos mandaram — nunca acontecia.
     * Falhou, vale zero recuperado e o ciclo segue para o pull.
     */
    let recoveredAfterPush = 0;
    if (pushSummary.errors > 0) {
      try {
        recoveredAfterPush =
          (await recoverRejectedRdoMutationsForSync(guard, {
            executionLease: lease,
            recoveredReplacementIds,
            recoveredReplacementByOriginalId,
          })) +
          // O reparo do vínculo no mesmo ciclo do envio: quem chegou ao campo
          // hoje sobe hoje, sem esperar a próxima janela de sincronização.
          (await recoverErroredWorkforceRdoMutationsForSync(guard, {
            requeuedMutationIds: recoveredReplacementByOriginalId,
          }));
      } catch (error: unknown) {
        await assertSyncExecution(guard, lease);
        reparosFalharam.push("recuperação pós-envio");
        console.warn(
          "[sync] A recuperação pós-envio falhou e foi pulada neste ciclo.",
          error,
        );
      }
    }
    await assertSyncExecution(guard, lease);
    /*
     * A fusão por campo já resolveu o conflito e a substituta está na fila; o
     * ciclo só não a enviava porque o reenvio dependia de `errors`, e conflito
     * conta em `conflicts`. A resolução ficava esperando a próxima janela, com
     * a tarja vermelha acesa por um impasse que já não existia. Conflito que se
     * resolve sozinho tem de morrer na janela em que nasceu.
     */
    const conflitosReconciliados =
      pushSummary.reconciledReplacementByOriginalId;
    const retryPushSummary =
      recoveredAfterPush > 0 || conflitosReconciliados.size > 0
        ? await pushOutbox(deviceId, guard)
        : {
          pushed: 0,
          applied: 0,
          errors: 0,
          retryableErrors: 0,
          conflicts: 0,
          appliedMutationIds: [],
          handledMutationIds: [],
          errorMutationIds: [],
          reconciledReplacementByOriginalId: new Map<string, string>(),
        };
    await assertSyncExecution(guard, lease);
    const pullSummary = await pullEvents(deviceId, guard);
    // Este passo vai à rede duas vezes, e ficou de fora do isolamento quando
    // os reparos entraram. Fora dele, uma falha de mensageria derrubava o
    // ciclo DEPOIS de o pull já ter gravado tudo: o cursor não era confirmado,
    // a fila não era podada e — o pior — o aviso de fim de sincronização nunca
    // saía, então as telas abertas continuavam mostrando o retrato velho de um
    // dado que já estava no aparelho.
    await reparoSemDerrubarOCiclo("mensagens após o pull", () =>
      refreshMessagingAfterPull(pullSummary.messagingConversationIds, guard),
    );
    const acknowledgedCommitSeq =
      await acknowledgeCurrentCursor(deviceId, guard);
    await assertSyncExecution(guard, lease);
    const currentPushErrorIds = new Set(
      pushSummary.errorMutationIds,
    );
    const retryHandledIds = new Set(
      retryPushSummary.handledMutationIds,
    );
    const replacedCurrentPushErrorCount = [
      ...recoveredReplacementByOriginalId,
    ].filter(([originalId, replacementId]) =>
      currentPushErrorIds.has(originalId) &&
      retryHandledIds.has(replacementId)
    ).length;
    /*
     * Um conflito cuja substituta subiu nesta mesma janela não é um conflito
     * a relatar: ele nasceu e morreu aqui dentro. Contá-lo faria a tela pedir
     * uma decisão sobre algo que já está resolvido — que é como o impasse
     * ganhava vida útil mesmo quando a fusão automática funcionava.
     */
    const conflitosResolvidosNestaJanela = [
      ...conflitosReconciliados,
    ].filter(([, replacementId]) =>
      retryHandledIds.has(replacementId)
    ).length;

    /*
     * A poda fecha o ciclo: o que subiu nesta janela já não precisa da linha na
     * fila, e sem isso a outbox só cresce. Vai depois do push, do pull e do
     * ack — antes de qualquer um deles, apagaria linha que o próprio ciclo
     * ainda vai ler.
     *
     * Falha aqui não derruba a sincronização. Toda a escrita real já aconteceu
     * e o resumo já está formado; deixar de podar custa espaço, enquanto
     * propagar a exceção custaria o ciclo inteiro e acenderia a tarja vermelha
     * por uma faxina.
     */
    try {
      await podarMutacoesJaAplicadas();
    } catch {
      // Sobra para a próxima janela.
    }
    await assertSyncExecution(guard, lease);

    await updateSyncState({
      isSyncing: false,
      lastSyncCompletedAt: new Date().toISOString(),
      lastSyncError: null,
    }, guard);
    await assertSyncExecution(guard, lease);

    const summary = {
      deviceId,
      pushed:
        uploadSummary.pushed +
        pushSummary.pushed +
        retryPushSummary.pushed,
      applied:
        uploadSummary.applied +
        pushSummary.applied +
        retryPushSummary.applied,
      errors:
        uploadSummary.errors +
        Math.max(
          0,
          pushSummary.errors -
            Math.min(
              pushSummary.errors,
              replacedCurrentPushErrorCount,
            ),
        ) +
        retryPushSummary.errors,
      retryableErrors:
        pushSummary.retryableErrors +
        retryPushSummary.retryableErrors,
      conflicts:
        Math.max(
          0,
          pushSummary.conflicts - conflitosResolvidosNestaJanela,
        ) + retryPushSummary.conflicts,
      pulled: pullSummary.pulled,
      acknowledgedCommitSeq,
      pullPendente: pullSummary.pendente,
      reparosFalharam,
    };
    announceSyncCompleted();
    return summary;
  } catch (error: unknown) {
    // Never write the old run's status into a newly active session database.
    try {
      await assertSyncExecution(guard, lease);
      const message =
        error instanceof Error
          ? error.message
          : "Falha desconhecida na sincronização.";
      await updateSyncState({
        isSyncing: false,
        lastSyncCompletedAt: new Date().toISOString(),
        lastSyncError: message,
      }, guard);
      await assertSyncExecution(guard, lease);
    } catch {
      // The original session is gone; its next run recovers SYNCING rows.
    }
    throw error;
  }
}

async function assertSyncExecution(
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
): Promise<void> {
  assertSyncSession(guard);
  await lease.assertOwned();
  assertSyncSession(guard);
}

export function syncNow(): Promise<SyncRunSummary> {
  let guard: SyncSessionGuard;
  try {
    guard = captureOnlineSyncSession();
  } catch (error: unknown) {
    return Promise.reject(error);
  }
  const active = activeSyncPromises.get(guard.fingerprint);
  if (active) return active;

  const promise = runWithSyncExecutionLease(
    guard,
    (lease) => executeSync(guard, lease),
  ).finally(() => {
    if (activeSyncPromises.get(guard.fingerprint) === promise) {
      activeSyncPromises.delete(guard.fingerprint);
    }
  });
  activeSyncPromises.set(guard.fingerprint, promise);
  return promise;
}
