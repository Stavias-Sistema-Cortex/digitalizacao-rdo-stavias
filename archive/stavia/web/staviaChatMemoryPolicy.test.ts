import { describe, expect, it } from "vitest";

import {
  STAVIA_CHAT_MEMORY_POLICY_VERSION,
  mustResetStaviaChatHistory,
} from "./staviaChatMemoryPolicy";

describe("StavIA chat memory policy", () => {
  it("invalida respostas em cache anteriores à centralização da Memória", () => {
    expect(mustResetStaviaChatHistory(null)).toBe(true);
    expect(mustResetStaviaChatHistory("legacy-operacional-v1")).toBe(true);
  });

  it("mantém apenas o cache escrito sob a política atual", () => {
    expect(
      mustResetStaviaChatHistory(STAVIA_CHAT_MEMORY_POLICY_VERSION),
    ).toBe(false);
  });
});
