import { apiFetch, readResponseBody } from "../../lib/api/apiClient";
import { ApiError, apiError } from "../../lib/api/apiError";
import { getCortexDb } from "../../lib/db/cortexDb";
import type { RdoAttachmentRecord } from "../../lib/db/db.types";
import { putRdoAttachment } from "../../lib/db/rdoAttachmentRepository";
import {
  assertSyncSession,
  captureOnlineSyncSession,
  type SyncSessionGuard,
} from "../../lib/sync/syncSession";

/**
 * As fotos do RDO sobem e descem por aqui.
 *
 * <p>A foto sempre foi gravada inteira no IndexedDB do aparelho que
 * fotografou — e só nele. A ficha do anexo subia pelo sync com um endereço
 * sintético ("indexeddb:" + id) que nenhum outro aparelho resolve, era
 * carimbada como sincronizada dos dois lados, e quem abria o mesmo RDO em
 * outra máquina via o cartão da foto sem a foto.
 *
 * <p>O desenho é deliberadamente desacoplado da fila de mutações. O binário
 * sobe pela rota genérica de objetos — a mesma dos anexos de mensagem — e um
 * vínculo idempotente o amarra ao anexo no servidor. Nada disso cita mutação,
 * depende de mutação ou bloqueia mutação: a auditoria já mostrou o que
 * acontece quando um upload e uma mutação vivem amarrados e um dos dois morre
 * — a citação fantasma que condena a outra ponta ao reenvio eterno. Aqui, se
 * o RDO ainda não chegou ao servidor, o vínculo responde 404 e a foto apenas
 * espera o próximo ciclo; se a foto ainda não subiu, o RDO sobe sem ela e o
 * cartão diz a verdade.
 */

/** Quantas fotos sobem por ciclo: o ciclo continua curto e o resto espera. */
const FOTOS_POR_CICLO = 3;

interface ResultadoDeUploadDeFotos {
  enviadas: number;
  falhas: number;
}

function hex(bytes: ArrayBuffer): string {
  return Array.from(new Uint8Array(bytes), (byte) =>
    byte.toString(16).padStart(2, "0"),
  ).join("");
}

async function sha256DoBlob(blob: Blob): Promise<string> {
  return hex(await crypto.subtle.digest("SHA-256", await blob.arrayBuffer()));
}

/**
 * A recusa que não muda com o tempo não merece nova tentativa.
 *
 * <p>O 404 fica de fora de propósito: ele é o servidor dizendo que o RDO — ou
 * a ficha do anexo — ainda não chegou lá, e isso o próximo ciclo resolve
 * sozinho, depois que o sync do RDO passar.
 */
function recusaDefinitiva(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    error.status !== 404 &&
    error.status >= 400 &&
    error.status < 500 &&
    error.status !== 408 &&
    error.status !== 429
  );
}

/**
 * Sobe as fotos que ainda não têm morada no servidor e amarra cada uma ao seu
 * anexo. Roda dentro do ciclo de sincronização, com o guard da sessão.
 */
export async function processRdoPhotoUploads(
  guard: SyncSessionGuard = captureOnlineSyncSession(),
): Promise<ResultadoDeUploadDeFotos> {
  assertSyncSession(guard);
  const database = await getCortexDb();
  assertSyncSession(guard);

  const todas = await database.getAll("rdo_attachments");
  const pendentes = todas.filter(
    (attachment) =>
      attachment.arquivo instanceof Blob &&
      !attachment.storedObjectId &&
      !attachment.removedAt &&
      attachment.syncStatus !== "SYNC_FAILED",
  );

  const resultado: ResultadoDeUploadDeFotos = { enviadas: 0, falhas: 0 };
  for (const attachment of pendentes.slice(0, FOTOS_POR_CICLO)) {
    assertSyncSession(guard);
    /*
     * A foto só sobe depois de o RDO existir no servidor: o vínculo precisa
     * da ficha do anexo lá, e a ficha chega com o sync do RDO. Subir antes
     * gastaria o upload para receber 404 no vínculo logo em seguida.
     */
    const rdo = await database.get("rdos", attachment.rdoId);
    if (!rdo || rdo.versaoEntidade === null) continue;
    const obraId = attachment.obraId ?? rdo.obraId;
    if (!obraId) continue;

    try {
      const sha256 =
        attachment.sha256 ?? (await sha256DoBlob(attachment.arquivo as Blob));
      assertSyncSession(guard);

      const form = new FormData();
      form.append("arquivo", attachment.arquivo as Blob, attachment.nome);
      const upload = await apiFetch(
        `/objetos?obraId=${encodeURIComponent(obraId)}`,
        {
          method: "POST",
          body: form,
          timeoutMs: 60_000,
          connectionErrorMessage:
            "Não foi possível enviar a foto. Ela continua salva neste dispositivo.",
          timeoutErrorMessage:
            "O envio da foto demorou demais. Ela continua salva neste dispositivo.",
        },
      );
      assertSyncSession(guard);
      const corpo = await readResponseBody(upload);
      if (!upload.ok) throw apiError(corpo, upload.status);
      const objeto = corpo as { id?: unknown; sha256?: unknown };
      if (typeof objeto.id !== "string" || objeto.sha256 !== sha256) {
        throw new Error(
          "O servidor devolveu um objeto que não corresponde à foto enviada.",
        );
      }

      const vinculo = await apiFetch(
        `/rdos/${encodeURIComponent(attachment.rdoId)}/anexos/${
          encodeURIComponent(attachment.id)
        }/objeto`,
        {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ objetoId: objeto.id, sha256 }),
          connectionErrorMessage:
            "Não foi possível amarrar a foto ao RDO. Ela continua salva neste dispositivo.",
          timeoutErrorMessage:
            "A amarração da foto demorou demais. Ela continua salva neste dispositivo.",
        },
      );
      assertSyncSession(guard);
      if (!vinculo.ok) {
        throw apiError(await readResponseBody(vinculo), vinculo.status);
      }

      await putRdoAttachment({
        ...attachment,
        storedObjectId: objeto.id,
        sha256,
        syncStatus: "SYNCED",
        ultimoErro: null,
        updatedAt: new Date().toISOString(),
      });
      resultado.enviadas += 1;
    } catch (error: unknown) {
      assertSyncSession(guard);
      resultado.falhas += 1;
      if (recusaDefinitiva(error)) {
        /*
         * SYNC_FAILED tira a foto da fila e acende o erro no registro: uma
         * recusa 4xx voltaria idêntica a cada ciclo, e insistir só gastaria
         * a bateria de quem está no campo. A foto continua no aparelho.
         */
        await putRdoAttachment({
          ...attachment,
          syncStatus: "SYNC_FAILED",
          ultimoErro:
            error instanceof Error
              ? error.message
              : "O envio da foto foi recusado.",
          updatedAt: new Date().toISOString(),
        });
      }
      // Falha transitória fica como está: o próximo ciclo tenta de novo.
    }
  }
  return resultado;
}

/**
 * Semeia no aparelho as fichas de anexo que vieram do servidor.
 *
 * <p>É a metade que faltava no aparelho B: o pull trazia o RDO com a lista de
 * anexos dentro do payload e nenhuma linha era criada em `rdo_attachments` —
 * a tela lia a loja vazia e a foto simplesmente não existia ali. A ficha
 * semeada nasce sem binário ({@code arquivo: null}); o download preenche
 * quando alguém abre.
 *
 * <p>A ficha local com binário nunca é destruída: dela adota-se apenas o
 * {@code storedObjectId} que o servidor confirmou — é assim que o aparelho A
 * fica sabendo que o próprio upload valeu, mesmo quando a resposta do vínculo
 * se perdeu no caminho.
 */
export async function semearFichasDeAnexoDoServidor(
  rdoId: string,
  attachments: unknown,
): Promise<number> {
  if (!Array.isArray(attachments) || attachments.length === 0) return 0;
  const database = await getCortexDb();
  let semeadas = 0;

  for (const bruto of attachments) {
    if (typeof bruto !== "object" || bruto === null) continue;
    const item = bruto as Record<string, unknown>;
    const id = typeof item.id === "string" ? item.id : null;
    if (!id) continue;
    const storedObjectId =
      typeof item.storedObjectId === "string" ? item.storedObjectId : null;
    const removedAt =
      typeof item.removedAt === "string" ? item.removedAt : null;

    const local = await database.get("rdo_attachments", id);
    if (local) {
      const mudouVinculo =
        storedObjectId !== null && local.storedObjectId !== storedObjectId;
      const mudouRemocao = removedAt !== null && !local.removedAt;
      if (!mudouVinculo && !mudouRemocao) continue;
      await database.put("rdo_attachments", {
        ...local,
        storedObjectId: storedObjectId ?? local.storedObjectId ?? null,
        removedAt: removedAt ?? local.removedAt,
        syncStatus: mudouVinculo ? "SYNCED" : local.syncStatus,
        updatedAt: new Date().toISOString(),
      });
      semeadas += 1;
      continue;
    }

    const registro: RdoAttachmentRecord = {
      id,
      rdoId,
      obraId: typeof item.obraId === "string" ? item.obraId : null,
      tipo: "FOTO",
      nome: typeof item.nome === "string" ? item.nome : id,
      nomeOriginal:
        typeof item.nomeOriginal === "string" ? item.nomeOriginal : null,
      mimeType:
        typeof item.mimeType === "string" ? item.mimeType : "image/jpeg",
      tamanhoBytes:
        typeof item.tamanhoBytes === "number" ? item.tamanhoBytes : 0,
      tamanhoOriginalBytes:
        typeof item.tamanhoOriginalBytes === "number"
          ? item.tamanhoOriginalBytes
          : 0,
      tamanhoComprimidoBytes:
        typeof item.tamanhoComprimidoBytes === "number"
          ? item.tamanhoComprimidoBytes
          : 0,
      arquivo: null,
      storedObjectId,
      sha256: null,
      syncStatus: "SYNCED",
      ultimoErro: null,
      metadata:
        typeof item.metadata === "object" && item.metadata !== null
          ? (item.metadata as Record<string, unknown>)
          : {},
      createdAt:
        typeof item.createdAt === "string"
          ? item.createdAt
          : new Date().toISOString(),
      updatedAt:
        typeof item.updatedAt === "string"
          ? item.updatedAt
          : new Date().toISOString(),
      removedAt,
    };
    await database.put("rdo_attachments", registro);
    semeadas += 1;
  }
  return semeadas;
}

/**
 * Garante o binário de uma ficha, baixando do servidor quando preciso.
 *
 * <p>O download é gravado de volta na ficha — diferente do anexo de mensagem,
 * que baixa a cada visualização. Foto de obra é olhada no campo, onde a rede
 * que existia na primeira visualização pode não existir na segunda: depois do
 * primeiro download, a foto é do aparelho.
 */
export async function garantirArquivoDaFoto(
  attachment: RdoAttachmentRecord,
): Promise<Blob | null> {
  if (attachment.arquivo instanceof Blob) return attachment.arquivo;
  if (!attachment.storedObjectId) return null;
  if (typeof navigator !== "undefined" && navigator.onLine === false) {
    return null;
  }

  try {
    const response = await apiFetch(
      `/rdos/${encodeURIComponent(attachment.rdoId)}/anexos/${
        encodeURIComponent(attachment.id)
      }/conteudo`,
      {
        method: "GET",
        timeoutMs: 60_000,
        connectionErrorMessage: "Não foi possível baixar a foto agora.",
        timeoutErrorMessage: "O download da foto demorou demais.",
      },
    );
    if (!response.ok) return null;
    const blob = await response.blob();
    await putRdoAttachment({
      ...attachment,
      arquivo: blob,
      updatedAt: new Date().toISOString(),
    });
    return blob;
  } catch {
    return null;
  }
}
