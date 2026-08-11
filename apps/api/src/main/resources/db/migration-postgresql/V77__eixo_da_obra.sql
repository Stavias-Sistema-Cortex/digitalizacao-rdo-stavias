-- O eixo da obra: a rodovia desenhada uma vez, com quilômetro nas pontas.
--
-- Até aqui, um trecho só existia no mapa se alguém o tivesse desenhado ali à
-- mão, um desenho por RDO. Quem apontava pelo quilômetro — que é como a obra
-- fala — não via nada: o dado estava lá, com km inicial e final declarados, e a
-- única tela que mostra onde o trabalho aconteceu ficava vazia.
--
-- O eixo inverte o gesto. Traça-se a rodovia uma vez, diz-se qual quilômetro
-- está em cada extremidade, e a partir daí todo apontamento com quilômetro se
-- apoia sozinho sobre ela. O desenho deixa de ser tarefa por RDO e vira
-- cadastro da obra.
--
-- É uma categoria nova de geometria, e não uma tabela: o eixo é exatamente o
-- que `obra_geometria` já guarda — uma linha vigente, com propriedades e
-- histórico —, e a quilometragem das pontas mora em `propriedades_json`, junto
-- do resto do que descreve o desenho. Nenhuma coluna nova, nenhum dado
-- duplicado: a régua fica no cadastro e o quilômetro do trabalho continua
-- morando só no apontamento do RDO.

ALTER TABLE obra_geometria
    DROP CONSTRAINT chk_obra_geometria_categoria;

ALTER TABLE obra_geometria
    ADD CONSTRAINT chk_obra_geometria_categoria CHECK (categoria IN (
        'LOCALIZACAO_OBRA', 'PERIMETRO_OBRA', 'TRECHO', 'PONTO_OPERACIONAL',
        'FRENTE_TRABALHO', 'EQUIPAMENTO', 'EVENTO', 'RDO', 'OCORRENCIA',
        'PROGRAMACAO', 'EIXO_OBRA'
    ));

COMMENT ON COLUMN obra_geometria.categoria IS
    'Camada geoespacial do desenho. EIXO_OBRA é o leito da rodovia, cadastrado uma vez por obra, e carrega kmInicial/kmFinal em propriedades_json — é a régua sobre a qual os quilômetros apontados nos RDOs são posicionados, e não uma afirmação de execução.';
