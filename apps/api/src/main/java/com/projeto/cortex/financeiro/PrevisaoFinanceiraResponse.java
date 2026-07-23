package com.projeto.cortex.financeiro;

import com.projeto.cortex.pdor.PdorResultadoResponse;

/**
 * Source-compatible marker for integrations migrating to the canonical PDOR
 * response. New HTTP routes return {@link PdorResultadoResponse} directly.
 */
@Deprecated(forRemoval = false)
public record PrevisaoFinanceiraResponse(
        PdorResultadoResponse pdor,
        String contractVersion
) {
}
