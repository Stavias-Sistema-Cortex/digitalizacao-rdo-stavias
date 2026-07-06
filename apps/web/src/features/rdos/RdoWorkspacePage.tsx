import {
  useCallback,
  useEffect,
  useState,
} from "react";

import { SyncStatusBanner } from "../../components/SyncStatusBanner";
import { clearSession, getSession } from "../auth/authSession";
import { IntegracoesPage } from "../integracoes/IntegracoesPage";
import { StaviaPanel } from "../stavia/StaviaPanel";
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
import { createEmptyRdo } from "./createEmptyRdo";
import { importarRdoArquivo } from "./importRdoExcel";
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
      initialNotice?: string;
    };

export function RdoWorkspacePage() {
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

  const [loadError, setLoadError] =
    useState("");
  const [isStaviaOpen, setIsStaviaOpen] =
    useState(false);
  const [staviaContext, setStaviaContext] = useState({
    obraId: "",
    rdoId: "",
  });

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

  async function handleImportRdoFile(file: File) {
    setIsImporting(true);
    setLoadError("");

    try {
      const session = getSession();
      const imported = await importarRdoArquivo(
        file,
        session?.nome ??
          session?.cpfMascarado ??
          "",
      );

      setMode({
        type: "FORM",
        draft: imported.draft,
        isExisting: false,
        initialNotice: [
          imported.summary,
          ...imported.warnings,
        ].join(" "),
      });
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

  function handleLogout() {
    clearSession();
    window.location.assign("/");
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
        initialNotice={mode.initialNotice}
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
        onOpenStavia={(context) => {
          setStaviaContext({
            obraId: context?.obraId ?? "",
            rdoId: context?.rdoId ?? "",
          });
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
      <div className="floating-controls">
        <SyncStatusBanner />
        <button
          type="button"
          className="logout-button"
          onClick={handleLogout}
        >
          Sair
        </button>
      </div>
      {pageContent}
      {mode.type !== "INTEGRACOES" && (
        <StaviaPanel
          key={mode.type === "FORM"
            ? `${mode.draft.obraId}:${mode.draft.id}`
            : `stavia-floating-global:${staviaContext.obraId}:${staviaContext.rdoId}`}
          variant="floating"
          isOpen={isStaviaOpen}
          onOpenChange={setIsStaviaOpen}
          initialObraId={
            mode.type === "FORM"
              ? mode.draft.obraId
              : staviaContext.obraId
          }
          initialRdoId={
            mode.type === "FORM"
              ? mode.draft.id
              : staviaContext.rdoId
          }
        />
      )}
    </>
  );
}
