-- A função profissional do colaborador, vinda do Academy.
--
-- O cadastro já trazia nome, CPF e perfil de ACESSO (nome_perfil) — e era o
-- perfil que acabava servindo de cargo na mão de obra do RDO, por falta de
-- coisa melhor. "Apontador" e "Administrador" descrevem o que a pessoa pode
-- fazer no sistema, não o que ela faz na obra. A função é o ofício declarado
-- na origem, e viaja pelo mesmo sync que o nome.
ALTER TABLE colaborador
    ADD COLUMN funcao varchar(255);
