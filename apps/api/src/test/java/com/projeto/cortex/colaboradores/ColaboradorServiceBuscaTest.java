package com.projeto.cortex.colaboradores;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A busca de pessoas por palavras soltas.
 *
 * <p>O LIKE '%termo%' exigia que o digitado fosse um pedaço contíguo do nome,
 * então "paulo neris" não achava "PAULO SERGIO DA SILVA NERIS" — essas duas
 * palavras nunca aparecem juntas. Na prática, só o nome completo e na ordem
 * exata encontrava alguém.
 */
class ColaboradorServiceBuscaTest {

    private final ColaboradorRepository repository =
            mock(ColaboradorRepository.class);
    private final ColaboradorService service =
            new ColaboradorService(repository);

    @Test
    void encontraPeloPrimeiroEPeloUltimoNome() {
        List<Colaborador> achados = List.of(
                colaborador("PAULO SERGIO DA SILVA NERIS"),
                colaborador("PAULO ROBERTO FERREIRA")
        );
        when(repository.buscarColaboradoresAtivos(anyString())).thenReturn(achados);

        List<ColaboradorResponse> encontrados =
                service.listarColaboradores("paulo neris");

        assertThat(encontrados)
                .extracting(ColaboradorResponse::nome)
                .containsExactly("PAULO SERGIO DA SILVA NERIS");
    }

    @Test
    void consultaOBancoComAPalavraMaisLongaPorSerAMaisRestritiva() {
        when(repository.buscarColaboradoresAtivos(anyString()))
                .thenReturn(List.of());

        service.listarColaboradores("da silveira");

        verify(repository).buscarColaboradoresAtivos("silveira");
    }

    @Test
    void ignoraAcentoQueOLowerDoSqlSozinhoNaoResolvia() {
        List<Colaborador> achados = List.of(colaborador("JOSÉ ANTÔNIO DA CONCEIÇÃO"));
        when(repository.buscarColaboradoresAtivos(anyString())).thenReturn(achados);

        assertThat(service.listarColaboradores("jose conceicao"))
                .hasSize(1);
    }

    @Test
    void semTermoNaoVaiParaAConsultaDeBusca() {
        List<Colaborador> todos = List.of(colaborador("ANA"));
        when(repository.findByDeletadoEmIsNullOrderByNomeAsc()).thenReturn(todos);

        assertThat(service.listarColaboradores("   ")).hasSize(1);
        verify(repository).findByDeletadoEmIsNullOrderByNomeAsc();
    }

    @Test
    void naoDevolveQuemFalhaEmUmaDasPalavras() {
        List<Colaborador> achados = List.of(colaborador("PAULO SERGIO DA SILVA NERIS"));
        when(repository.buscarColaboradoresAtivos(anyString())).thenReturn(achados);

        assertThat(service.listarColaboradores("paulo ferreira")).isEmpty();
    }

    /** A entidade não expõe setters; o nome é tudo que a busca lê. */
    private Colaborador colaborador(String nome) {
        Colaborador colaborador = mock(Colaborador.class);
        when(colaborador.getNome()).thenReturn(nome);
        return colaborador;
    }
}
