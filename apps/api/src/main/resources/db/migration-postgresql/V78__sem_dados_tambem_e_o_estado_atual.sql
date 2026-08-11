-- "Não dá para calcular" também é o estado atual de uma obra.
--
-- A V54 escreveu que só um cálculo bem-sucedido pode ocupar a posição de
-- atual. Na época isso era verdade por construção: o snapshot de dados
-- insuficientes nascia fora da corrente, e a restrição apenas registrava o que
-- o código já fazia.
--
-- Depois o código mudou, e por um bom motivo. Apagar o RDO que sustentava a
-- projeção deixava a projeção velha como atual para sempre — a obra desfazia a
-- produção e o número continuava na tela. A leitura honesta é a inversa:
-- quando a entrada some, o que a obra tem hoje é ausência de entrada, e é isso
-- que deve estar na posição de atual.
--
-- Só que a restrição continuou aqui. O resultado é que publicar esse snapshot
-- passou a violar um CHECK, o cálculo morria com erro do banco, nenhum
-- snapshot novo era gravado e a leitura caía de volta no último snapshot
-- existente — o vencido, com o valor do RDO apagado. O sintoma que o usuário
-- via ("Internal Server Error" ao recalcular, e o valor morto teimando na
-- tela") era esta linha.
--
-- FAILED continua de fora, e por outro motivo: falha não é projeção. Ela tem
-- tabela própria, pdor_calculation_failure, com correlação e horário da
-- tentativa.

ALTER TABLE pdor_snapshot
    DROP CONSTRAINT chk_pdor_current_success;

ALTER TABLE pdor_snapshot
    ADD CONSTRAINT chk_pdor_current_success CHECK (
        NOT is_current
        OR (
            status_execucao IN ('SUCCESS', 'INSUFFICIENT_DATA')
            AND NOT is_stale
        )
    );
