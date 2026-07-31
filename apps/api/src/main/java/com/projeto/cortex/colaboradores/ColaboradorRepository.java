package com.projeto.cortex.colaboradores;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ColaboradorRepository extends JpaRepository<Colaborador, String> {

    List<Colaborador> findByDeletadoEmIsNullOrderByNomeAsc();

    List<Colaborador> findByCpfHashAndAtivoTrueAndDeletadoEmIsNull(
            String cpfHash
    );

    @Query("""
            SELECT c
            FROM Colaborador c
            WHERE c.deletadoEm IS NULL
              AND (
                   LOWER(c.codigoColaborador) LIKE LOWER(CONCAT('%', :query, '%'))
                OR LOWER(c.nome) LIKE LOWER(CONCAT('%', :query, '%'))
                OR LOWER(c.email) LIKE LOWER(CONCAT('%', :query, '%'))
                OR LOWER(c.nomeGrupo) LIKE LOWER(CONCAT('%', :query, '%'))
                OR LOWER(c.nomePerfil) LIKE LOWER(CONCAT('%', :query, '%'))
              )
            ORDER BY c.nome ASC
            """)
    List<Colaborador> buscarColaboradoresAtivos(@Param("query") String query);
}
