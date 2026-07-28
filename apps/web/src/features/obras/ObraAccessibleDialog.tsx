import {
  useEffect,
  useRef,
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

  useEffect(() => {
    const currentDialog = dialogRef.current;
    if (!currentDialog) return;
    const dialog: HTMLElement = currentDialog;

    function focusInside(preferLast = false) {
      const focusable = focusableElements(dialog);
      const target = preferLast
        ? focusable.at(-1)
        : focusable[0];
      (target ?? dialog).focus();
    }

    function containsActiveFocus(): boolean {
      const active = document.activeElement;
      return active instanceof HTMLElement &&
        dialog.contains(active) &&
        !active.matches(":disabled");
    }

    function handleFocusIn(event: FocusEvent) {
      if (
        !(event.target instanceof Node) ||
        !dialog.contains(event.target)
      ) {
        focusInside();
      }
    }

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        event.stopPropagation();
        if (closeDisabled) {
          if (!containsActiveFocus()) {
            focusInside();
          }
        } else {
          onClose();
        }
        return;
      }
      if (event.key !== "Tab") return;

      const focusable = focusableElements(dialog);
      const active = document.activeElement;
      const activeIndex = active instanceof HTMLElement
        ? focusable.indexOf(active)
        : -1;
      if (focusable.length === 0) {
        event.preventDefault();
        dialog.focus();
        return;
      }
      if (activeIndex === -1) {
        event.preventDefault();
        focusInside(event.shiftKey);
        return;
      }

      const atBoundary = event.shiftKey
        ? activeIndex === 0
        : activeIndex === focusable.length - 1;
      if (atBoundary) {
        event.preventDefault();
        focusInside(event.shiftKey);
      }
    }

    document.addEventListener("focusin", handleFocusIn, true);
    document.addEventListener("keydown", handleKeyDown, true);
    if (!containsActiveFocus()) {
      focusInside();
    }

    return () => {
      document.removeEventListener("focusin", handleFocusIn, true);
      document.removeEventListener("keydown", handleKeyDown, true);
    };
  }, [closeDisabled, onClose]);

  return (
    <section
      ref={dialogRef}
      className={className}
      role="dialog"
      aria-modal="true"
      aria-labelledby={labelledBy}
      tabIndex={-1}
    >
      {children}
    </section>
  );
}
