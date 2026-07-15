# Validação do potencial Monte Carlo do PDOR

Data: 2026-07-15  
Versão avaliada após os gates: `PDOR-0.5.0` / `PDOR-ASSUMPTIONS-0.5.0`

## Conclusão executiva

O Monte Carlo do PDOR tem potencial comprovado para apoio à decisão, comparação
de cenários e ordenação de risco. A estabilidade numérica do cenário controlado
foi forte entre o orçamento operacional e a referência de alta iteração.

Ele ainda não é uma previsão probabilística validada fora da amostra. O produto
deve apresentar resultados como `Protótipo` ou `Histórico assistido`, nunca como
probabilidade calibrada de receita, até concluir backtest temporal e validação
externa. `calibratedProbabilityBelow95Pct` permanece nulo.

## Gates incorporados

- mínimo de 12 observações semanais para produtividade ou material passarem
  de premissa de protótipo para histórico Stavias;
- equipamento continua explicitamente em premissa de protótipo;
- convergência exige duas comparações estáveis consecutivas entre lotes
  independentes, em vez de comparar acumulados altamente correlacionados;
- seed e versões permanecem determinísticas e auditáveis;
- invariantes de percentis, finitude, probabilidades e direção de risco
  continuam obrigatórios;
- o orçamento operacional é comparado em teste à referência de 80.000
  iterações.

## Evidência quantitativa controlada

Cenário sintético controlado: contrato de R$ 4,3 milhões, avanço físico de 48%,
12 semanas de produtividade e material, com perda operacional e paralisação de
equipamento. Os dados são deliberadamente sintéticos e não representam uma
obra real nem um backtest.

| Métrica | 20.000 solicitadas | Referência 80.000 solicitadas | Diferença |
|---|---:|---:|---:|
| P10 | R$ 4.021.488,63 | R$ 4.021.060,52 | 0,0106% |
| P50 | R$ 4.061.328,03 | R$ 4.061.244,86 | 0,0020% |
| P80 | R$ 4.086.544,47 | R$ 4.086.544,52 | < 0,0001% |
| P95 | R$ 4.108.932,96 | R$ 4.108.997,43 | 0,0016% |
| P(receita < 95% contrato) | 78,40% | 78,47% | 0,07 p.p. |

A execução operacional usou as 20.000 iterações e não declarou convergência
sob o gate independente mais rigoroso. A referência declarou convergência em
60.000 iterações. Isso é comportamento conservador desejado: ausência do selo
de convergência reduz confiança; não transforma um resultado estável em uma
alegação excessiva.

## O que está validado

- estabilidade numérica no cenário controlado;
- determinismo do mesmo snapshot;
- monotonicidade dos limiares de shortfall;
- direção de risco para pior captura, paralisação, material e produtividade;
- proteção contra dados não finitos, negativos e iteração insuficiente;
- transição para histórico somente com 12 semanas;
- distinção entre estabilidade de simulação e calibração externa.

## O que falta para status VALIDADO

1. backtest walk-forward por obra, sem usar observações futuras;
2. tamanho mínimo de amostra por fase e relatório de autocorrelação/tamanho
   efetivo;
3. cobertura empírica dos intervalos P10/P50/P80/P95;
4. Brier score e curva de calibração para os limiares de 90%, 95% e contrato;
5. comparação da triangular com bootstrap empírico e distribuições alternativas;
6. sensibilidade global dos coeficientes causais, não apenas testes direcionais;
7. histórico real de equipamento;
8. validação externa e monitoramento de drift por versão.

Até esses gates existirem, o uso recomendado é triagem e discussão operacional,
com os drivers e as premissas visíveis. Não deve ser usado sozinho para promessa
contratual, reconhecimento contábil ou decisão financeira irreversível.
