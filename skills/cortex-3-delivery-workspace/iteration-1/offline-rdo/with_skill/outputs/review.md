# Revisão: Novo RDO offline e sincronização

## Veredito

**Rejeitado.** A implementação não demonstra que `Novo RDO` nem a sincronização estejam prontos para Cortex 3. O único teste prova navegação; ele não prova criação válida, persistência offline ou sincronização.

## Achados priorizados

### P0 — Dados operacionais fictícios são gravados no fluxo de produção

`handleCreate()` fixa `obra-demo`, `RDO-001`, João e Carlos no código de produção. Isso viola o requisito de obra real e autorizada, inventa mão de obra e pode apresentar um RDO aparentemente real sem fonte persistida. O número também não é apenas uma sugestão: é imposto sem a resolução autoritativa de colisões.

### P0 — O rascunho não tem identidade de mutação estável nem gravação local atômica comprovada

O fluxo não gera nem evidencia um UUID de cliente estável. Tampouco há evidência de uma única transação IndexedDB que persista o snapshot do RDO, a mutação canônica e o evento operacional pendente antes da navegação. Portanto, `saveRdo(draft)` não prova sobrevivência a recarga, recuperação de falha intermediária, isolamento por usuário ou replay idempotente.

### P0 — A sincronização é manual e afirma sucesso sem evidência persistida

`syncNow()` só é executada pelo botão `Sincronizar` e faz `setStatus("SYNCED")` depois de `api.push`. Isso não satisfaz a sincronização automática em login, volta de conectividade, foco e gravações bem-sucedidas; também não mostra que cada mutação foi aceita, rejeitada ou entrou em conflito. Uma falha parcial, rejeição de autorização ou conflito pode ser apresentada como sincronizada.

### P1 — Não existe contexto de criação por obra nem proveniência da mão de obra anterior

Não há consulta da RDO elegível mais recente, data selecionada, colaboradores autorizados ou IDs estáveis de colaborador. As linhas usam `colaboradorId: null`, impossibilitando deduplicação, seleção/deseleção confiável, indicação/remoção do apontador e preservação de trabalhador histórico indisponível como proveniência.

### P1 — Faltam autorização, versionamento e prova de reconciliação no servidor

Não há evidência de verificação de acesso à obra antes de criar/sincronizar, de versão-base, de idempotência por mutation ID ou de resolução transacional do número RDO. Assim, não há prova contra acesso entre obras, duplicação após replay ou colisões de número em criações concorrentes.

### P1 — A evidência de teste é insuficiente para a alegação funcional

O teste de clique/navegação não cobre entrada por obra, cache autorizado, recarga offline, reconexão automática, persistência do estado de fila, conflitos/rejeições ou não duplicação. Não há teste de integração PostgreSQL nem cenário de navegador/cliente com desligamento de rede.

## Matriz de requisitos

| Requisito | Estado | Evidência atual |
|---|---|---|
| Novo RDO exige obra real e autorizada | CONTRADICTED | A obra é `"obra-demo"` fixa. |
| Não há dados operacionais fabricados | CONTRADICTED | Obra, número e trabalhadores são constantes de produção. |
| ID de cliente estável e preservado | MISSING | Não há geração nem teste de UUID estável. |
| Snapshot, mutação e evento gravam atomicamente no cliente | MISSING | Não há transação IndexedDB nem teste correspondente. |
| Mão de obra vem da RDO anterior com proveniência por ID | CONTRADICTED | Não há consulta anterior e os IDs são `null`. |
| Sincronização é automática e tem estado literal persistido | CONTRADICTED | Depende do botão e força estado React `SYNCED`. |
| Recarregar offline preserva o RDO pendente | MISSING | Não há cenário de recarga offline. |
| Reconectar sincroniza uma vez, sem duplicar | MISSING | Não há teste de reconexão, idempotência ou PostgreSQL. |

## Critérios de aceite funcionais

1. `Novo RDO` abre uma seleção de obra e data; só obras autorizadas vindas da API ou do cache local daquele usuário são exibidas. Sem contexto disponível offline, o editor não abre e informa a ausência de contexto — sem criar valores de exemplo.
2. Após selecionar obra e data, o cliente gera um UUID uma única vez. Antes de navegar, uma única transação IndexedDB, em namespace do usuário autenticado, grava o rascunho, a mutação canônica versionada e o evento pendente correlacionado. Uma recarga offline preserva os três e seu estado pendente.
3. O contexto inclui a RDO não cancelada mais recente anterior à data, número/data/versão de origem e trabalhadores por `colaboradorId`. Trabalhadores ainda autorizados iniciam selecionados; indisponíveis permanecem visíveis como proveniência, desmarcados. O usuário pode desmarcar/adicionar trabalhador e alterar ou limpar o apontador, que só pode pertencer ao conjunto selecionado.
4. O servidor autoriza a obra antes de carregar ou gravar, valida referências e versão, aplica a criação e o evento em uma transação PostgreSQL, e usa o UUID do cliente para idempotência. Criações concorrentes podem receber números autoritativos distintos sem trocar o UUID nem duplicar RDO/evento.
5. A fila inicia automaticamente após login, escrita local bem-sucedida, retorno de rede e foco; aplica backoff para falhas de rede. Estados `PENDING`, `SYNCED`, `REJECTED` e `CONFLICT` são derivados de registros persistidos, e rejeição/conflito mantém o diagnóstico e a mutação local.
6. Há testes de integração e de cliente que: criam RDO offline com contexto em cache, recarregam, reconectam sem pressionar sincronização, confirmam uma única criação/evento, número autoritativo e estado literal; repetem recarga/reconexão sem duplicações; e negam acesso de outra obra. A integração exercita PostgreSQL real, não mock/H2.

Somente após esses critérios, mais a evidência de navegador da sequência offline–recarga–reconexão, a alegação de que Novo RDO e sync estão prontos pode ser aceita.
