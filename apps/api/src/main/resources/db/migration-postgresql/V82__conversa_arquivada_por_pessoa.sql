-- Arquivar e limpar uma conversa, para uma pessoa só.
--
-- A conversa já tinha status ARQUIVADA, mas ele é da conversa: arquivar ali
-- tiraria o assunto da tela de todo mundo, e ninguém tem esse direito sobre a
-- caixa alheia. O que se quer é o contrário — cada um organiza a sua vista, e
-- a conversa segue inteira para os outros.
--
-- Também não dava para pendurar isso em conversa_participante. Em conversa de
-- OBRA e de EQUIPE o acesso nasce do vínculo com a obra, não de uma linha de
-- participante, e quem é Alfa alcança conversa em que nunca foi inscrito:
-- muita gente que vê uma conversa não tem linha nenhuma nessa tabela. A
-- preferência é de quem lê, exista ou não a inscrição.
--
-- Nada aqui apaga mensagem. "Limpar" guarda um instante: o que é anterior a
-- ele some da minha vista e continua no banco, visível para os outros e
-- intacto para auditoria — o RDO e a conversa são registro de obra, e registro
-- de obra não desaparece porque alguém quis a tela mais limpa.
CREATE TABLE conversa_preferencia_pessoal (
    id varchar(36) PRIMARY KEY,
    conversa_id varchar(36) NOT NULL REFERENCES conversa(id),
    colaborador_id varchar(36) NOT NULL REFERENCES colaborador(id),
    -- Nulo = a conversa está na minha lista. Preenchido = eu a arquivei.
    arquivado_em timestamp(6) without time zone,
    -- Nulo = vejo o histórico inteiro. Preenchido = vejo o que veio depois.
    limpo_ate timestamp(6) without time zone,
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_conversa_preferencia_pessoal UNIQUE (conversa_id, colaborador_id)
);

-- A lista de conversas pergunta "o que esta pessoa arquivou?" a cada abertura.
CREATE INDEX idx_conversa_preferencia_pessoal_colaborador
    ON conversa_preferencia_pessoal (colaborador_id, arquivado_em, conversa_id);

CREATE TRIGGER trg_conversa_preferencia_pessoal_touch_atualizado_em
    BEFORE UPDATE ON conversa_preferencia_pessoal
    FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();
