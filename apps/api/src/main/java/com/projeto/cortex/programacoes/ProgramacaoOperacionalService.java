package com.projeto.cortex.programacoes;

import com.projeto.cortex.obras.Obra;
import com.projeto.cortex.obras.ObraRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
public class ProgramacaoOperacionalService {

    private final ProgramacaoOperacionalRepository programacaoRepository;
    private final ObraRepository obraRepository;

    public ProgramacaoOperacionalService(
            ProgramacaoOperacionalRepository programacaoRepository,
            ObraRepository obraRepository
    ) {
        this.programacaoRepository = programacaoRepository;
        this.obraRepository = obraRepository;
    }

    public List<ProgramacaoOperacionalResponse> listarProgramacoes(String query) {
        List<ProgramacaoOperacional> programacoes;

        if (isBlank(query)) {
            programacoes = programacaoRepository.listar(PageRequest.of(0, 100));
        } else {
            programacoes = programacaoRepository.buscar(query.trim(), PageRequest.of(0, 100));
        }

        return programacoes.stream()
                .map(ProgramacaoOperacionalResponse::from)
                .toList();
    }

    public ProgramacaoOperacionalResponse criarProgramacao(ProgramacaoOperacionalRequest request) {
        if (isBlank(request.obraId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Campo obrigatório ausente: obraId");
        }

        if (request.dataProgramacao() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Campo obrigatório ausente: dataProgramacao");
        }

        Obra obra = obraRepository.findById(request.obraId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Obra não encontrada."));

        String status = isBlank(request.status())
                ? "PLANEJADA"
                : request.status().trim().toUpperCase(Locale.ROOT);

        String fonteCriacao = isBlank(request.fonteCriacao())
                ? "MANUAL"
                : request.fonteCriacao().trim().toUpperCase(Locale.ROOT);

        ProgramacaoOperacional programacao = ProgramacaoOperacional.criar(
                obra.getId(),
                obra.getCodigoContrato(),
                obra.getNome(),
                request.dataProgramacao(),
                normalizarOpcional(request.equipe()),
                normalizarOpcional(request.encarregado()),
                normalizarOpcional(request.engenheiro()),
                normalizarOpcional(request.cliente()),
                normalizarOpcional(request.servico()),
                normalizarOpcional(request.tipoServico()),
                normalizarOpcional(request.cidade()),
                normalizarUf(request.uf()),
                normalizarOpcional(request.rodovia()),
                normalizarOpcional(request.sentido()),
                normalizarOpcional(request.faixa()),
                normalizarOpcional(request.kmInicial()),
                normalizarOpcional(request.kmFinal()),
                request.extensaoM(),
                request.larguraM(),
                request.espessuraCm(),
                request.areaM2(),
                request.volumeM3(),
                status,
                fonteCriacao,
                normalizarOpcional(request.fonteArquivo()),
                request.linhaOrigem(),
                gerarHash(request, obra),
                normalizarOpcional(request.observacoes())
        );

        return ProgramacaoOperacionalResponse.from(programacaoRepository.save(programacao));
    }

    private String gerarHash(ProgramacaoOperacionalRequest request, Obra obra) {
        try {
            String valor = String.join("|",
                    nullToEmpty(obra.getId()),
                    nullToEmpty(obra.getCodigoContrato()),
                    nullToEmpty(obra.getNome()),
                    request.dataProgramacao() == null ? "" : request.dataProgramacao().toString(),
                    nullToEmpty(request.equipe()),
                    nullToEmpty(request.encarregado()),
                    nullToEmpty(request.engenheiro()),
                    nullToEmpty(request.cliente()),
                    nullToEmpty(request.servico()),
                    nullToEmpty(request.tipoServico()),
                    nullToEmpty(request.cidade()),
                    nullToEmpty(request.uf()),
                    nullToEmpty(request.rodovia()),
                    nullToEmpty(request.sentido()),
                    nullToEmpty(request.faixa()),
                    nullToEmpty(request.kmInicial()),
                    nullToEmpty(request.kmFinal()),
                    request.extensaoM() == null ? "" : request.extensaoM().toPlainString(),
                    request.larguraM() == null ? "" : request.larguraM().toPlainString(),
                    request.espessuraCm() == null ? "" : request.espessuraCm().toPlainString(),
                    request.areaM2() == null ? "" : request.areaM2().toPlainString(),
                    request.volumeM3() == null ? "" : request.volumeM3().toPlainString()
            );

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(valor.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception exception) {
            throw new RuntimeException("Falha ao gerar hash da programação.", exception);
        }
    }

    private String normalizarOpcional(String value) {
        if (isBlank(value)) {
            return null;
        }

        return value.trim();
    }

    private String normalizarUf(String uf) {
        if (isBlank(uf)) {
            return null;
        }

        String normalizada = uf.trim().toUpperCase(Locale.ROOT);

        if (normalizada.length() != 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UF deve ter exatamente 2 caracteres.");
        }

        return normalizada;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
