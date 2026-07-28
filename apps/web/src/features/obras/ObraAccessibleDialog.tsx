import {
  useEffect,
  useRef,
  type KeyboardEvent,
  type ReactNode,
  type RefObject,
} from "react";

const FOCUSABLE_SELECTOR = [
  "button:not([disabled])",
  "input:not([disabled])",
  "select:not([disabled])",
  "textarea:not([disabled])",
  "[href]",
  '[tabindex]:not([tabindex="-1"])',
].join(",");

function focusableElements(container: HTMLElement): HTMLElement[] {
  return Array.from(
    container.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR),
  ).filter((element) => !element.hasAttribute("hidden"));
}

function returnFocus(
  primary: HTMLElement | null,
  fallbackRef?: RefObject<HTMLElement | null>,
): void {
  queueMicrotask(() => {
    const target = [primary, fallbackRef?.current].find(
      (element) =>
        element?.isConnected &&
        !element.matches(":disabled"),
    );
    target?.focus();
  });
}

export function ObraAccessibleDialog({
  children,
  className,
  labelledBy,
  initialFocusRef,
  returnFocusRef,
  returnFallbackRef,
  closeDisabled = false,
  onClose,
}: {
  children: ReactNode;
  className: string;
  labelledBy: string;
  initialFocusRef: RefObject<HTMLElement | null>;
  returnFocusRef: RefObject<HTMLElement | null>;
  returnFallbackRef?: RefObject<HTMLElement | null>;
  closeDisabled?: boolean;
  onClose: () => void;
}) {
  const dialogRef = useRef<HTMLElement>(null);

  useEffect(() => {
    const returnFocusTarget = returnFocusRef.current;
    initialFocusRef.current?.focus();
    return () => {
      returnFocus(returnFocusTarget, returnFallbackRef);
    };
  }, [initialFocusRef, returnFallbackRef, returnFocusRef]);

  function handleKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.key === "Escape") {
      event.preventDefault();
      if (!closeDisabled) {
        onClose();
      }
      return;
    }
    if (event.key !== "Tab" || !dialogRef.current) return;

    const focusable = focusableElements(dialogRef.current);
    if (focusable.length === 0) {
      event.preventDefault();
      dialogRef.current.focus();
      return;
    }

    const first = focusable[0];
    const last = focusable.at(-1) as HTMLElement;
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (
      !event.shiftKey &&
      document.activeElement === last
    ) {
      event.preventDefault();
      first.focus();
    }
  }

  return (
    <section
      ref={dialogRef}
      className={className}
      role="dialog"
      aria-modal="true"
      aria-labelledby={labelledBy}
      tabIndex={-1}
      onKeyDown={handleKeyDown}
    >
      {children}
    </section>
  );
}
