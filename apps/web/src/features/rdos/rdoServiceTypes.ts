export interface RdoServiceType {
  catalogId: string;
  code: string;
  name: string;
  displayName: string;
  level: number;
  parentCode: string | null;
  parentName: string | null;
  searchText: string;
}

const SERVICE_TYPE_ROWS: Array<[string, string]> = [
  ["1", "Tronco"],
  ["1.1", "Fresagem e Recomposicao (Diurno)"],
  ["1.1.1", "Microfresagem"],
  ["1.1.1.1", "Microfresagem do pavimento"],
  ["1.1.2", "Fresagem Fina"],
  ["1.1.2.1", "Fresagem Fina"],
  ["1.1.3", "Fresagem Funcional CBUQ 16,0 mm"],
  ["1.1.3.1", "Fresagem"],
  ["1.1.3.2", "Pintura de ligacao"],
  [
    "1.1.3.3",
    "Concreto betuminoso usinado a quente (CBUQ) SPV 16 mm",
  ],
  ["1.1.3", "Fresagem Funcional CBUQ 16,0 mm c/borracha"],
  ["1.1.3.1", "Fresagem"],
  ["1.1.3.2", "Pintura de ligacao"],
  [
    "1.1.3.3",
    "Concreto betuminoso usinado a quente (CBUQ) SPV 16 mm Modificado por Borracha",
  ],
  ["1.1.4", "Fresagem Estrutural CBUQ 16,0 mm c/borracha"],
  ["1.1.4.1", "Fresagem"],
  ["1.1.4.2", "Pintura de ligacao"],
  [
    "1.1.4.3",
    "Concreto betuminoso usinado a quente (CBUQ) SPV 16 mm Modificado por Borracha",
  ],
  ["1.2", "Camada de Revestimento (Diurno)"],
  ["1.2.1", "Gap Graded"],
  ["1.2.1.1", "Imprimadura ligante Modificada por Polimero"],
  ["1.2.1.2", "Gap Graded"],
  ["1.2.2", "Concreto Asfaltico"],
  ["1.2.2.1", "Imprimadura ligante"],
  [
    "1.2.2.2",
    "Concreto betuminoso usinado a quente (CBUQ) SPV 16 mm Modificado por Borracha",
  ],
  ["1.2.3", "Pre-Misturado a Quente"],
  ["1.2.3.1", "Imprimadura Ligante"],
  ["1.2.3.2", "Pre-Misturado a Quente"],
  ["1.3", "Fresagem e Recomposicao (Noturno)"],
  ["1.3.1", "Microfresagem"],
  ["1.3.1.1", "Microfresagem do pavimento"],
  ["1.3.2", "Fresagem Fina"],
  ["1.3.2.1", "Fresagem Fina"],
  ["1.3.3", "Fresagem Funcional CBUQ 16,0 mm"],
  ["1.3.3.1", "Fresagem"],
  ["1.3.3.2", "Pintura de ligacao"],
  [
    "1.3.3.3",
    "Concreto betuminoso usinado a quente (CBUQ) SPV 16 mm",
  ],
  ["1.3.4", "Fresagem Funcional CBUQ 16,0 mm c/borracha"],
  ["1.3.4.1", "Fresagem"],
  ["1.3.4.2", "Pintura de ligacao"],
  [
    "1.3.4.3",
    "Concreto betuminoso usinado a quente (CBUQ) SPV 16 mm Modificado por Borracha",
  ],
  ["1.3.5", "Fresagem Estrutural CBUQ 16,0 mm c/borracha"],
  ["1.3.5.1", "Fresagem"],
  ["1.3.5.2", "Pintura de ligacao"],
  [
    "1.3.5.3",
    "Concreto betuminoso usinado a quente (CBUQ) SPV 16 mm Modificado por Borracha",
  ],
  ["1.4", "Camada de Revestimento (Noturno)"],
  ["1.4.1", "Gap Graded"],
  ["1.4.1.1", "Imprimadura ligante Modificada por Polimero"],
  ["1.4.1.2", "Gap Graded"],
  ["1.4.2", "Concreto Asfaltico"],
  ["1.4.2.1", "Imprimadura ligante"],
  [
    "1.4.2.2",
    "Concreto betuminoso usinado a quente (CBUQ) SPV 16 mm Modificado por Borracha",
  ],
  ["1.4.3", "Pre-Misturado a Quente"],
  ["1.4.3.1", "Imprimadura ligante"],
  ["1.4.3.2", "Pre-Misturado a Quente"],
  ["1.5", "Reconstrucao e Reparo Estrutural"],
  ["1.5.1", "Reparo Profundo"],
  ["1.5.1.1", "Fresagem descontinua do pavimento"],
  ["1.5.1.2", "Remocao de Pavimento"],
  ["1.5.1.3", "Regularizacao do subleito"],
  ["1.5.1.4", "Macadame seco"],
  ["1.5.1.5", "Brita graduada simples"],
  ["1.5.1.6", "Imprimadura impermeabilizante - EAI"],
  ["1.5.1.7", "Imprimadura ligante"],
  [
    "1.5.1.8",
    "Concreto betuminoso usinado a quente (CBUQ) SPV 16 mm Modificado por Borracha",
  ],
  ["1.5.2", "Reconstrucao"],
  ["1.5.2.1", "Fresagem descontinua do pavimento"],
  ["1.5.2.2", "Remocao de Pavimento"],
  ["1.5.2.3", "Regularizacao do subleito"],
  ["1.5.2.4", "Macadame seco"],
  ["1.5.2.5", "Brita graduada tratada com cimento"],
  ["1.5.2.6", "Imprimadura impermeabilizante - EAI"],
  ["1.5.2.7", "Imprimadura ligante"],
  [
    "1.5.2.8",
    "Concreto betuminoso usinado a quente (CBUQ) SPV 16 mm Modificado por Borracha",
  ],
  ["1.6", "Drenagem"],
  ["1.6.1", "Drenagem do pavimento"],
  [
    "1.6.1.1",
    "Dreno profundo longitudinal tipo DPL - com tubo dreno O 4",
  ],
  [
    "1.6.1.2",
    "Dreno transversal profundo DTP - com tubo dreno O 4",
  ],
  ["1.6.1.3", "Boca de saida para dreno - BSD-1"],
  ["1.7", "Sinalizacao Horizontal"],
  ["1.7.1", "Sinalizacao Horizontal Provisoria"],
  ["1.7.1.1", "Sinalizacao horizontal acrilica provisoria"],
  [
    "1.7.1.2",
    "Tacha refletiva monodirecional plastica provisoria",
  ],
  ["2", "Marginais"],
  ["2.1", "Fresagem e Recomposicao"],
  ["2.1.1", "Microfresagem"],
  ["2.1.1.1", "Microfresagem do pavimento"],
  ["2.1.2", "Fresagem Funcional CBUQ 16,0 mm"],
  ["2.1.2.1", "Fresagem"],
  ["2.1.2.2", "Pintura de ligacao"],
  [
    "2.1.2.3",
    "Concreto betuminoso usinado a quente (CBUQ) SPV 16 mm",
  ],
  ["2.2", "Camada de Revestimento"],
  ["2.2.1", "Gap Graded"],
  ["2.2.1.1", "Imprimadura ligante Modificada por Polimero"],
  ["2.2.1.2", "Gap Graded"],
  ["2.3", "Sinalizacao Horizontal"],
  ["2.3.1", "Sinalizacao Horizontal Provisoria"],
  ["2.3.1.1", "Sinalizacao horizontal acrilica provisoria"],
  [
    "2.3.1.2",
    "Tacha refletiva monodirecional plastica provisoria",
  ],
  ["3", "Dispositivos"],
  ["3.1", "Fresagem e Recomposicao"],
  ["3.1.1", "Microfresagem"],
  ["3.1.1.1", "Microfresagem do pavimento"],
  ["3.1.2", "Fresagem Funcional CBUQ 16,0 mm"],
  ["3.1.2.1", "Fresagem"],
  ["3.1.2.2", "Pintura de ligacao"],
  [
    "3.1.2.3",
    "Concreto betuminoso usinado a quente (CBUQ) SPV 16 mm",
  ],
  ["3.2", "Camada de Revestimento"],
  ["3.2.1", "Gap Graded"],
  ["3.2.1.1", "Imprimadura ligante Modificada por Polimero"],
  ["3.2.1.2", "Gap Graded"],
  ["3.3", "Sinalizacao Horizontal"],
  ["3.3.1", "Sinalizacao Horizontal Provisoria"],
  ["3.3.1.1", "Sinalizacao horizontal acrilica provisoria"],
  [
    "3.3.1.2",
    "Tacha refletiva monodirecional plastica provisoria",
  ],
  ["4.0", "Descontos/ Reembolsos"],
  ["4.1", "Controle de Qualidade"],
  ["4.1.1", "Descontos Controle de Qualidade ( Pay Factor )"],
  ["4.1.2", "Descontos de CAP"],
  ["4.2", "Retrabalho Pay Factor"],
  ["4.2.1", "Reembolso Pay Factor"],
];

function normalizeSearchText(value: string): string {
  return value
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .trim();
}

function parentCode(code: string): string | null {
  const lastDot = code.lastIndexOf(".");

  if (lastDot < 0) {
    return null;
  }

  return code.slice(0, lastDot);
}

const parentNameByCode = new Map<string, string>();

for (const [code, name] of SERVICE_TYPE_ROWS) {
  if (!parentNameByCode.has(code)) {
    parentNameByCode.set(code, name);
  }
}

export const RDO_SERVICE_TYPES: RdoServiceType[] =
  SERVICE_TYPE_ROWS.map(([code, name], index) => {
    const parent = parentCode(code);
    const displayName = `${code} - ${name}`;
    const parentName = parent
      ? parentNameByCode.get(parent) ?? null
      : null;

    return {
      catalogId: `${index + 1}:${code}:${name}`,
      code,
      name,
      displayName,
      level: code.split(".").length,
      parentCode: parent,
      parentName,
      searchText: normalizeSearchText(
        [code, name, parentName].filter(Boolean).join(" "),
      ),
    };
  });

export function formatRdoServiceType(
  serviceType: RdoServiceType,
): string {
  return serviceType.displayName;
}

export function searchRdoServiceTypes(
  query: string,
  limit = 40,
): RdoServiceType[] {
  const terms = normalizeSearchText(query)
    .split(/\s+/)
    .filter(Boolean);

  if (terms.length === 0) {
    return RDO_SERVICE_TYPES.slice(0, limit);
  }

  return RDO_SERVICE_TYPES.filter((serviceType) =>
    terms.every((term) => serviceType.searchText.includes(term)),
  ).slice(0, limit);
}
