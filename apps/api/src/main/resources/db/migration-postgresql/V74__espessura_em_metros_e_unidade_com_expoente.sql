-- Duas unidades que estavam erradas de lados opostos.

-- 1. A espessura do serviço passa a ser medida em metros.
--
-- Ela nasceu em centímetros na V69 porque é assim que se fala dela em campo —
-- "cinco centímetros de capa". Só que era a única das três medidas do serviço
-- numa unidade diferente das outras duas, e a divisão por cem vivia escondida
-- dentro da conta de volume, em três lugares distintos do código. Um lugar a
-- mais para errar por cem, num número que sai daqui direto para a medição.
--
-- A conversão acontece uma vez e é aritmética, não interpretação: o que estava
-- gravado em centímetros vale exatamente o mesmo em metros dividido por cem. A
-- escala vai a quatro casas antes da divisão porque três não bastam — 6,25 cm
-- é 0,0625 m, e truncar aqui apagaria meio milímetro de capa asfáltica.
ALTER TABLE execucao_servico_rdo
    ALTER COLUMN espessura_cm TYPE numeric(10,4);

UPDATE execucao_servico_rdo
   SET espessura_cm = espessura_cm / 100
 WHERE espessura_cm IS NOT NULL;

ALTER TABLE execucao_servico_rdo
    RENAME COLUMN espessura_cm TO espessura_m;

COMMENT ON COLUMN execucao_servico_rdo.espessura_m IS
    'Espessura executada do serviço, em metros, na mesma unidade da largura e do comprimento. NULL é ausência de medida; sem ela o volume fica indeterminado, nunca zero.';

-- 2. A unidade do preço volta a aceitar metro quadrado e metro cúbico.
--
-- O formato exigia `^[A-Z0-9][A-Z0-9._/-]{0,29}$`, que não tem lugar para ²
-- nem ³. O catálogo do Córtex escreve a unidade no símbolo — "m2" digitado vira
-- "M²" —, então toda tentativa de cadastrar um serviço medido em área ou em
-- volume era gravada localmente, entrava na fila e voltava recusada pelo banco.
-- Sobrava "M": quem precisava de M² ou M³ via o cadastro falhar na
-- sincronização sem que nada dissesse qual caractere era o culpado.
--
-- Os expoentes entram na lista permitida; o resto da regra fica de pé, porque
-- ela é o que impede a unidade de virar texto livre e de deixar de casar com o
-- preço na hora de apurar receita.
ALTER TABLE service_price_version
    DROP CONSTRAINT chk_service_price_version_unit;

ALTER TABLE service_price_version
    ADD CONSTRAINT chk_service_price_version_unit
    CHECK (unidade ~ '^[A-Z0-9][A-Z0-9²³._/-]{0,29}$');
