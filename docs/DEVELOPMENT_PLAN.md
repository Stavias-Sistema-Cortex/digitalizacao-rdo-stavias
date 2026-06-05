# Plano de desenvolvimento

## Princípios

- Priorizar funcionamento offline-first para equipes em campo.
- Nunca versionar dados reais de clientes, documentos internos ou credenciais.
- Manter `main` estável, `develop` como integração e `feature/*` para trabalho diário.
- Validar regras críticas antes de automatizar decisões operacionais.

## Fase 1 — Fundação do backend

- Configurar NestJS, TypeScript, Prisma e PostgreSQL.
- Criar módulos iniciais de RDO, sincronização, clima e manutenção.
- Definir schema inicial do banco com projetos, trechos, frentes, RDOs, equipamentos e auditoria.
- Criar endpoints mínimos para testar a lógica sem frontend.

## Fase 2 — RDO real

- Mapear todos os campos do `RDO.xlsx`.
- Ajustar DTOs e modelos para refletir o formulário real.
- Criar validações de datas, horas, equipamentos, produção e assinatura.
- Persistir RDOs via Prisma em vez de memória.

## Fase 3 — Sincronização offline-first

- Definir identificador único por dispositivo.
- Salvar operações locais na PWA futura com IndexedDB/Dexie.
- Implementar `push` de alterações pendentes e `pull` de mudanças do servidor.
- Criar regras de conflito para versões divergentes do mesmo RDO.
- Registrar auditoria de quem alterou, quando alterou e de onde veio a alteração.

## Fase 4 — Meteorologia por corredor

- Cadastrar pontos de controle por km.
- Sincronizar previsões quando houver internet.
- Armazenar snapshots meteorológicos no banco.
- Gerar alertas como risco de chuva por trecho e impacto em serviços sensíveis.

## Fase 5 — Equipamentos e manutenção

- Detectar paradas e quebras registradas no RDO.
- Abrir solicitações para manutenção.
- Controlar status da solicitação até resolução.
- Relacionar paradas com impacto no cronograma e produtividade.

## Fase 6 — Sala técnica e aprovação

- Criar fluxo de envio do apontador para sala técnica.
- Adicionar validação manual por engenheiro ou responsável.
- Criar histórico de mudanças para auditoria.
- Revisar requisitos jurídicos de assinatura digital antes de escolher tecnologia.

## Fase 7 — Treinamento e adoção

- Criar documentação simples para campo.
- Preparar apresentação de treinamento.
- Avaliar chatbot de suporte interno apenas depois que o fluxo operacional estiver estável.

## Critérios de MVP

- Criar RDO com dados principais da obra.
- Operar em campo sem internet no frontend futuro.
- Sincronizar quando a conexão voltar.
- Listar pendências de validação para sala técnica.
- Registrar parada de equipamento e abrir fluxo de manutenção.
- Manter auditoria mínima das alterações.
