import {
  useCallback,
  useEffect,
  useState,
} from "react";

import { SyncStatusBanner } from "../../components/SyncStatusBanner";
import { IntegracoesPage } from "../integracoes/IntegracoesPage";
import { StaviaPanel } from "../stavia/StaviaPanel";
import type { LocalRdoRecord } from "../../lib/db/db.types";
import { listLocalRdos } from "../../lib/db/rdoRepository";
import { createEmptyRdo } from "./createEmptyRdo";
import { localRecordToDraft } from "./localRecordToDraft";
import { RdoCreatePage } from "./RdoCreatePage";
import { RdoLocalList } from "./RdoLocalList";
import type { RdoDraft } from "./rdo.types";

type WorkspaceMode =
  | {
      type: "LIST";
    }
  | {
      type: "INTEGRACOES";
    }
  | {
      type: "FORM";
      draft: RdoDraft;
      isExisting: boolean;
    };

export function RdoWorkspacePage() {
  const [mode, setMode] =
    useState<WorkspaceMode>({
      type: "LIST",
    });

  const [records, setRecords] =
    useState<LocalRdoRecord[]>([]);

  const [isLoading, setIsLoading] =
    useState(true);

  const [loadError, setLoadError] =
    useState("");
  const [isStaviaOpen, setIsStaviaOpen] =
    useState(false);

  const loadRecords =
    useCallback(async () => {
      setIsLoading(true);
      setLoadError("");

      try {
        const localRecords =
          await listLocalRdos();

        setRecords(localRecords);
      } catch (error: unknown) {
        setLoadError(
          error instanceof Error
            ? error.message
            : "Falha ao carregar os RDOs locais.",
        );
      } finally {
        setIsLoading(false);
      }
    }, []);

  useEffect(() => {
    const timeoutId = window.setTimeout(() => {
      void loadRecords();
    }, 0);

    return () => {
      window.clearTimeout(timeoutId);
    };
  }, [loadRecords]);

  function handleCreate() {
    setMode({
      type: "FORM",
      draft: createEmptyRdo(),
      isExisting: false,
    });
  }

  function handleOpen(
    record: LocalRdoRecord,
  ) {
    setMode({
      type: "FORM",
      draft:
        localRecordToDraft(record),
      isExisting: true,
    });
  }

  async function handleBackToList() {
    await loadRecords();

    setMode({
      type: "LIST",
    });
  }

  let pageContent;

  if (mode.type === "INTEGRACOES") {
    pageContent = (
      <IntegracoesPage
        onBack={() => {
          setMode({
            type: "LIST",
          });
        }}
      />
    );
  } else if (mode.type === "FORM") {
    pageContent = (
      <RdoCreatePage
        key={mode.draft.id}
        initialDraft={mode.draft}
        isExisting={mode.isExisting}
        onBackToList={() => {
          void handleBackToList();
        }}
        onSaved={() => {
          void loadRecords();
        }}
      />
    );
  } else {
    pageContent = (
      <RdoLocalList
        records={records}
        isLoading={isLoading}
        error={loadError}
        onCreate={handleCreate}
        onOpen={handleOpen}
        onRefresh={() => {
          void loadRecords();
        }}
        onOpenStavia={() => {
          setIsStaviaOpen(true);
        }}
        onOpenIntegracoes={() => {
          setMode({
            type: "INTEGRACOES",
          });
        }}
      />
    );
  }

  return (
    <>
      <SyncStatusBanner />
      {pageContent}
      {mode.type !== "INTEGRACOES" && (
        <StaviaPanel
          key={mode.type === "FORM"
            ? `${mode.draft.obraId}:${mode.draft.id}`
            : "stavia-floating-global"}
          variant="floating"
          isOpen={isStaviaOpen}
          onOpenChange={setIsStaviaOpen}
          initialObraId={mode.type === "FORM" ? mode.draft.obraId : ""}
          initialRdoId={mode.type === "FORM" ? mode.draft.id : ""}
        />
      )}
    </>
  );
}
