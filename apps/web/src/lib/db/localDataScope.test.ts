import { describe, expect, it } from "vitest";

import { clearUserScopedLocalStorage } from "./localDataScope";

describe("clearUserScopedLocalStorage", () => {
  it("remove o histórico auxiliar da StavIA ao encerrar ou trocar a sessão", () => {
    const removed: string[] = [];

    clearUserScopedLocalStorage({
      removeItem(key: string) {
        removed.push(key);
      },
    });

    expect(removed).toEqual([
      "cortex:stavia:chat:operacional",
      "cortex:stavia:last-context",
    ]);
  });
});
