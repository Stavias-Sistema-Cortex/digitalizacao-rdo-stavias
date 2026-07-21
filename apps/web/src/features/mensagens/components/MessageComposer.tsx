import { useEffect, useRef, type FormEvent } from "react";

import { IconImage, IconPaperclip, IconSend, IconSpinner } from "./icons";

export interface MessageComposerProps {
  value: string;
  files: File[];
  sending: boolean;
  isOnline: boolean;
  onChange: (value: string) => void;
  onFilesChange: (files: File[]) => void;
  onRemoveFile: (index: number) => void;
  onSubmit: (event: FormEvent) => void;
}

export function MessageComposer(props: MessageComposerProps) {
  const fileInput = useRef<HTMLInputElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, 140)}px`;
  }, [props.value]);

  useEffect(() => {
    if (props.files.length === 0 && fileInput.current) {
      fileInput.current.value = "";
    }
  }, [props.files.length]);

  return (
    <form className="mensagens-composer" onSubmit={props.onSubmit}>
      {props.files.length > 0 ? (
        <ul className="mensagens-file-preview">
          {props.files.map((file, index) => (
            <li key={`${file.name}-${file.lastModified}`}>
              <span>{file.name}</span>
              <button type="button" onClick={() => props.onRemoveFile(index)}>
                Remover
              </button>
            </li>
          ))}
        </ul>
      ) : null}
      <div className="mensagens-composer-bar">
        <textarea
          ref={textareaRef}
          id="mensagem-body"
          value={props.value}
          onChange={(event) => props.onChange(event.target.value)}
          onKeyDown={(event) => {
            if (
              event.key === "Enter" &&
              !event.shiftKey &&
              !event.nativeEvent.isComposing
            ) {
              event.preventDefault();
              event.currentTarget.form?.requestSubmit();
            }
          }}
          placeholder="Mensagem"
          rows={1}
        />
        <label className="mensagens-attach" aria-label="Anexar foto">
          <input
            type="file"
            accept="image/*"
            multiple
            onChange={(event) => props.onFilesChange(Array.from(event.target.files ?? []))}
          />
          <IconImage />
        </label>
        <label className="mensagens-attach" aria-label="Anexar arquivos">
          <input
            ref={fileInput}
            type="file"
            multiple
            onChange={(event) => props.onFilesChange(Array.from(event.target.files ?? []))}
          />
          <IconPaperclip />
        </label>
        <button
          type="submit"
          className="mensagens-send"
          disabled={props.sending || (!props.value.trim() && props.files.length === 0)}
          aria-label="Enviar mensagem"
        >
          {props.sending ? <IconSpinner /> : <IconSend />}
        </button>
      </div>
      {!props.isOnline ? (
        <span className="mensagens-composer-hint">
          Offline — a mensagem será enviada quando reconectar.
        </span>
      ) : null}
    </form>
  );
}
