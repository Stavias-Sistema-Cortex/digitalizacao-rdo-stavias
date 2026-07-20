import { describe, expect, it } from "vitest";

import {
  SIDEBAR_WIDTH_DEFAULT,
  SIDEBAR_WIDTH_MAX,
  SIDEBAR_WIDTH_MIN,
  clampSidebarWidth,
  readStoredSidebarWidth,
} from "./sidebarWidth";

describe("clampSidebarWidth", () => {
  it("mantém larguras dentro dos limites", () => {
    expect(clampSidebarWidth(248)).toBe(248);
  });

  it("limita abaixo do mínimo", () => {
    expect(clampSidebarWidth(120)).toBe(SIDEBAR_WIDTH_MIN);
  });

  it("limita acima do máximo", () => {
    expect(clampSidebarWidth(900)).toBe(SIDEBAR_WIDTH_MAX);
  });

  it("arredonda valores fracionários", () => {
    expect(clampSidebarWidth(250.6)).toBe(251);
  });

  it("volta ao padrão para valores não finitos", () => {
    expect(clampSidebarWidth(Number.NaN)).toBe(SIDEBAR_WIDTH_DEFAULT);
    expect(clampSidebarWidth(Infinity)).toBe(SIDEBAR_WIDTH_DEFAULT);
  });
});

describe("readStoredSidebarWidth", () => {
  it("usa o padrão sem valor salvo", () => {
    expect(readStoredSidebarWidth(null)).toBe(SIDEBAR_WIDTH_DEFAULT);
  });

  it("usa o padrão para texto vazio ou inválido", () => {
    expect(readStoredSidebarWidth("")).toBe(SIDEBAR_WIDTH_DEFAULT);
    expect(readStoredSidebarWidth("  ")).toBe(SIDEBAR_WIDTH_DEFAULT);
    expect(readStoredSidebarWidth("abc")).toBe(SIDEBAR_WIDTH_DEFAULT);
  });

  it("lê e limita o valor salvo", () => {
    expect(readStoredSidebarWidth("320")).toBe(320);
    expect(readStoredSidebarWidth("50")).toBe(SIDEBAR_WIDTH_MIN);
    expect(readStoredSidebarWidth("9999")).toBe(SIDEBAR_WIDTH_MAX);
  });
});
