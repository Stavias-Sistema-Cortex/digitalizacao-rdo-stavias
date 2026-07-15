package com.projeto.cortex.financeiro.invoice.extraction;

import static com.projeto.cortex.financeiro.invoice.extraction.FiscalExtractionDtos.*;

import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.springframework.stereotype.Component;
import org.xml.sax.helpers.DefaultHandler;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

@Component
public class XmlFiscalExtractor implements FiscalDocumentExtractor {

    private static final String EXTRACTOR = "NFE_XML";
    private static final String VERSION = "1";
    private static final BigDecimal CONFIDENCE = new BigDecimal("1.000000");

    @Override
    public boolean supports(String mediaType) {
        return "application/xml".equalsIgnoreCase(mediaType)
                || "text/xml".equalsIgnoreCase(mediaType);
    }

    @Override
    public FiscalExtractionResult extract(FiscalDocumentSource source) {
        String rawXml = new String(source.content(), StandardCharsets.UTF_8);
        String normalized = rawXml.toUpperCase(Locale.ROOT);
        if (normalized.contains("<!DOCTYPE") || normalized.contains("<!ENTITY")) {
            return failure("XML_INSEGURO");
        }

        try {
            Document document = parseSecurely(source.content());
            XPath xpath = XPathFactory.newInstance().newXPath();
            List<FiscalCandidate> candidates = new ArrayList<>();
            List<String> warnings = new ArrayList<>();

            addText(candidates, "tipoDocumento", "NFE", "/NFe",
                    CandidateValidation.VALIDO, null);
            addText(candidates, "moeda", "BRL", "/NFe/infNFe/total",
                    CandidateValidation.VALIDO, null);
            addText(candidates, "numero", text(xpath, document,
                    "(//*[local-name()='ide']/*[local-name()='nNF'])[1]"),
                    "/NFe/infNFe/ide/nNF", CandidateValidation.VALIDO, null);
            addText(candidates, "serie", text(xpath, document,
                    "(//*[local-name()='ide']/*[local-name()='serie'])[1]"),
                    "/NFe/infNFe/ide/serie", CandidateValidation.VALIDO, null);

            String issuedAt = text(xpath, document,
                    "(//*[local-name()='ide']/*[local-name()='dhEmi' or "
                            + "local-name()='dEmi'])[1]");
            if (issuedAt != null && issuedAt.length() >= 10) {
                issuedAt = issuedAt.substring(0, 10);
            }
            addText(candidates, "dataEmissao", issuedAt,
                    "/NFe/infNFe/ide/dhEmi", CandidateValidation.VALIDO, null);

            String cnpj = digits(text(xpath, document,
                    "(//*[local-name()='emit']/*[local-name()='CNPJ'])[1]"));
            addText(candidates, "fornecedorCnpj", cnpj,
                    "/NFe/infNFe/emit/CNPJ", CandidateValidation.VALIDO, null);
            addText(candidates, "fornecedorNome", text(xpath, document,
                    "(//*[local-name()='emit']/*[local-name()='xNome'])[1]"),
                    "/NFe/infNFe/emit/xNome", CandidateValidation.VALIDO, null);

            String key = accessKey(xpath, document);
            CandidateValidation keyValidation = validAccessKey(key)
                    ? CandidateValidation.VALIDO
                    : CandidateValidation.DIVERGENTE;
            if (key != null && keyValidation == CandidateValidation.DIVERGENTE) {
                warnings.add("CHAVE_ACESSO_INVALIDA");
            }
            addText(candidates, "chaveAcesso", key, "/NFe/infNFe/@Id",
                    keyValidation, keyValidation == CandidateValidation.VALIDO
                            ? null : "CHAVE_ACESSO_INVALIDA");

            BigDecimal gross = decimal(xpath, document, "vProd");
            BigDecimal discount = decimalOrZero(xpath, document, "vDesc");
            BigDecimal additions = sum(xpath, document,
                    "vFrete", "vSeg", "vOutro", "vII", "vIPI", "vST");
            BigDecimal retention = BigDecimal.ZERO;
            BigDecimal net = decimal(xpath, document, "vNF");

            addDecimal(candidates, "valorBruto", gross,
                    "/NFe/infNFe/total/ICMSTot/vProd",
                    CandidateValidation.VALIDO, null);
            addDecimal(candidates, "desconto", discount,
                    "/NFe/infNFe/total/ICMSTot/vDesc",
                    CandidateValidation.VALIDO, null);
            addDecimal(candidates, "acrescimo", additions,
                    "/NFe/infNFe/total/ICMSTot/(vFrete+vSeg+vOutro+vII+vIPI+vST)",
                    CandidateValidation.VALIDO, null);
            addDecimal(candidates, "retencoes", retention,
                    "/NFe/infNFe/total/ICMSTot", CandidateValidation.VALIDO, null);

            CandidateValidation totalValidation = CandidateValidation.VALIDO;
            if (gross != null && net != null) {
                BigDecimal calculated = gross.subtract(discount)
                        .add(additions).subtract(retention);
                if (calculated.compareTo(net) != 0) {
                    totalValidation = CandidateValidation.DIVERGENTE;
                    warnings.add("TOTAL_DIVERGENTE");
                }
            }
            addDecimal(candidates, "valorLiquido", net,
                    "/NFe/infNFe/total/ICMSTot/vNF", totalValidation,
                    totalValidation == CandidateValidation.DIVERGENTE
                            ? "TOTAL_DIVERGENTE" : null);

            if (!hasRequiredCandidates(candidates)) {
                warnings.add("CAMPOS_OBRIGATORIOS_AUSENTES");
            }
            ExtractionStatus status = warnings.isEmpty()
                    ? ExtractionStatus.EXTRAIDO
                    : ExtractionStatus.REVISAO_NECESSARIA;
            return new FiscalExtractionResult(
                    "XML", status, EXTRACTOR, VERSION, candidates,
                    warnings, "NAO_VERIFICADA");
        } catch (Exception exception) {
            return failure("XML_INVALIDO");
        }
    }

    private Document parseSecurely(byte[] content) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(
                "http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature(
                "http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature(
                "http://apache.org/xml/features/nonvalidating/load-external-dtd",
                false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler());
        return builder.parse(new ByteArrayInputStream(content));
    }

    private String text(XPath xpath, Document document, String expression)
            throws Exception {
        String value = (String) xpath.evaluate(
                expression, document, XPathConstants.STRING);
        return blankToNull(value);
    }

    private String accessKey(XPath xpath, Document document) throws Exception {
        Node node = (Node) xpath.evaluate(
                "(//*[local-name()='infNFe']/@Id)[1]",
                document,
                XPathConstants.NODE);
        if (node == null) return null;
        String value = node.getNodeValue();
        if (value != null && value.startsWith("NFe")) value = value.substring(3);
        return digits(value);
    }

    private BigDecimal decimal(XPath xpath, Document document, String element)
            throws Exception {
        String value = text(xpath, document,
                "(//*[local-name()='ICMSTot']/*[local-name()='" + element
                        + "'])[1]");
        return value == null ? null : new BigDecimal(value);
    }

    private BigDecimal decimalOrZero(
            XPath xpath,
            Document document,
            String element
    ) throws Exception {
        BigDecimal value = decimal(xpath, document, element);
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal sum(
            XPath xpath,
            Document document,
            String... elements
    ) throws Exception {
        BigDecimal total = BigDecimal.ZERO;
        for (String element : elements) {
            total = total.add(decimalOrZero(xpath, document, element));
        }
        return total;
    }

    private void addText(
            List<FiscalCandidate> candidates,
            String field,
            String value,
            String location,
            CandidateValidation validation,
            String detail
    ) {
        if (value == null) return;
        candidates.add(new FiscalCandidate(
                field, TextNode.valueOf(value), value, EXTRACTOR, VERSION,
                location, CONFIDENCE, validation, detail));
    }

    private void addDecimal(
            List<FiscalCandidate> candidates,
            String field,
            BigDecimal value,
            String location,
            CandidateValidation validation,
            String detail
    ) {
        if (value == null) return;
        candidates.add(new FiscalCandidate(
                field, DecimalNode.valueOf(value), value.toPlainString(),
                EXTRACTOR, VERSION, location, CONFIDENCE, validation, detail));
    }

    private boolean hasRequiredCandidates(List<FiscalCandidate> candidates) {
        return List.of("numero", "dataEmissao", "fornecedorCnpj",
                        "valorLiquido")
                .stream()
                .allMatch(required -> candidates.stream()
                        .anyMatch(candidate -> candidate.field().equals(required)));
    }

    private boolean validAccessKey(String key) {
        if (key == null || !key.matches("\\d{44}")) return false;
        int weight = 2;
        int sum = 0;
        for (int index = 42; index >= 0; index--) {
            sum += Character.digit(key.charAt(index), 10) * weight;
            weight = weight == 9 ? 2 : weight + 1;
        }
        int digit = 11 - (sum % 11);
        if (digit >= 10) digit = 0;
        return digit == Character.digit(key.charAt(43), 10);
    }

    private String digits(String value) {
        if (value == null) return null;
        return blankToNull(value.replaceAll("\\D", ""));
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.strip();
    }

    private FiscalExtractionResult failure(String warning) {
        return new FiscalExtractionResult(
                "XML", ExtractionStatus.FALHA, EXTRACTOR, VERSION,
                List.of(), List.of(warning), "NAO_VERIFICADA");
    }
}
