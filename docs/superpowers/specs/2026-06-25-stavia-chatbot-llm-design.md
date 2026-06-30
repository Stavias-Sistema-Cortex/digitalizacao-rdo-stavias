# Stav.IA Chatbot — Inteligência por LLM local com grounding rígido

- **Data:** 2026-06-25
- **Branch:** develop
- **Abordagem escolhida:** B — LLM no entendimento + na geração (com correção determinística de completude)
- **Cérebro:** modelo local/auto-hospedado (LGPD, sem custo por consulta, sem internet)
- **Stack recomendada:** Ollama + Qwen2.5 7B Instruct (trocável por config; 14B se o hardware permitir)
- **Modo inicial do intérprete (ao ligar):** `interpreter-mode: llm` (llm-first) — para validar o teto de qualidade
- **Entrega:** plano único (completude determinística + integração LLM juntas)

---

## 1. Contexto e problema

A Stav.IA hoje é um pipeline **100% determinístico** (palavra-chave + template). Duas dores relatadas pelo usuário:

1. **Fragilidade de frase.** Reescrever a pergunta muda o resultado por completo. Ex.: *"Tem apontador?"* vs *"Quem é o apontador dessa obra?"* dão respostas diferentes. "apontador" sequer existe no vocabulário de regras.
2. **Contexto incompleto.** A Stav.IA enxerga uma fatia estreita (tipicamente o último RDO) em vez do quadro completo. O exemplo dado (um colaborador presente em 2 RDOs aparecendo em só 1) é apenas um sintoma.

**Visão do usuário:** a Stav.IA deve ser um **oráculo** — enxergar *tudo* que existe no Córtex (dentro da obra e das permissões do usuário) e responder com o quadro completo, em linguagem natural, "de fato inteligente".

## 2. Diagnóstico (causas-raiz)

| Sintoma | Causa-raiz | Arquivo |
|---|---|---|
| Fragilidade de frase | Classificação de intenção por **scoring de palavra-chave**; vocabulário fixo; "apontador" inexistente | `intent/StaviaIntentClassifier.java` |
| Idem | Planejamento por regex/`contains`; sinônimos chumbados | `planning/StaviaQueryPlanner.java`, `semantic/StaviaSemanticCatalog.java` |
| Contexto incompleto | Fontes buscam o **bolo da obra sem filtrar pela entidade** (LIMIT 50) e o engine **trunca em 5** evidências (ordenado por data desc) | `knowledge/allocation/AllocationKnowledgeSource.java`, `StaviaEngine.limitSources` (l. 287–291) |
| "Chat burro" | Geração 100% **template**; o modo `prompt` usa `DeterministicStaviaModelClient` (também determinístico); **nenhuma dependência de IA no projeto** | `generation/DeterministicStaviaResponseGenerator.java`, `generation/DeterministicStaviaModelClient.java` |

**Costuras já prontas para inteligência (reaproveitadas):** interface `StaviaResponseGenerator`, interface `StaviaModelClient`, `StaviaPromptBuilder` (com regras de grounding já escritas), e a propriedade `cortex.stavia.generator-mode` em `application.yml`.

**Rede de segurança de grounding já existente (forte):** `StaviaGroundingValidator` + `StaviaEngine.resolveGeneratedSources` **rejeitam qualquer resposta que cite uma fonte fora do contexto autorizado** — ela vira `INFORMACAO_INSUFICIENTE`. Isso permite plugar o LLM com segurança: ele não consegue forjar citações.

## 3. Princípio condutor

**O LLM propõe, o determinístico dispõe.** O modelo adiciona flexibilidade de linguagem em dois pontos (entender e redigir), mas **toda decisão de segurança permanece determinística e inalterada**: grounding, citação de fontes, contradição, qualidade e acesso. Se o modelo cair, ficar lento ou devolver lixo, a Stav.IA **cai no comportamento atual** — nunca quebra, nunca trava o usuário.

**Completude como propriedade.** Dentro do escopo autorizado, a Stav.IA recupera o **conjunto completo** de evidências relevantes e **sintetiza**; ela nunca mostra silenciosamente um pedaço. Quando precisa resumir por volume, resume de forma **transparente** (agrega/conta), nunca descartando dado sem avisar.

**Oráculo honesto.** Onisciente sobre o que *existe* no Córtex, **dentro da obra e das permissões do usuário**; não atravessa obra, não inventa, e é explícito sobre lacunas. Oráculo que inventa é perigoso; oráculo que conhece todo o dado real e é honesto sobre o que falta é o objetivo.

## 4. Arquitetura

```
POST /api/stavia/consultas
   │
   ▼
StaviaQueryService
   │
   ├─① ENTENDER  ──►  StaviaQuestionInterpreter ★
   │                    ├─ LlmQuestionInterpreter ★ (Ollama) → StaviaClassification + StaviaQueryPlan
   │                    │     (intenção + entidades p.ex. COLABORADOR/ROLE + atributos + tempo)
   │                    └─ DeterministicQuestionInterpreter (classifier + planner atuais) = fallback
   │                    Saída validada contra os enums/catálogo existentes.
   │
   ├─② RECUPERAR ──►  StaviaKnowledgeOrchestrator → fontes JDBC
   │                    + completude: fontes filtram por entidade/role; engine para de truncar em 5 ★
   │
   ├─③ VALIDAR   ──►  Contradição / Qualidade / Grounding   (INALTERADO)
   │
   └─④ REDIGIR   ──►  PromptBasedStaviaResponseGenerator → StaviaModelClient
                        ├─ OllamaStaviaModelClient ★ (resposta natural, aterrada, cita sourceKeys)
                        └─ DeterministicStaviaResponseGenerator = fallback
```

**Decisão de reuso:** o intérprete produz os **artefatos que já existem** — `StaviaClassification(intent, confidence)` e `StaviaQueryPlan(domain, operation, entities, temporalFilter, requestedAttributes, …, requiredSources, …)`. O caminho a jusante (orquestrador, registry, engine) permanece o mesmo; muda apenas *quem* preenche esses artefatos e o fato de as fontes passarem a **usar** `plan.entities()`.

### Inventário de componentes

**Novos:**
- `interpret/StaviaInterpretation.java` — record `{ StaviaClassification classification, StaviaQueryPlan plan, Origin origin }` (`Origin = LLM | DETERMINISTICO`, para telemetria).
- `interpret/StaviaQuestionInterpreter.java` — interface `StaviaInterpretation interpret(StaviaQuestion q)`.
- `interpret/DeterministicQuestionInterpreter.java` — embrulha `StaviaIntentClassifier` + `StaviaQueryPlanner` (lógica de hoje) no contrato acima. É o fallback e a base de comparação.
- `interpret/LlmQuestionInterpreter.java` — Ollama + prompt estruturado (`response_format: json_object`) + validação.
- `interpret/StaviaInterpretationCoordinator.java` — orquestra primário+fallback conforme `interpreter-mode`.
- `interpret/StaviaInterpretationPromptBuilder.java` — monta o prompt de interpretação (lista de intenções com 1 linha em PT, tipos de entidade, atributos do catálogo, few-shots).
- `llm/OllamaChatClient.java` — **único** ponto que toca HTTP (RestClient do Spring); messages+opções → texto; timeout, retry, circuit-breaker.
- `llm/StaviaLlmProperties.java` — `@ConfigurationProperties("cortex.stavia.llm")`.
- `generation/OllamaStaviaModelClient.java` — `implements StaviaModelClient`, ativo quando `generator-mode=prompt`. **Atenção de wiring:** hoje `DeterministicStaviaModelClient` é `@Component` sem condição; é preciso torná-los **mutuamente exclusivos** (condicionar por `generator-mode`/`@ConditionalOnProperty` ou `@Primary`) para `PromptBasedStaviaResponseGenerator` não injetar bean ambíguo.

**Modificados:**
- `knowledge/StaviaKnowledgeRequest.java` — passa a expor os filtros de entidade do plano (via `plan().entities()`; sem novo campo se o plano já os carrega).
- Fontes (`AllocationKnowledgeSource`, `team/*`, `equipment/*`, etc.) — aplicam filtro por colaborador (nome, `unaccent`/`ILIKE`), por função/cargo (`funcao`/`cargo`) e por equipamento.
- `StaviaEngine.limitSources` — orçamento por tipo de consulta em vez de corte fixo em 5.
- `StaviaQueryService.query` — usa o `StaviaInterpretationCoordinator` no lugar de `classifyDetailed` + `plan` diretos.
- `version/StaviaVersions.java` — adiciona `INTERPRETATION` e `LLM_*` versions; `application.yml` — bloco `cortex.stavia.llm` + `interpreter-mode`.

**Reutilizados como validador/fallback (sem mudança de comportamento):** `StaviaIntentClassifier`, `StaviaQueryPlanner`, `StaviaSemanticCatalog`, `StaviaPromptBuilder`, e todas as políticas.

## 5. Entendimento — intérprete semântico

Resolve a **fragilidade de frase**. O LLM recebe intenções, tipos de entidade (COLABORADOR, EQUIPAMENTO, OBRA, RDO, **ROLE/função**), atributos do catálogo e few-shots, devolvendo JSON. Exemplos-chave:

```
"Tem apontador?"               → intent CONSULTAR_EQUIPE, entities:[ROLE "apontador"]
"Quem é o apontador da obra?"  → intent CONSULTAR_EQUIPE, entities:[ROLE "apontador"]   (igual!)
"Onde o Abner trabalhou?"      → intent CONSULTAR_ALOCACAO_COLABORADOR, entities:[COLABORADOR "Abner"]
```

"apontador" vira um filtro de **função** — as tabelas já têm `rdo_mao_obra.cargo` e `alocacao_colaborador.funcao`.

**Guarda determinística sobre a saída do LLM:**
1. `intent` precisa existir no enum `StaviaIntent`; senão → fallback.
2. `requestedAttributes` são **interseccionados** com o `StaviaSemanticCatalog`; desconhecidos caem fora.
3. `entities` precisam ter `type` reconhecido; nomes ficam como `value` para resolução no banco (§6). Ambiguidade → `ResolvedEntity.ambiguous=true` + `alternatives`.
4. `confidence < confidence-threshold` (default 0,45) → usa o fallback determinístico.
5. JSON inválido / timeout / Ollama fora → fallback determinístico. **Sempre há resposta.**

**Modos (`interpreter-mode`):**
- `deterministic` (default): comportamento atual (LLM desligado).
- `llm`: sempre interpreta com LLM; fallback em falha/baixa confiança. Máxima robustez.
- `llm-on-doubt`: roda o determinístico primeiro; só aciona o LLM quando dá `DESCONHECIDA`/confiança baixa. Perguntas óbvias ficam instantâneas; o LLM entra nos casos difíceis (como "apontador"). Controla latência.

## 6. Completude de contexto (oráculo)

1. **Entidades/papéis irrigam a recuperação.** `plan.entities()` (colaborador, função, equipamento, serviço, RDO) passa a ser **usado** pelas fontes para filtrar com precisão, em vez de devolver o bolo da obra. Matching de nome com `unaccent`/normalização e tolerante a parciais.
2. **Fim do estreitamento artificial.** `StaviaEngine.limitSources` vira **orçamento por tipo de consulta**: consultas de listagem/entidade mantêm o conjunto relevante (SQL já limita em 50); cortes, quando houver, são declarados ("+N registros omitidos"). Consultas sobre uma entidade deixam de assumir "só o último RDO" — olham a janela inteira, salvo recorte temporal explícito.
3. **Síntese aterrada.** O gerador recebe **todas** as evidências relevantes (até `max-evidences`) e produz resposta coesa citando os `sourceKeys` granulares; o `StaviaGroundingValidator` confere cada citação.
4. **Panorama multi-fonte.** Para perguntas holísticas ("panorama da obra"), o orquestrador faz fan-out entre fontes (RDOs, equipe, alocação, ocorrências, equipamentos, financeiro, PDOC) e o LLM costura um relato único.

**Sem RAG/vector store** — completude vem de recuperação honesta + síntese, não de busca aproximada.

## 7. Cliente Ollama, contrato HTTP e configuração

**Contrato (endpoint compatível com OpenAI; Ollama expõe em `/v1`):**
```
POST {base-url}/chat/completions
{ "model": "...", "messages": [ {role:"system",…}, {role:"user",…} ],
  "temperature": 0, "response_format": { "type": "json_object" } }
→ choices[0].message.content (string JSON) → parse para os records
```

Interpretação devolve `{intent, entities, attributes, temporal, confidence}`; geração devolve `{text, answerType, sourceKeys}`. HTTP via **`RestClient` do Spring** (já no classpath; **zero dependência nova**).

**Configuração (flags com default no comportamento ATUAL — "ship dark"):**
```yaml
cortex:
  stavia:
    generator-mode:   ${CORTEX_STAVIA_GENERATOR_MODE:deterministic}    # 'prompt' liga geração LLM
    interpreter-mode: ${CORTEX_STAVIA_INTERPRETER_MODE:deterministic}  # 'llm' | 'llm-on-doubt'
    llm:
      base-url:           ${CORTEX_STAVIA_LLM_BASE_URL:http://localhost:11434/v1}
      model:              ${CORTEX_STAVIA_LLM_MODEL:qwen2.5:7b-instruct}
      api-key:            ${CORTEX_STAVIA_LLM_API_KEY:ollama}
      connect-timeout-ms: 2000
      read-timeout-ms:    ${CORTEX_STAVIA_LLM_READ_TIMEOUT_MS:20000}
      max-evidences:      50
      confidence-threshold: 0.45
```
Com as flags em `deterministic`, **nada muda** — merge seguro; liga-se quando o Ollama subir. A correção de completude (§6) é determinística e vale **mesmo com o LLM desligado**.

## 8. Erros, latência e resiliência

- **Cadeia de contingência:** interpretação falha → classifier+planner; geração falha → gerador determinístico. Aviso discreto em `StaviaQueryResult.warnings`; **resposta sai limpa**. Nenhuma exceção chega ao usuário.
- **Circuit-breaker:** após N falhas seguidas (default: 3), pula o LLM por M segundos (default: 30) — não paga timeout a cada consulta com Ollama fora; retoma sozinho. 1 retry curto só em falha transitória.
- **Latência:** `llm-first` = 2 chamadas (poucos segundos num 7B modesto). Mitigações: `llm-on-doubt`, `read-timeout`+fallback, `max-evidences`. **Streaming (SSE) fora de escopo** (endpoint segue síncrono).
- **Telemetria (SLF4J):** `origin` (LLM/DETERMINISTICO), latência por etapa, modelo, estado do breaker.

## 9. Estratégia de testes (TDD, sem modelo no ar)

A rede está isolada atrás do `OllamaChatClient`; tudo acima usa interfaces → injeta-se falso.
- **Intérprete:** `OllamaChatClient` falso com JSON canned. Fixa que *"Tem apontador?"* e *"Quem é o apontador da obra?"* dão **a mesma** interpretação; e JSON inválido / intenção inexistente / confiança baixa → **fallback**.
- **Completude:** com dados de teste, consulta de entidade **filtra + agrega por todos os RDOs** e **não trunca em 5** (regressão do "caso Abner").
- **Geração:** JSON canned → `StaviaModelResponse`; resposta citando `sourceKey` inexistente → `INFORMACAO_INSUFICIENTE` (grounding preservado).
- **Transporte HTTP:** `MockRestServiceServer` (do `spring-test`, zero dep nova) — formato da requisição (`model`, `response_format`, mensagens), timeout, não-2xx → exceção, circuit-breaker.
- **Integração real opcional:** `@EnabledIfEnvironmentVariable(CORTEX_STAVIA_LLM_BASE_URL)` — pulada no build normal/CI.
- **Build:** compilar/testar com **JDK 21** (o default JDK 25 do shell quebra o Mockito no `StaviaControllerTest`).

## 10. Rollout

1. Merge com flags em `deterministic` (no-op).
2. Subir Ollama + `ollama pull qwen2.5:7b-instruct`; ligar `interpreter-mode: llm` + `generator-mode: prompt` em perfil local.
3. Validar manualmente "apontador"/panorama/paráfrases.
4. Produção quando a infra (CPU/GPU) estiver provisionada.

## 11. Escopo

**Inclui:** intérprete LLM (intent+entidades+atributos) com fallback; cliente Ollama OpenAI-compatível; gerador aterrado via Ollama; uso de `plan.entities()` nas fontes; correção do truncamento/agregação; config + flags; testes sem modelo no ar.

**Não inclui (YAGNI):** RAG/embeddings/vector store; streaming SSE; consultas cruzando múltiplas obras; fine-tuning; troca do banco/ontologia.

## 12. Riscos e mitigações

- **R1 — Incompatibilidade da chave de evidência.** `StaviaPromptBuilder` monta `sourceKey = type + ":" + id`, mas as fontes já embutem o tipo no `id` (ex.: `AllocationKnowledgeSource` usa `id = "ALOCACAO_COLABORADOR:" + uuid`), e o engine valida com `StaviaEvidenceKeys.key(...)`. Risco de **dupla prefixação** → toda resposta LLM viraria "não rastreável". **Mitigação:** unificar a derivação de chave numa única fonte da verdade (`StaviaEvidenceKeys.key`) usada no prompt e no validador, com teste cobrindo.
- **R2 — JSON inválido de modelo pequeno.** Mitigação: `response_format: json_object`, validação + fallback, 1 retry.
- **R3 — Latência em hardware fraco.** Mitigação: `llm-on-doubt`, timeout+fallback, `max-evidences`, breaker, modelo trocável.
- **R4 — Qualidade de PT/raciocínio do 7B.** Mitigação: modelo trocável (14B), few-shots, fixture de avaliação de paráfrases.
- **R5 — Resolução de nomes/acentos/ambiguidade.** Mitigação: `unaccent`/normalização + matching tolerante; quando ambíguo, o oráculo **lista candidatos** (`ResolvedEntity.alternatives`) em vez de chutar.

## 13. Critérios de aceitação

1. **Robustez a paráfrase:** um conjunto de paráfrases (incl. "Tem apontador?" / "Quem é o apontador da obra?") produz a mesma intenção+entidade e a mesma resposta factual. *(teste)*
2. **Completude:** uma entidade presente em ≥2 RDOs aparece com **todos** os RDOs na resposta (sem truncar). *(teste de regressão)*
3. **Grounding intacto:** resposta que citaria fonte fora do contexto → `INFORMACAO_INSUFICIENTE`. *(teste)*
4. **Fallback:** com LLM indisponível (falso lança/timeout), a consulta responde via determinístico, sem erro, com aviso. *(teste)*
5. **Ship-dark:** com flags em `deterministic`, comportamento idêntico ao atual. *(teste/observação)*
6. **Função/role:** "quem é o apontador" filtra equipe por cargo/função e responde com a(s) pessoa(s) correta(s). *(teste)*
7. **Build/test verdes em JDK 21.**
