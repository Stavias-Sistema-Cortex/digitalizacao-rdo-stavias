-- Popula o catálogo de funções operacionais.
--
-- A tabela nasceu vazia na V44 e nunca ganhou carga inicial nem tela de
-- cadastro: existe POST /api/funcoes-operacionais, mas nada no aplicativo o
-- chama. O efeito era um beco sem saída — o seletor "Função operacional" do
-- diálogo "Adicionar pessoa" não tinha uma única opção, a função é obrigatória
-- para salvar, e portanto nenhuma pessoa podia ser adicionada a nenhuma equipe
-- numa instalação nova. Sem carga inicial, montar equipe era impossível.
--
-- Os nomes abaixo são os cargos de campo comuns em obra rodoviária, escolhidos
-- para destravar o fluxo, e não um retrato do quadro real da empresa. São dados
-- editáveis: PUT /api/funcoes-operacionais/{id} renomeia e desativa, e o
-- catálogo é justamente o lugar onde a empresa registra a própria nomenclatura.
--
-- ON CONFLICT porque o código é único e o catálogo pode já ter sido preenchido
-- à mão pela API antes desta migração rodar. Semear é garantir que exista
-- alguma opção, nunca sobrescrever a escolha de quem chegou primeiro.
INSERT INTO funcao_operacional (
    id, codigo, nome, descricao, ativo, ordem_exibicao, criado_por, atualizado_por
)
VALUES
    ('a3f1c0d2-0001-4a10-9c01-5f6b7c8d9e01', 'ENCARREGADO', 'Encarregado',
     'Responde pela frente de serviço e pela equipe em campo.', TRUE, 1, 'SISTEMA', 'SISTEMA'),
    ('a3f1c0d2-0002-4a10-9c01-5f6b7c8d9e02', 'APONTADOR', 'Apontador',
     'Registra o RDO e o avanço dos serviços.', TRUE, 2, 'SISTEMA', 'SISTEMA'),
    ('a3f1c0d2-0003-4a10-9c01-5f6b7c8d9e03', 'OPERADOR', 'Operador de equipamento',
     'Opera máquinas e equipamentos da frente.', TRUE, 3, 'SISTEMA', 'SISTEMA'),
    ('a3f1c0d2-0004-4a10-9c01-5f6b7c8d9e04', 'MOTORISTA', 'Motorista',
     'Conduz veículos e caminhões da obra.', TRUE, 4, 'SISTEMA', 'SISTEMA'),
    ('a3f1c0d2-0005-4a10-9c01-5f6b7c8d9e05', 'TOPOGRAFO', 'Topógrafo',
     'Faz locação, nivelamento e conferência geométrica.', TRUE, 5, 'SISTEMA', 'SISTEMA'),
    ('a3f1c0d2-0006-4a10-9c01-5f6b7c8d9e06', 'SINALEIRO', 'Sinaleiro',
     'Cuida da sinalização e do desvio de tráfego.', TRUE, 6, 'SISTEMA', 'SISTEMA'),
    ('a3f1c0d2-0007-4a10-9c01-5f6b7c8d9e07', 'MECANICO', 'Mecânico',
     'Mantém e repara equipamentos em campo.', TRUE, 7, 'SISTEMA', 'SISTEMA'),
    ('a3f1c0d2-0008-4a10-9c01-5f6b7c8d9e08', 'SERVENTE', 'Servente',
     'Apoia os serviços gerais da frente.', TRUE, 8, 'SISTEMA', 'SISTEMA')
ON CONFLICT (codigo) DO NOTHING;
