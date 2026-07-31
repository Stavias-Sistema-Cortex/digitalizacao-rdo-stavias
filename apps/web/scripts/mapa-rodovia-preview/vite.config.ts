import { fileURLToPath } from "node:url";

import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

const here = (relative: string) =>
  fileURLToPath(new URL(relative, import.meta.url));

/**
 * Configuração exclusiva do harness de verificação visual.
 *
 * O build de produção da PWA continua com `apps/web/index.html` como única
 * entrada, então nada deste diretório entra em `dist`. Os dois módulos de
 * acesso a dados são trocados por fixtures para que os componentes reais possam
 * ser medidos sem sessão, API ou banco.
 */
/**
 * Os componentes importam suas camadas de dados por caminho relativo, que um
 * alias de especificador não alcança. O redirecionamento acontece depois da
 * resolução, comparando o arquivo realmente resolvido.
 */
const SUBSTITUICOES: ReadonlyArray<readonly [string, string]> = [
  [
    "src/features/obras/map/obraMapApi.ts",
    here("./obraMapApiFixture.ts"),
  ],
  [
    "src/features/obras/trecho/obraTrechoApi.ts",
    here("./obraTrechoApiFixture.ts"),
  ],
  [
    "src/features/financeiro/financeCapabilitiesResolver.ts",
    here("./financeCapabilitiesFixture.ts"),
  ],
  [
    "src/features/financeiro/revenueTraceCacheRepository.ts",
    here("./revenueTraceFixture.ts"),
  ],
];

function fixtureDataLayer() {
  return {
    name: "mapa-rodovia-preview-fixtures",
    enforce: "pre" as const,
    async resolveId(source: string, importer: string | undefined) {
      const resolved = await this.resolve(source, importer, {
        skipSelf: true,
      });
      if (!resolved) return null;
      const normalized = resolved.id.split("\\").join("/");
      for (const [suffix, replacement] of SUBSTITUICOES) {
        if (normalized.endsWith(suffix)) {
          return replacement;
        }
      }
      return null;
    },
  };
}

export default defineConfig({
  root: here("."),
  plugins: [fixtureDataLayer(), react()],
  server: {
    host: "127.0.0.1",
    port: 5199,
    strictPort: true,
  },
});
