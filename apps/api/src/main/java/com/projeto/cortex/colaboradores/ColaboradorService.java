package com.projeto.cortex.colaboradores;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ColaboradorService {

    private final ColaboradorRepository colaboradorRepository;

    public ColaboradorService(ColaboradorRepository colaboradorRepository) {
        this.colaboradorRepository = colaboradorRepository;
    }

    public List<ColaboradorResponse> listarColaboradores(String query) {
        List<Colaborador> colaboradores;

        if (query == null || query.isBlank()) {
            colaboradores = colaboradorRepository.findByDeletadoEmIsNullOrderByNomeAsc();
        } else {
            colaboradores = colaboradorRepository.buscarColaboradoresAtivos(query.trim());
        }

        return colaboradores.stream()
                .map(ColaboradorResponse::from)
                .toList();
    }
}
