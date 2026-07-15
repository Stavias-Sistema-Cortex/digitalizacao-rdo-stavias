package com.projeto.cortex.financeiro.invoice.extraction;

import static com.projeto.cortex.financeiro.invoice.extraction.FiscalExtractionDtos.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class XmlFiscalExtractorTest {

    private final XmlFiscalExtractor extractor = new XmlFiscalExtractor();

    @Test
    void extractsNamespacedNfeWithExactEvidenceAndConsistentTotal() {
        String key = validNfeKey();
        FiscalExtractionResult result = extractor.extract(source("""
                <?xml version="1.0" encoding="UTF-8"?>
                <nfeProc xmlns="http://www.portalfiscal.inf.br/nfe">
                  <NFe><infNFe Id="NFe%s">
                    <ide><nNF>125</nNF><serie>3</serie><dhEmi>2026-07-14T09:30:00-03:00</dhEmi></ide>
                    <emit><CNPJ>12345678000195</CNPJ><xNome>Fornecedor Real</xNome></emit>
                    <total><ICMSTot>
                      <vProd>100.00</vProd><vFrete>5.00</vFrete><vSeg>0.00</vSeg>
                      <vDesc>10.00</vDesc><vII>0.00</vII><vIPI>0.00</vIPI>
                      <vST>0.00</vST><vOutro>0.00</vOutro><vNF>95.00</vNF>
                    </ICMSTot></total>
                  </infNFe></NFe>
                </nfeProc>
                """.formatted(key)));

        assertThat(result.status()).isEqualTo(ExtractionStatus.EXTRAIDO);
        assertThat(result.autorizacaoFiscal())
                .isEqualTo("NAO_VERIFICADA");
        assertThat(result.candidate("numero").orElseThrow().normalizedValue().asText())
                .isEqualTo("125");
        assertThat(result.candidate("tipoDocumento").orElseThrow()
                .normalizedValue().asText()).isEqualTo("NFE");
        assertThat(result.candidate("chaveAcesso").orElseThrow()
                .normalizedValue().asText()).isEqualTo(key);
        assertThat(result.candidate("fornecedorCnpj").orElseThrow()
                .normalizedValue().asText()).isEqualTo("12345678000195");
        assertThat(result.candidate("valorLiquido").orElseThrow()
                .normalizedValue().decimalValue()).isEqualByComparingTo("95.00");
        assertThat(result.candidate("valorLiquido").orElseThrow().location())
                .contains("ICMSTot", "vNF");
    }

    @Test
    void blocksDoctypeAndMalformedXmlWithoutReturningCandidates() {
        FiscalExtractionResult unsafe = extractor.extract(source("""
                <?xml version="1.0"?>
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <foo>&xxe;</foo>
                """));
        FiscalExtractionResult malformed = extractor.extract(source("<NFe>"));

        assertThat(unsafe.status()).isEqualTo(ExtractionStatus.FALHA);
        assertThat(unsafe.candidates()).isEmpty();
        assertThat(unsafe.warnings()).contains("XML_INSEGURO");
        assertThat(malformed.status()).isEqualTo(ExtractionStatus.FALHA);
        assertThat(malformed.candidates()).isEmpty();
    }

    @Test
    void keepsDeclaredTotalButRequiresReviewWhenMathDiverges() {
        FiscalExtractionResult result = extractor.extract(source("""
                <?xml version="1.0"?>
                <NFe xmlns="http://www.portalfiscal.inf.br/nfe"><infNFe Id="NFe%s">
                  <ide><nNF>10</nNF><serie>1</serie><dhEmi>2026-07-14T09:30:00-03:00</dhEmi></ide>
                  <emit><CNPJ>12345678000195</CNPJ><xNome>Fornecedor</xNome></emit>
                  <total><ICMSTot><vProd>100.00</vProd><vDesc>0.00</vDesc>
                    <vFrete>0.00</vFrete><vSeg>0.00</vSeg><vOutro>0.00</vOutro>
                    <vII>0.00</vII><vIPI>0.00</vIPI><vST>0.00</vST>
                    <vNF>99.00</vNF></ICMSTot></total>
                </infNFe></NFe>
                """.formatted(validNfeKey())));

        assertThat(result.status())
                .isEqualTo(ExtractionStatus.REVISAO_NECESSARIA);
        assertThat(result.warnings()).contains("TOTAL_DIVERGENTE");
        assertThat(result.candidate("valorLiquido").orElseThrow().validation())
                .isEqualTo(CandidateValidation.DIVERGENTE);
    }

    @Test
    void preservesInvalidAccessKeyAsReviewableEvidence() {
        FiscalExtractionResult result = extractor.extract(source("""
                <NFe xmlns="http://www.portalfiscal.inf.br/nfe">
                  <infNFe Id="NFe35260712345678000195550030000001251000000010">
                    <ide><nNF>10</nNF><serie>1</serie><dEmi>2026-07-14</dEmi></ide>
                    <emit><CNPJ>12345678000195</CNPJ></emit>
                    <total><ICMSTot><vProd>10.00</vProd><vNF>10.00</vNF>
                    </ICMSTot></total>
                  </infNFe>
                </NFe>
                """));

        assertThat(result.status())
                .isEqualTo(ExtractionStatus.REVISAO_NECESSARIA);
        assertThat(result.warnings()).contains("CHAVE_ACESSO_INVALIDA");
        assertThat(result.candidate("chaveAcesso").orElseThrow().validation())
                .isEqualTo(CandidateValidation.DIVERGENTE);
    }

    private FiscalDocumentSource source(String xml) {
        return new FiscalDocumentSource(
                "application/xml",
                "nota.xml",
                xml.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String validNfeKey() {
        String base = "3526071234567800019555003000000125100000001";
        int weight = 2;
        int sum = 0;
        for (int index = base.length() - 1; index >= 0; index--) {
            sum += Character.digit(base.charAt(index), 10) * weight;
            weight = weight == 9 ? 2 : weight + 1;
        }
        int digit = 11 - (sum % 11);
        if (digit >= 10) digit = 0;
        return base + digit;
    }
}
