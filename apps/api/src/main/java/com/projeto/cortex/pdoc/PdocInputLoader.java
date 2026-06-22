package com.projeto.cortex.pdoc;

import com.projeto.cortex.obras.Obra;

import java.time.LocalDate;

public interface PdocInputLoader {

    PdocInputBundle load(Obra obra, LocalDate requestedReferenceDate);
}
