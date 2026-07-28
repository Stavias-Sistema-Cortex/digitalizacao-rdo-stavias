package com.projeto.cortex.programacoes;

import com.projeto.cortex.obras.Obra;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import com.projeto.cortex.obras.ObraRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final ObraOperabilityGuard operabilityGuard;

    public ProgramacaoSeedImportService(
            ProgramacaoOperacionalRepository programacaoRepository,
            ObraRepository obraRepository,
            ObraOperabilityGuard operabilityGuard
    ) {
        this.programacaoRepository = programacaoRepository;
        this.obraRepository = obraRepository;
        this.operabilityGuard = operabilityGuard;
    }

    @Transactional
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

    ProgramacaoSeedImportResult importar(Path path) {
        int registrosLidos = 0;
        int registrosInseridos = 0;
        int registrosAtualizados = 0;
        int registrosIgnorados = 0;
        int registrosComErro = 0;

        try {
            List<String> linhas = Files.readAllLines(path, StandardCharsets.UTF_8);

            if (linhas.isEmpty()) {
                return new ProgramacaoSeedImportResult("SUCCESS", path.toString(), 0, 0, 0, 0, 0);
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

                    LocalDate dataProgramacao = LocalDate.parse(
                            valorObrigatorio(header, valores, "data_programacao")
                    );

                    String equipe = valorOpcional(header, valores, "equipe");
                    String encarregado = valorOpcional(header, valores, "encarregado");
                    String engenheiro = valorOpcional(header, valores, "engenheiro");
                    String cliente = valorOpcional(header, valores, "cliente");
                    String servico = valorOpcional(header, valores, "servico");
                    String tipoServico = valorOpcional(header, valores, "tipo_servico");
                    String cidade = valorOpcional(header, valores, "cidade");
                    String uf = valorOpcional(header, valores, "uf");
                    String rodovia = valorOpcional(header, valores, "rodovia");
                    String sentido = valorOpcional(header, valores, "sentido");
                    String faixa = valorOpcional(header, valores, "faixa");
                    String kmInicial = valorOpcional(header, valores, "km_inicial");
                    String kmFinal = valorOpcional(header, valores, "km_final");

                    BigDecimal extensaoM = decimalOpcional(header, valores, "extensao_m");
                    BigDecimal larguraM = decimalOpcional(header, valores, "largura_m");
                    BigDecimal espessuraCm = decimalOpcional(header, valores, "espessura_cm");
                    BigDecimal areaM2 = decimalOpcional(header, valores, "area_m2");
                    BigDecimal volumeM3 = decimalOpcional(header, valores, "volume_m3");

                    String fonteArquivo = valorOpcional(header, valores, "fonte_arquivo");
                    Integer linhaOrigem = inteiroOpcional(header, valores, "linha_origem");
                    String observacoes = valorOpcional(header, valores, "observacoes");

                    String hashOrigem = gerarHash(linha);
                    String chaveNegocio = gerarChaveNegocio(
                            obra.getCodigoContrato(),
                            dataProgramacao,
                            equipe,
                            servico,
                            tipoServico,
                            rodovia,
                            sentido,
                            faixa,
                            kmInicial,
                            kmFinal,
                            linhaOrigem
                    );

                    var existente = programacaoRepository.findByChaveNegocio(chaveNegocio);

                    if (existente.isPresent()) {
                        ProgramacaoOperacional programacao = existente.get();

                        if (programacao.temMesmoHashOrigem(hashOrigem)) {
                            registrosIgnorados++;
                            continue;
                        }

                        operabilityGuard.requireWritable(obra.getId());
                        programacao.atualizarDeSeed(
                                obra.getId(),
                                obra.getCodigoContrato(),
                                obra.getNome(),
                                dataProgramacao,
                                equipe,
                                encarregado,
                                engenheiro,
                                cliente,
                                servico,
                                tipoServico,
                                cidade,
                                uf,
                                rodovia,
                                sentido,
                                faixa,
                                kmInicial,
                                kmFinal,
                                extensaoM,
                                larguraM,
                                espessuraCm,
                                areaM2,
                                volumeM3,
                                "PLANEJADA",
                                "SEED",
                                fonteArquivo,
                                linhaOrigem,
                                hashOrigem,
                                chaveNegocio,
                                observacoes
                        );

                        programacaoRepository.save(programacao);
                        registrosAtualizados++;
                        continue;
                    }

                    operabilityGuard.requireWritable(obra.getId());
                    ProgramacaoOperacional programacao = ProgramacaoOperacional.criarComChaveNegocio(
                            obra.getId(),
                            obra.getCodigoContrato(),
                            obra.getNome(),
                            dataProgramacao,
                            equipe,
                            encarregado,
                            engenheiro,
                            cliente,
                            servico,
                            tipoServico,
                            cidade,
                            uf,
                            rodovia,
                            sentido,
                            faixa,
                            kmInicial,
                            kmFinal,
                            extensaoM,
                            larguraM,
                            espessuraCm,
                            areaM2,
                            volumeM3,
                            "PLANEJADA",
                            "SEED",
                            fonteArquivo,
                            linhaOrigem,
                            hashOrigem,
                            chaveNegocio,
                            observacoes
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
                    registrosAtualizados,
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
            header.put(colunas.get(index).replace("\uFEFF", "").trim(), index);
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

        String normalizado = value.trim();

        if (normalizado.equalsIgnoreCase("null")) {
            return null;
        }

        return normalizado;
    }

    private BigDecimal decimalOpcional(Map<String, Integer> header, List<String> valores, String coluna) {
        String value = valorOpcional(header, valores, coluna);

        if (value == null || ehVazioOperacional(value)) {
            return null;
        }

        return new BigDecimal(value.replace(",", "."));
    }

    private Integer inteiroOpcional(Map<String, Integer> header, List<String> valores, String coluna) {
        String value = valorOpcional(header, valores, coluna);

        if (value == null || ehVazioOperacional(value)) {
            return null;
        }

        return Integer.valueOf(value);
    }

    private boolean ehVazioOperacional(String value) {
        String normalizado = value.trim();

        return normalizado.isBlank()
                || normalizado.equals("-")
                || normalizado.equals("–")
                || normalizado.equals("—")
                || normalizado.equalsIgnoreCase("n/a")
                || normalizado.equalsIgnoreCase("na");
    }

    private String gerarChaveNegocio(
            String codigoContrato,
            LocalDate dataProgramacao,
            String equipe,
            String servico,
            String tipoServico,
            String rodovia,
            String sentido,
            String faixa,
            String kmInicial,
            String kmFinal,
            Integer linhaOrigem
    ) throws Exception {
        String base = String.join(
                "|",
                textoChave(codigoContrato),
                dataProgramacao == null ? "" : dataProgramacao.toString(),
                textoChave(equipe),
                textoChave(servico),
                textoChave(tipoServico),
                textoChave(rodovia),
                textoChave(sentido),
                textoChave(faixa),
                textoChave(kmInicial),
                textoChave(kmFinal),
                linhaOrigem == null ? "" : linhaOrigem.toString()
        );

        return gerarHash(base);
    }

    private String textoChave(String value) {
        if (value == null) {
            return "";
        }

        return value.trim();
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
