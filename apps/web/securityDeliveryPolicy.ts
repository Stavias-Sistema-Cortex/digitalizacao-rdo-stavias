import type { Plugin } from "vite";

const sourceMapReference =
  /(?:\/\/[#@]\s*sourceMappingURL=.*|\/\*[#@]\s*sourceMappingURL=.*?\*\/)\s*(?:\r?\n|$)/g;

export function stripSourceMapReferences(content: string): string {
  return content.replace(sourceMapReference, "");
}

export function stripSourceMapReferencesPlugin(): Plugin {
  return {
    name: "cortex-strip-source-map-references",
    apply: "build",
    enforce: "post",
    generateBundle(_options, bundle) {
      for (const output of Object.values(bundle)) {
        if (output.type === "chunk") {
          output.code = stripSourceMapReferences(output.code);
          continue;
        }

        if (!/\.(?:css|m?js)$/.test(output.fileName)) {
          continue;
        }

        if (typeof output.source === "string") {
          output.source = stripSourceMapReferences(output.source);
          continue;
        }

        const decoded = new TextDecoder().decode(output.source);
        output.source = new TextEncoder().encode(
          stripSourceMapReferences(decoded)
        );
      }
    },
  };
}
