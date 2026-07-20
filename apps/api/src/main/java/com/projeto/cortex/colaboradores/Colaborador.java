package com.projeto.cortex.colaboradores;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "colaborador")
public class Colaborador {

    @Id
    private String id;

    @Column(name = "banco_origem", nullable = false)
    private String bancoOrigem;

    @Column(name = "tabela_origem", nullable = false)
    private String tabelaOrigem;

    @Column(name = "pk_origem", nullable = false)
    private String pkOrigem;

    @Column(name = "codigo_colaborador")
    private String codigoColaborador;

    @Column(name = "cpf_hash")
    private String cpfHash;

    @Column(name = "cpf_mascarado")
    private String cpfMascarado;

    @Column(name = "nome", nullable = false)
    private String nome;

    @Column(name = "email")
    private String email;

    @Column(name = "id_grupo_origem")
    private String idGrupoOrigem;

    @Column(name = "nome_grupo")
    private String nomeGrupo;

    @Column(name = "id_perfil_origem")
    private String idPerfilOrigem;

    @Column(name = "nome_perfil")
    private String nomePerfil;

    @Column(name = "papel_acesso")
    private String papelAcesso;

    @Column(name = "ativo", nullable = false)
    private boolean ativo;

    @Column(name = "criado_em_origem")
    private LocalDateTime criadoEmOrigem;

    @Column(name = "atualizado_em_origem")
    private LocalDateTime atualizadoEmOrigem;

    @Column(name = "hash_origem")
    private String hashOrigem;

    @Column(name = "importado_em")
    private LocalDateTime importadoEm;

    @Column(name = "visto_por_ultimo_em")
    private LocalDateTime vistoPorUltimoEm;

    @Column(name = "atualizado_em")
    private LocalDateTime atualizadoEm;

    @Column(name = "deletado_em")
    private LocalDateTime deletadoEm;

    @Column(name = "versao_linha")
    private long versaoLinha;

    public String getId() {
        return id;
    }

    public String getBancoOrigem() {
        return bancoOrigem;
    }

    public String getTabelaOrigem() {
        return tabelaOrigem;
    }

    public String getPkOrigem() {
        return pkOrigem;
    }

    public String getCodigoColaborador() {
        return codigoColaborador;
    }

    public String getCpfHash() {
        return cpfHash;
    }

    public String getCpfMascarado() {
        return cpfMascarado;
    }

    public String getNome() {
        return nome;
    }

    public String getEmail() {
        return email;
    }

    public String getIdGrupoOrigem() {
        return idGrupoOrigem;
    }

    public String getNomeGrupo() {
        return nomeGrupo;
    }

    public String getIdPerfilOrigem() {
        return idPerfilOrigem;
    }

    public String getNomePerfil() {
        return nomePerfil;
    }

    public String getPapelAcesso() {
        return papelAcesso;
    }

    public boolean isAtivo() {
        return ativo;
    }

    public LocalDateTime getCriadoEmOrigem() {
        return criadoEmOrigem;
    }

    public LocalDateTime getAtualizadoEmOrigem() {
        return atualizadoEmOrigem;
    }

    public String getHashOrigem() {
        return hashOrigem;
    }

    public LocalDateTime getImportadoEm() {
        return importadoEm;
    }

    public LocalDateTime getVistoPorUltimoEm() {
        return vistoPorUltimoEm;
    }

    public LocalDateTime getAtualizadoEm() {
        return atualizadoEm;
    }

    public LocalDateTime getDeletadoEm() {
        return deletadoEm;
    }

    public long getVersaoLinha() {
        return versaoLinha;
    }
}
