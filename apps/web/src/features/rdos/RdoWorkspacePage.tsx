import {
  useCallback,
  useEffect,
  useRef,
  useState,
} from "react";

import { CortexShell } from "../../components/shell/CortexShell";
import { getSession } from "../auth/authSession";
import {
  listOperationalEvents,
} from "../../lib/db/operationalEventRepository";
import {
  listAllRdoAttachments,
} from "../../lib/db/rdoAttachmentRepository";
import type {
  LocalRdoRecord,
  OperationalEventRecord,
  RdoAttachmentRecord,
} from "../../lib/db/db.types";
import { listLocalRdos } from "../../lib/db/rdoRepository";
import { importarRdoArquivo } from "./importRdoExcel";
import { localRecordToDraft } from "./localRecordToDraft";
import { RdoCreatePage } from "./RdoCreatePage";
import { RdoCreationDialog } from "./RdoCreationDialog";
import { RdoLocalList } from "./RdoLocalList";
import type { RdoDraft } from "./rdo.types";
import type { RdoCreationContextLookup } from "./rdoLookupApi";
import "./RdoWorkspacePage.css";
import {
  colaboradorStorageKey,
  setLastAccessedObraId,
} from "../home/lastAccessedObra";

type WorkspaceMode =
  | {
      type: "LIST";
    }
  | {
      type: "FORM";
      draft: RdoDraft;
      isExisting: boolean;
      initialNotice?: string;
      creationContext?: RdoCreationContextLookup;
    };

export function RdoWorkspacePage() {
  const createButtonRef = useRef<HTMLButtonElement | null>(null);
  const [mode, setMode] =
    useState<WorkspaceMode>({
      type: "LIST",
    });

  const [records, setRecords] =
    useState<LocalRdoRecord[]>([]);
  const [events, setEvents] =
    useState<OperationalEventRecord[]>([]);
  const [attachments, setAttachments] =
    useState<RdoAttachmentRecord[]>([]);

  const [isLoading, setIsLoading] =
    useState(true);
  const [isImporting, setIsImporting] =
    useState(false);
  const [isCreationDialogOpen, setIsCreationDialogOpen] =
    useState(false);
  const [pendingImport, setPendingImport] = useState<{
    draft: RdoDraft;
    notice: string;
  } | null>(null);

  const [loadError, setLoadError] =
    useState("");

  const loadRecords =
    useCallback(async () => {
      setIsLoading(true);
      setLoadError("");

      try {
        const [
          localRecords,
          localEvents,
          localAttachments,
        ] = await Promise.all([
          listLocalRdos(),
          listOperationalEvents(),
          listAllRdoAttachments(),
        ]);

        setRecords(localRecords);
        setEvents(localEvents);
        setAttachments(localAttachments);
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
    setPendingImport(null);
    setIsCreationDialogOpen(true);
  }

  function handleOpen(
    record: LocalRdoRecord,
  ) {
    setLastAccessedObraId(
      colaboradorStorageKey(getSession()),
      record.obraId,
    );

    setMode({
      type: "FORM",
      draft:
        localRecordToDraft(record),
      isExisting: true,
    });
  }

  async function handleImportRdoFile(file: File) {
    setIsImporting(true);
    setLoadError("");

    try {
      const session = getSession();
      const imported = await importarRdoArquivo(
        file,
        session?.nome ?? "",
      );

      const notice = [
        imported.summary,
        ...imported.warnings,
      ].join(" ");
      if (
        !imported.draft.obraId ||
        imported.draft.creationContextVersion === null
      ) {
        setPendingImport({ draft: imported.draft, notice });
        setIsCreationDialogOpen(true);
      } else {
        setMode({
          type: "FORM",
          draft: imported.draft,
          isExisting: false,
          initialNotice: notice,
        });
      }
    } catch (error: unknown) {
      setLoadError(
        error instanceof Error
          ? error.message
          : "Falha ao importar o arquivo de RDO.",
      );
    } finally {
      setIsImporting(false);
    }
  }

  async function handleBackToList() {
    await loadRecords();

    setMode({
      type: "LIST",
    });
  }

  if (mode.type === "FORM") {
    return (
      <CortexShell active="rdos">
        <RdoCreatePage
          key={mode.draft.id}
          initialDraft={mode.draft}
          isExisting={mode.isExisting}
          initialNotice={mode.initialNotice}
          creationContext={mode.creationContext}
          onBackToList={() => {
            void handleBackToList();
          }}
          onSaved={(savedObraId) => {
            if (savedObraId) {
              setLastAccessedObraId(
                colaboradorStorageKey(getSession()),
                savedObraId,
              );
            }
            void loadRecords();
          }}
        />
      </CortexShell>
    );
  }

  return (
    <CortexShell
      active="rdos"
      onRefresh={() => {
        void loadRecords();
      }}
      isRefreshing={isLoading}
    >
      <RdoLocalList
        records={records}
        events={events}
        attachments={attachments}
        isLoading={isLoading}
        error={loadError}
        onCreate={handleCreate}
        onImportRdoFile={(file) => {
          void handleImportRdoFile(file);
        }}
        isImporting={isImporting}
        onOpen={handleOpen}
        onRefresh={() => {
          void loadRecords();
        }}
        createButtonRef={createButtonRef}
      />
      {isCreationDialogOpen ? (
        <RdoCreationDialog
          returnFocusRef={createButtonRef}
          initialDraft={pendingImport?.draft}
          onClose={() => {
            setIsCreationDialogOpen(false);
            setPendingImport(null);
          }}
          onCreated={(draft, creationContext) => {
            setIsCreationDialogOpen(false);
            setMode({
              type: "FORM",
              draft,
              isExisting: true,
              initialNotice:
                [
                  pendingImport?.notice,
                  "Rascunho local persistido e incluído na fila de sincronização.",
                ]
                  .filter(Boolean)
                  .join(" "),
              creationContext,
            });
            setPendingImport(null);
          }}
        />
      ) : null}
    </CortexShell>
  );
}
