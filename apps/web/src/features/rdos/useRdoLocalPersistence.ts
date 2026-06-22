import { useState } from "react";
import { saveRdoDraftAtomically } from "../../lib/db/localRdoService";
import { syncNow } from "../../lib/sync/syncEngine";
import type { RdoDraft } from "./rdo.types";

export interface RdoPersistenceState {
  isSaving: boolean;
  isSyncing: boolean;
  message: string;
  error: string;
}

export function useRdoLocalPersistence() {
  const [state, setState] =
    useState<RdoPersistenceState>({
      isSaving: false,
      isSyncing: false,
      message: "",
      error: "",
    });

  async function saveLocally(
    draft: RdoDraft,
  ): Promise<void> {
    setState({
      isSaving: true,
      isSyncing: false,
      message: "",
      error: "",
    });

    try {
      await saveRdoDraftAtomically(draft);

      setState({
        isSaving: false,
        isSyncing: false,
        message:
          "RDO salvo neste dispositivo e aguardando sincronização.",
        error: "",
      });
    } catch (error: unknown) {
      setState({
        isSaving: false,
        isSyncing: false,
        message: "",
        error:
          error instanceof Error
            ? error.message
            : "Falha ao salvar o RDO localmente.",
      });

      throw error;
    }
  }

  async function synchronize(): Promise<void> {
    setState((current) => ({
      ...current,
      isSyncing: true,
      message: "",
      error: "",
    }));

    try {
      const summary = await syncNow();

      const recebidos =
        summary.pulled === 1
          ? "1 atualização recebida"
          : `${summary.pulled} atualizações recebidas`;

      // Mutações rejeitadas pelo servidor (erro/conflito) não são sucesso:
      // reporta como falha para evitar um falso positivo de status.
      if (summary.errors > 0 || summary.conflicts > 0) {
        const problemas: string[] = [];

        if (summary.errors > 0) {
          problemas.push(
            summary.errors === 1
              ? "1 alteração com erro"
              : `${summary.errors} alterações com erro`,
          );
        }

        if (summary.conflicts > 0) {
          problemas.push(
            summary.conflicts === 1
              ? "1 alteração em conflito"
              : `${summary.conflicts} alterações em conflito`,
          );
        }

        setState({
          isSaving: false,
          isSyncing: false,
          message: "",
          error:
            `Sincronização incompleta: ${
              summary.applied === 1
                ? "1 alteração aplicada"
                : `${summary.applied} alterações aplicadas`
            }; ${problemas.join(" e ")}. ${recebidos}.`,
        });

        return;
      }

      // Nada pendente para enviar: deixa explícito que nenhuma mutação foi
      // encontrada, em vez de anunciar uma sincronização bem-sucedida.
      if (summary.pushed === 0) {
        setState({
          isSaving: false,
          isSyncing: false,
          message:
            `Nenhuma alteração pendente para sincronizar. ${recebidos}.`,
          error: "",
        });

        return;
      }

      setState({
        isSaving: false,
        isSyncing: false,
        message:
          `Sincronização concluída: ${
            summary.applied === 1
              ? "1 alteração aplicada"
              : `${summary.applied} alterações aplicadas`
          }. ${recebidos}.`,
        error: "",
      });
    } catch (error: unknown) {
      setState({
        isSaving: false,
        isSyncing: false,
        message: "",
        error:
          error instanceof Error
            ? error.message
            : "Falha na sincronização.",
      });

      throw error;
    }
  }

  return {
    ...state,
    saveLocally,
    synchronize,
  };
}
