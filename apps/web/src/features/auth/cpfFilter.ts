import { onlyDigits } from "./loginValidation";

/**
 * Filtro de Bloom dos CPFs ativos, servido por GET /api/auth/cpf-filter.
 * Permite validar o login offline sem expor a lista de CPFs. O esquema
 * (sha256-double/v1) é idêntico ao backend (CpfBloomFilter.java) e foi
 * verificado por teste de paridade entre Java e JavaScript.
 */
export type CpfFilter = {
  algoritmo: string;
  m: number;
  k: number;
  tamanho: number;
  bits: string;
  geradoEm: string;
};

const FILTER_KEY = "cortex.auth.cpfFilter";

export function getCachedFilter(): CpfFilter | null {
  try {
    const raw = localStorage.getItem(FILTER_KEY);
    if (!raw) {
      return null;
    }

    const parsed = JSON.parse(raw) as CpfFilter;
    if (
      typeof parsed?.m !== "number" ||
      typeof parsed?.k !== "number" ||
      typeof parsed?.bits !== "string"
    ) {
      return null;
    }

    return parsed;
  } catch {
    return null;
  }
}

export function setCachedFilter(filter: CpfFilter): void {
  try {
    localStorage.setItem(FILTER_KEY, JSON.stringify(filter));
  } catch {
    /* armazenamento indisponível: ignora */
  }
}

function base64ToBytes(base64: string): Uint8Array {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

function uint32(bytes: Uint8Array, offset: number): number {
  return (
    ((bytes[offset] << 24) |
      (bytes[offset + 1] << 16) |
      (bytes[offset + 2] << 8) |
      bytes[offset + 3]) >>>
    0
  );
}

/**
 * true  = CPF possivelmente válido (presente no filtro);
 * false = CPF definitivamente ausente da Academy.
 */
export async function cpfPodeEstarNoFiltro(
  filter: CpfFilter,
  cpf: string,
): Promise<boolean> {
  const digits = onlyDigits(cpf);
  if (digits.length !== 11) {
    return false;
  }

  const data = new TextEncoder().encode(digits);
  const digest = await crypto.subtle.digest("SHA-256", data);
  const hash = new Uint8Array(digest);
  const bits = base64ToBytes(filter.bits);

  const h1 = uint32(hash, 0);
  const h2 = (uint32(hash, 4) | 1) >>> 0;

  for (let i = 0; i < filter.k; i += 1) {
    const idx = (h1 + i * h2) % filter.m;
    if ((bits[idx >>> 3] & (1 << (idx & 7))) === 0) {
      return false;
    }
  }

  return true;
}
