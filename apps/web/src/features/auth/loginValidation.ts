/*
 * Validação do formulário de login do Sistema Córtex.
 * O acesso é feito somente pelo CPF do colaborador (validado na Academy).
 * Mantida fora do componente para facilitar testes e o Fast Refresh do Vite.
 */

export type LoginFieldErrors = {
  cpf?: string;
};

export function onlyDigits(value: string): string {
  return value.replace(/\D/g, "");
}

/** Formata progressivamente como 000.000.000-00. */
export function formatCpf(value: string): string {
  const d = onlyDigits(value).slice(0, 11);

  if (d.length > 9) {
    return `${d.slice(0, 3)}.${d.slice(3, 6)}.${d.slice(6, 9)}-${d.slice(9)}`;
  }
  if (d.length > 6) {
    return `${d.slice(0, 3)}.${d.slice(3, 6)}.${d.slice(6)}`;
  }
  if (d.length > 3) {
    return `${d.slice(0, 3)}.${d.slice(3)}`;
  }
  return d;
}

/** Valida os 11 dígitos do CPF, incluindo os dígitos verificadores. */
export function isValidCpf(value: string): boolean {
  const d = onlyDigits(value);

  if (d.length !== 11 || /^(\d)\1{10}$/.test(d)) {
    return false;
  }

  const digit = (sliceLength: number): number => {
    let sum = 0;
    for (let i = 0; i < sliceLength; i += 1) {
      sum += Number(d[i]) * (sliceLength + 1 - i);
    }
    const rest = (sum * 10) % 11;
    return rest === 10 ? 0 : rest;
  };

  return digit(9) === Number(d[9]) && digit(10) === Number(d[10]);
}

export function validateLoginForm(cpf: string): LoginFieldErrors {
  const errors: LoginFieldErrors = {};
  const cpfDigits = onlyDigits(cpf);

  if (!cpfDigits) {
    errors.cpf = "Informe seu CPF para continuar.";
  } else if (!isValidCpf(cpfDigits)) {
    errors.cpf = "Informe um CPF válido.";
  }

  return errors;
}
