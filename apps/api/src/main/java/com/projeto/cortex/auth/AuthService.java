package com.projeto.cortex.auth;

import com.projeto.cortex.colaboradores.Colaborador;
import com.projeto.cortex.colaboradores.ColaboradorRepository;
import java.util.List;
import com.projeto.cortex.colaboradores.CpfHasher;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Autenticação por CPF: valida exatamente contra os colaboradores ativos
 * importados da Academy (login e senha são o CPF do colaborador).
 */
@Service
public class AuthService {

    private final ColaboradorRepository colaboradorRepository;

    public AuthService(ColaboradorRepository colaboradorRepository) {
        this.colaboradorRepository = colaboradorRepository;
    }

    public Optional<LoginResponse> autenticarPorCpf(String cpfRaw, String senhaRaw) {
        String normalizado = CpfHasher.normalizar(cpfRaw);
        String senhaNormalizada = CpfHasher.normalizar(senhaRaw);

        if (normalizado == null
                || normalizado.length() != 11
                || senhaNormalizada == null
                || !normalizado.equals(senhaNormalizada)) {
            return Optional.empty();
        }

        String cpfHash = CpfHasher.hashDeDigitos(normalizado);

        List<Colaborador> matches = colaboradorRepository
                .findByCpfHashAndAtivoTrueAndDeletadoEmIsNull(cpfHash);

        // A base ainda não tem uma restrição UNIQUE histórica para hashes de
        // CPF. Até a higienização dos dados, qualquer ambiguidade falha
        // fechada: escolher o "primeiro" colaborador poderia autenticar a
        // identidade errada.
        if (matches == null || matches.size() != 1) {
            return Optional.empty();
        }

        return Optional.of(toResponse(matches.getFirst()));
    }

    private static LoginResponse toResponse(Colaborador colaborador) {
        return new LoginResponse(
                null,
                colaborador.getId(),
                colaborador.getNome(),
                colaborador.getCpfMascarado(),
                colaborador.getNomePerfil(),
                colaborador.getNomeGrupo(),
                papelEfetivo(colaborador).name()
        );
    }

    private static PapelAcesso papelEfetivo(Colaborador colaborador) {
        PapelAcesso explicito =
                PapelAcesso.fromNullable(colaborador.getPapelAcesso());
        if (explicito != null) {
            return explicito;
        }
        return PapelAcesso.BETA;
    }
}
