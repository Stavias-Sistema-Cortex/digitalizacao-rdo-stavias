#!/usr/bin/env bash

# Normal CPF/passkey runtime must not inherit activation or legacy delivery
# configuration from a shared local environment file.
while IFS= read -r cortex_runtime_variable; do
  case "$cortex_runtime_variable" in
    *[Oo][Tt][Pp]*|CORTEX_EMAIL_*|CORTEX_SMTP_*|CORTEX_FINANCE_EMAIL_*)
      unset "$cortex_runtime_variable"
      ;;
  esac
done < <(compgen -e)
unset cortex_runtime_variable
