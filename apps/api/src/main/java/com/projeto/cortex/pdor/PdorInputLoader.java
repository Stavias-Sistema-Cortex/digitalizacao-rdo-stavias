package com.projeto.cortex.pdor;

import com.projeto.cortex.obras.Obra;

import java.time.LocalDate;

public interface PdorInputLoader {

    PdorInputBundle load(Obra obra, LocalDate requestedReferenceDate);
}
