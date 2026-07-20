package com.projeto.cortex.auth;

import com.projeto.cortex.colaboradores.ColaboradorRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Gera o filtro de Bloom dos CPFs ativos para validação de login offline.
 * Sempre derivado dos colaboradores importados da Academy (nada hardcoded).
 */
@Service
public class CpfBloomFilterService {

    private static final double FALSE_POSITIVE_ALVO = 0.001;

    private final ColaboradorRepository colaboradorRepository;

    public CpfBloomFilterService(ColaboradorRepository colaboradorRepository) {
        this.colaboradorRepository = colaboradorRepository;
    }

    public CpfFilterResponse gerarFiltro() {
        List<String> hashesAtivos = colaboradorRepository.findHashesAtivos();
        CpfBloomFilter filtro =
                CpfBloomFilter.construir(hashesAtivos, FALSE_POSITIVE_ALVO);

        return new CpfFilterResponse(
                CpfBloomFilter.ALGORITMO,
                filtro.getM(),
                filtro.getK(),
                hashesAtivos.size(),
                filtro.getBitsBase64(),
                OffsetDateTime.now().toString()
        );
    }
}
