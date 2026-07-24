# Revisão — limite StavIA e ontologia

**Conclusão:** rejeitar a alegação de conclusão. A remoção por exclusão não preserva a ontologia: o próprio resumo informa que o endpoint, o serviço e a única chamada de sincronização operacional estavam no pacote apagado. Os testes verdes citados são estreitos demais para provar limite de runtime, projeção, persistência PostgreSQL ou disponibilidade da ontologia.

## Matriz de requisitos

| Requisito | Veredito | Evidência e lacuna concreta |
|---|---|---|
| StavIA está ausente de todo runtime compilado (web e API). | INDIRECT | O resumo declara que o diretório web, imports do launcher e pacote backend foram apagados, mas não há varredura de fontes/saída de build, teste de fronteira executável, nem suíte API completa. `RdoServiceTest` e um build web não cobrem rotas, configurações, dependências ou referências restantes. |
| Histórico de StavIA é preservado em arquivo não compilado. | CONTRADICTED | O resumo declara que não existe arquivo `archive/stavia` nem README de arquivamento; a exclusão destrói o requisito de preservação e torna restauração/auditoria impossíveis. |
| Branding legítimo **STAVIAS** continua permitido. | INDIRECT | O resumo diz que logos `stavias-*` foram mantidos, o que é coerente com o contrato: `Stavias` (marca) não deve ser confundido com `StavIA` (assistente). Porém não há inspeção de artefato atual que prove a preservação. Isso não é, por si, uma referência proibida ao assistente. |
| A API de ontologia permanece independente de StavIA. | CONTRADICTED | `/api/ontology/entities` e `StaviaOntologyService` estavam no pacote apagado. Logo a capacidade foi removida, não extraída/renomeada como exige o contrato. |
| O grafo/ontologia continua funcional, determinístico, idempotente e checkpointed. | CONTRADICTED | Não há novo projector, repositório de grafo ou checkpoint. Além disso, `synchronizeOperationalData(obraId)` era chamado somente por `StaviaReasoningService`, criando uma dependência de projeção/sincronização perdida pela exclusão. |
| A projeção persiste e é comprovada no PostgreSQL. | MISSING | Não existe migração, teste de integração PostgreSQL ou replay que demonstre `ON CONFLICT`, checkpoint transacional, ausência de duplicação ou recuperação de falha. |
| Os testes citados demonstram a alegação completa. | INDIRECT | Um teste de serviço RDO e o build frontend somente demonstram esses alvos; não demonstram rota autorizada de ontologia, projeção, fronteira StavIA, archive não compilado nem PostgreSQL. |

## Riscos concretos

- `GET /api/ontology/entities` deixa de existir ou quebra em runtime após apagar seu controller/serviço.
- Eventos operacionais podem deixar de alimentar o grafo porque a única chamada de sincronização foi apagada junto com o serviço de raciocínio.
- Mesmo que a UI compile, o grafo pode ficar vazio ou obsoleto, sem checkpoint/retry e sem diagnóstico confiável.
- Sem archive, perde-se a história do assistente e aumenta-se o risco de reintrodução acidental de tipos, rotas ou configuração sem uma fronteira testada.
- Um caminho ainda pode referenciar StavIA em configuração, dependência, rota ou artefato compilado sem que os dois sinais verdes o revelem.

## Menor sequência de correção verificável

1. Antes de apagar, extrair contratos puros de grafo para `com.projeto.cortex.ontology.graph`: entidades, relações, eventos, estados, evidências, lote de projeção e repositório. Criar teste que proíba `intelligence.stavia` e `Stavia` nesse pacote.
2. Migrar a consulta de ontologia para controller/serviço independentes em `/api/ontology/**`, com autorização por obra, SQL parametrizado e limites de travessia. Provar acesso negado entre obras e rejeição de profundidade excessiva.
3. Substituir a chamada exclusiva de `StaviaReasoningService` por um projector operacional independente. Implementar migração PostgreSQL com checkpoint, upserts determinísticos/idempotentes e retry seguro de falha.
4. Executar teste unitário de replay e integração PostgreSQL: o mesmo evento gera exatamente os mesmos IDs/fatos, não duplica linhas e avança o checkpoint uma vez; falha deixa o checkpoint inalterado.
5. Mover, com histórico, todo código e testes específicos do assistente para `archive/stavia/backend` e `archive/stavia/web`; acrescentar README com commit de origem, razão, limite de restauração e proibição de compilação. Não mover contratos de ontologia nem projection para o archive.
6. Remover wiring de StavIA no backend e frontend, preservando branding `STAVIAS`. Adicionar testes de fronteira que varram fontes de produção por `intelligence.stavia`, `/api/stavia`, providers/launchers e imports de `archive`; executar suíte API, suíte/lint/build web e varreduras de fronteira.

Só após esses artefatos e comandos registrarem sucesso o requisito de remoção pode ser **PROVEN**. A alegação de ontologia/grafo “perfeito” exige adicionalmente prova PostgreSQL de projeção e replay; com o fixture atual ela é diretamente refutada.
