package com.projeto.cortex.auth;

import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Tombstone do fluxo legado de CPF como senha. O SHA legado pode localizar
 * uma identidade durante a transição, mas nunca comprovar autenticação.
 */
@Service
public class AuthService {

    public Optional<LoginResponse> autenticarPorCpf(
            String cpfRaw,
            String senhaRaw
    ) {
        return Optional.empty();
    }
}
