package com.projeto.cortex.programacoes;

import com.projeto.cortex.obras.Obra;
import com.projeto.cortex.obras.ObraRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class ProgramacaoSeedImportService {

    private static final List<Path> SEED_PATH_CANDIDATES = List.of(
            Path.of("data", "seeds", "programacoes_seed.csv"),
            Path.of("..", "..", "data", "seeds", "programacoes_seed.csv")
    );

    private final ProgramacaoOperacionalRepository programacaoRepository;
    private final ObraRepository obraRepository;

    public ProgramacaoSeedImportService(
            ProgramacaoOperacionalRepository programacaoRepository,
            ObraRepository obraRepository
    ) {
        this.programacaoRepository = programacaoRepository;
        this.obraRepository = obraRepository;
    }

    public ProgramacaoSeedImportResult importarSeedPadrao() {
        return importar(encontrarSeedPath());
    }

    private Path encontrarSeedPath() {
        for (Path path : SEED_PATH_CANDIDATES) {
            if (Files.exists(path)) {
                return path;
            }
        }

        throw new IllegalStateException(
                "Arquivo de seed não encontrado. Caminhos testados: " + SEED_PATH_CANDIDATES
        );
    }

    private ProgramacaoSeedImportResult importar(Path path) {
        int registrosLidos = 0;
        int registrosInseridos = 0;
        int registrosIgnorados = 0;
        int registrosComErro = 0;

        try {
            List<String> linhas = Files.readAllLines(path, StandardCharsets.UTF_8);

            if (linhas.isEmpty()) {
                return new ProgramacaoSeedImportResult("SUCCESS", path.toString(), 0, 0, 0, 0);
            }

            Map<String, Integer> header = mapearHeader(parseCsvLine(linhas.get(0)));

            for (int index = 1; index < linhas.size(); index++) {
                String linha = linhas.get(index);

                if (linha == null || linha.isBlank()) {
                    continue;
                }

                registrosLidos++;

                try {
                    List<String> valores = parseCsvLine(linha);

                    String codigoContrato = valorObrigatorio(header, valores, "codigo_contrato");
                    Obra obra = obraRepository.findByCodigoContrato(codigoContrato)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Obra não encontrada para codigo_contrato: " + codigoContrato
                            ));

                    String hashOrigem = gerarHash(linha);

                    if (programacaoRepository.existsByHashOrigem(hashOrigem)) {
                        registrosIgnorados++;
                        continue;
                    }

                    ProgramacaoOperacional programacao = ProgramacaoOperacional.criar(
                            obra.getId(),
                            obra.getCodigoContrato(),
                            obra.getNome(),
                            LocalDate.parse(valorObrigatorio(header, valores, "data_programacao")),
                            valorOpcional(header, valores, "equipe"),
                            valorOpcional(header, valores, "encarregado"),
                            valorOpcional(header, valores, "engenheiro"),
                            valorOpcional(header, valores, "cliente"),
                            valorOpcional(header, valores, "servico"),
                            valorOpcional(header, valores, "tipo_servico"),
                            valorOpcional(header, valores, "cidade"),
                            valorOpcional(header, valores, "uf"),
                            valorOpcional(header, valores, "rodovia"),
                            valorOpcional(header, valores, "sentido"),
                            valorOpcional(header, valores, "faixa"),
                            valorOpcional(header, valores, "km_inicial"),
                            valorOpcional(header, valores, "km_final"),
                            decimalOpcional(header, valores, "extensao_m"),
                            decimalOpcional(header, valores, "largura_m"),
                            decimalOpcional(header, valores, "espessura_cm"),
                            decimalOpcional(header, valores, "area_m2"),
                            decimalOpcional(header, valores, "volume_m3"),
                            "PLANEJADA",
                            "SEED",
                            valorOpcional(header, valores, "fonte_arquivo"),
                            inteiroOpcional(header, valores, "linha_origem"),
                            hashOrigem,
                            valorOpcional(header, valores, "observacoes")
                    );

                    programacaoRepository.save(programacao);
                    registrosInseridos++;
                } catch (Exception exception) {
                    registrosComErro++;
                }
            }

            return new ProgramacaoSeedImportResult(
                    "SUCCESS",
                    path.toString(),
                    registrosLidos,
                    registrosInseridos,
                    registrosIgnorados,
                    registrosComErro
            );
        } catch (Exception exception) {
            throw new RuntimeException("Falha ao importar seed de programação operacional.", exception);
        }
    }

    private Map<String, Integer> mapearHeader(List<String> colunas) {
        Map<String, Integer> header = new HashMap<>();

        for (int index = 0; index < colunas.size(); index++) {
            header.put(colunas.get(index).trim(), index);
        }

        return header;
    }

    private String valorObrigatorio(Map<String, Integer> header, List<String> valores, String coluna) {
        String value = valorOpcional(header, valores, coluna);

        if (value == null) {
            throw new IllegalArgumentException("Campo obrigatório ausente: " + coluna);
        }

        return value;
    }

    private String valorOpcional(Map<String, Integer> header, List<String> valores, String coluna) {
        Integer index = header.get(coluna);

        if (index == null || index >= valores.size()) {
            return null;
        }

        String value = valores.get(index);

        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }

    private BigDecimal decimalOpcional(Map<String, Integer> header, List<String> valores, String coluna) {
        String value = valorOpcional(header, valores, coluna);

        if (value == null) {
            return null;
        }

        return new BigDecimal(value.replace(",", "."));
    }

    private Integer inteiroOpcional(Map<String, Integer> header, List<String> valores, String coluna) {
        String value = valorOpcional(header, valores, coluna);

        if (value == null) {
            return null;
        }

        return Integer.valueOf(value);
    }

    private String gerarHash(String linha) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(linha.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    private List<String> parseCsvLine(String linha) {
        java.util.ArrayList<String> valores = new java.util.ArrayList<>();
        StringBuilder atual = new StringBuilder();
        boolean dentroDeAspas = false;

        for (int index = 0; index < linha.length(); index++) {
            char caractere = linha.charAt(index);

            if (caractere == '"') {
                dentroDeAspas = !dentroDeAspas;
            } else if (caractere == ',' && !dentroDeAspas) {
                valores.add(atual.toString());
                atual.setLength(0);
            } else {
                atual.append(caractere);
            }
        }

        valores.add(atual.toString());
        return valores;
    }
}
