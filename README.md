# Digitalização de RDO — Stavias

Projeto para digitalização e organização do fluxo de Relatórios Diários de Obra (RDOs).

## Objetivo

Estruturar uma solução digital para facilitar o registro, a validação e o acompanhamento de informações relacionadas aos RDOs.

## Status

Em desenvolvimento. Esta versão contém o primeiro esqueleto de backend, sem frontend.

## Estrutura do projeto

- `src/`: código-fonte da API
- `src/modules/rdo/`: fluxo principal de Relatórios Diários de Obra
- `src/modules/sync/`: contratos iniciais para sincronização offline-first
- `src/modules/weather/`: avaliação local de risco meteorológico por corredor
- `src/modules/maintenance/`: abertura inicial de solicitações de manutenção
- `src/prisma/`: conexão central com o banco via Prisma
- `prisma/schema.prisma`: modelo inicial do banco PostgreSQL
- `tests/`: testes automatizados futuros
- `docs/`: documentação técnica e funcional

## Segurança e privacidade

Este repositório não deve armazenar documentos internos, credenciais ou dados reais de clientes.

## Stack proposta

- TypeScript
- NestJS
- Prisma
- PostgreSQL
- Arquitetura offline-first para integração futura com PWA/IndexedDB

## Comandos iniciais

```bash
npm install
cp .env.example .env
npm run prisma:generate
npm run start:dev
```

Antes de rodar migrações, preencha `DATABASE_URL` no `.env` local com uma conexão PostgreSQL válida.

## Documentação

- `docs/ARCHITECTURE.md`: explica a lógica dos módulos, arquivos e endpoints.
- `docs/DEVELOPMENT_PLAN.md`: plano de evolução do MVP até versões com sincronização, clima, manutenção e validação.
