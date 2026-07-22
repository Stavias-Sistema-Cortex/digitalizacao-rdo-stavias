import { describe, expect, it } from "vitest";

import { clearUserScopedLocalStorage } from "./localDataScope";

describe("clearUserScopedLocalStorage", () => {
  it("remove o histórico auxiliar da StavIA ao encerrar ou trocar a sessão", () => {
    const removed: string[] = [];
    const previousWindow = Object.getOwnPropertyDescriptor(globalThis, "window");

    Object.defineProperty(globalThis, "window", {
      configurable: true,
      value: {
        localStorage: {
          removeItem(key: string) {
            removed.push(key);
          },
        },
      },
    });

    try {
      expect(clearUserScopedLocalStorage.length).toBe(0);
      clearUserScopedLocalStorage();
    } finally {
      if (previousWindow) {
        Object.defineProperty(globalThis, "window", previousWindow);
      } else {
        Reflect.deleteProperty(globalThis, "window");
      }
    }

    expect(removed).toEqual([
      "cortex:stavia:chat:operacional",
      "cortex:stavia:last-context",
    ]);
  });
});
