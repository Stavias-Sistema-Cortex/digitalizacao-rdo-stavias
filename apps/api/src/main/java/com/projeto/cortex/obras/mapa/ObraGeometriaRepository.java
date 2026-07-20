package com.projeto.cortex.obras.mapa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface ObraGeometriaRepository extends JpaRepository<ObraGeometria, String> {

    List<ObraGeometria> findByObraIdAndStatusOrderByValidoDesdeAscIdAsc(
            String obraId,
            String status
    );

    Optional<ObraGeometria> findByIdAndObraId(String id, String obraId);
}
