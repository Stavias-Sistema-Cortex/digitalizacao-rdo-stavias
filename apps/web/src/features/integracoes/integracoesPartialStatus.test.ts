import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { describe, expect, it } from "vitest";

import { descricaoDaEspera } from "./integracoesApi";

const page = readFileSync(
  resolve(process.cwd(), "./src/features/integracoes/IntegracoesPage.tsx"),
  "utf8",
);

describe("Academy partial synchronization presentation", () => {
  it("reports a partial import as an explicit partial result, not a failure", () => {
    expect(page).toContain('case "PARTIAL":\n      return "Parcial";');
    expect(page).toContain("Solicitações pendentes");
  });

  it("acompanha o fim da sincronização em vez de esperar um gesto manual", () => {
    // A solicitação sobe na mesma janela de qualquer lançamento; sem isto a
    // tela anunciava como pendente algo já executado pelo servidor.
    expect(page).toContain("SYNC_COMPLETED_EVENT");
    expect(page).toContain("addEventListener");
  });
});

describe("espera exibida da solicitação", () => {
  it("não afirma falta de rede quando o aparelho está conectado", () => {
    // O texto antigo dizia "após a reconexão" mesmo com o computador online,
    // porque exibia cru o código de fila do protocolo. O código continua
    // literal no envelope — o servidor o exige assim; o que mudou é a leitura
    // exibida, agora conferida contra o estado real do aparelho.
    expect(descricaoDaEspera(true)).toBe(
      "na fila; sobe na próxima sincronização",
    );
    expect(descricaoDaEspera(false)).toBe(
      "sem rede agora; sobe sozinha na reconexão",
    );
  });

  it("preserva o código de fila que o servidor exige literal", () => {
    const api = readFileSync(
      resolve(process.cwd(), "./src/features/integracoes/integracoesApi.ts"),
      "utf8",
    );
    expect(api).toContain('motivo: "AGUARDANDO_REDE"');
  });
});
