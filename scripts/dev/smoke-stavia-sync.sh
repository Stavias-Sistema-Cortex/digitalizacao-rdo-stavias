#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/stavia-sync-smoke.XXXXXX")"
MYSQL_CONTAINER="cortex-mysql-stavia-smoke-$$"
API_PID=""
API_LOG="$TMP_DIR/api.log"

DB_NAME="cortex_smoke"
DB_USER="cortex_app"
DB_PASSWORD="cortex-smoke-pass"
DB_ROOT_PASSWORD="cortex-smoke-root-pass"
JWT_SECRET="stavia-smoke-jwt-secret-0000000000000000"

ADMIN_ID="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
COLAB_1_ID="bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
COLAB_2_ID="cccccccc-cccc-cccc-cccc-cccccccccccc"
OBRA_ID="11111111-1111-1111-1111-111111111111"
RDO_ID="22222222-2222-2222-2222-222222222222"
DEVICE_ID="33333333-3333-3333-3333-333333333333"
PHOTO_1_ID="44444444-4444-4444-4444-444444444444"
PHOTO_2_ID="55555555-5555-5555-5555-555555555555"
EVENT_1_ID="66666666-6666-6666-6666-666666666666"
EVENT_2_ID="77777777-7777-7777-7777-777777777777"

log() {
  printf '[stavia-sync-smoke] %s\n' "$*"
}

fail() {
  printf '[stavia-sync-smoke] ERRO: %s\n' "$*" >&2
  if [[ -f "$API_LOG" ]]; then
    printf '\n--- ultimas linhas da API ---\n' >&2
    tail -n 120 "$API_LOG" >&2 || true
  fi
  exit 1
}

cleanup() {
  if [[ -n "$API_PID" ]] && kill -0 "$API_PID" >/dev/null 2>&1; then
    kill "$API_PID" >/dev/null 2>&1 || true
    wait "$API_PID" >/dev/null 2>&1 || true
  fi
  docker rm -f "$MYSQL_CONTAINER" >/dev/null 2>&1 || true
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "comando obrigatorio ausente: $1"
}

free_port() {
  node - <<'NODE'
const net = require("node:net");
const server = net.createServer();
server.listen(0, "127.0.0.1", () => {
  console.log(server.address().port);
  server.close();
});
NODE
}

generate_jwt() {
  node - "$JWT_SECRET" "$ADMIN_ID" <<'NODE'
const crypto = require("node:crypto");
const [secret, subject] = process.argv.slice(2);
function b64url(input) {
  return Buffer.from(input).toString("base64url");
}
const now = Math.floor(Date.now() / 1000);
const header = b64url(JSON.stringify({ alg: "HS256", typ: "JWT" }));
const payload = b64url(JSON.stringify({ sub: subject, iat: now, exp: now + 3600 }));
const signingInput = `${header}.${payload}`;
const signature = crypto.createHmac("sha256", secret).update(signingInput).digest("base64url");
console.log(`${signingInput}.${signature}`);
NODE
}

api_json() {
  local method="$1"
  local path="$2"
  local input_file="$3"
  local output_file="$4"
  local status

  status="$(
    curl -sS \
      -X "$method" \
      -H "Authorization: Bearer $TOKEN" \
      -H "Content-Type: application/json" \
      -H "Origin: http://127.0.0.1:5173" \
      -o "$output_file" \
      -w "%{http_code}" \
      --data-binary "@$input_file" \
      "$BASE_URL$path"
  )"

  if [[ "$status" -lt 200 || "$status" -ge 300 ]]; then
    printf 'Resposta HTTP %s em %s %s:\n' "$status" "$method" "$path" >&2
    cat "$output_file" >&2 || true
    fail "API retornou status inesperado"
  fi
}

api_get() {
  local path="$1"
  local output_file="$2"
  local status

  status="$(
    curl -sS \
      -H "Authorization: Bearer $TOKEN" \
      -H "Origin: http://127.0.0.1:5173" \
      -o "$output_file" \
      -w "%{http_code}" \
      "$BASE_URL$path"
  )"

  if [[ "$status" -lt 200 || "$status" -ge 300 ]]; then
    printf 'Resposta HTTP %s em GET %s:\n' "$status" "$path" >&2
    cat "$output_file" >&2 || true
    fail "API retornou status inesperado"
  fi
}

assert_json() {
  local file="$1"
  shift
  local message="${!#}"
  local jq_args_count=$(($# - 1))
  local jq_args=("${@:1:$jq_args_count}")

  jq -e "${jq_args[@]}" "$file" >/dev/null || fail "$message"
}

assert_contains() {
  local value="$1"
  local expected="$2"
  local message="$3"

  if [[ "$value" != *"$expected"* ]]; then
    printf 'Valor recebido:\n%s\n' "$value" >&2
    fail "$message"
  fi
}

require_command docker
require_command curl
require_command jq
require_command node

if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home ]]; then
  export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home
fi

API_PORT="$(free_port)"
BASE_URL="http://127.0.0.1:$API_PORT/api"

log "iniciando MySQL descartavel em container $MYSQL_CONTAINER"
docker run --rm -d \
  --name "$MYSQL_CONTAINER" \
  -e MYSQL_DATABASE="$DB_NAME" \
  -e MYSQL_USER="$DB_USER" \
  -e MYSQL_PASSWORD="$DB_PASSWORD" \
  -e MYSQL_ROOT_PASSWORD="$DB_ROOT_PASSWORD" \
  -p 127.0.0.1::3306 \
  mysql:8.4 \
  --character-set-server=utf8mb4 \
  --collation-server=utf8mb4_0900_ai_ci >/dev/null

for _ in {1..90}; do
  if ! docker inspect -f '{{.State.Running}}' "$MYSQL_CONTAINER" 2>/dev/null | grep -q true; then
    docker logs "$MYSQL_CONTAINER" >&2 || true
    fail "container MySQL descartavel encerrou antes de ficar pronto"
  fi

  if docker exec "$MYSQL_CONTAINER" mysql -uroot -p"$DB_ROOT_PASSWORD" -NBe "SELECT 1" "$DB_NAME" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

docker exec "$MYSQL_CONTAINER" mysql -uroot -p"$DB_ROOT_PASSWORD" -NBe "SELECT 1" "$DB_NAME" >/dev/null 2>&1 \
  || fail "MySQL descartavel nao ficou pronto"

DB_PORT="$(docker port "$MYSQL_CONTAINER" 3306/tcp | sed 's/.*://')"
DB_URL="jdbc:mysql://127.0.0.1:$DB_PORT/$DB_NAME?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"

log "subindo API local em $BASE_URL"
(
  cd "$ROOT_DIR/apps/api"
  env \
    SPRING_PROFILES_ACTIVE=local \
    PORT="$API_PORT" \
    CORTEX_DB_URL="$DB_URL" \
    CORTEX_DB_USER="$DB_USER" \
    CORTEX_DB_PASSWORD="$DB_PASSWORD" \
    CORTEX_AUTH_JWT_SECRET="$JWT_SECRET" \
    CORTEX_AUTH_DEV_ADMIN_ENABLED=true \
    CORTEX_IMPORT_ENABLED=false \
    CORTEX_SYNC_ENABLED=false \
    CORTEX_STAVIA_GENERATOR_MODE=deterministic \
    CORTEX_STAVIA_INTERPRETER_MODE=deterministic \
    ./mvnw -q -DskipTests spring-boot:run
) >"$API_LOG" 2>&1 &
API_PID="$!"

for _ in {1..180}; do
  if ! kill -0 "$API_PID" >/dev/null 2>&1; then
    fail "API encerrou antes do healthcheck"
  fi
  if curl -fsS "$BASE_URL/health" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

curl -fsS "$BASE_URL/health" >/dev/null 2>&1 \
  || fail "API nao respondeu ao healthcheck"

log "semeando obra e colaboradores para payload real de sync"
docker exec -i "$MYSQL_CONTAINER" mysql -uroot -p"$DB_ROOT_PASSWORD" "$DB_NAME" <<SQL
INSERT INTO obra (
  id,
  codigo_contrato,
  codigo_cw,
  codigo_interno,
  nome,
  cliente,
  cidade,
  uf,
  rodovia,
  status
) VALUES (
  '$OBRA_ID',
  'CTR-STAVIA-SMOKE',
  'CW-SMOKE',
  'OBRA-SMOKE',
  'Obra Smoke StavIA',
  'Stavias',
  'Sao Paulo',
  'SP',
  'SP-215',
  'ATIVA'
);

INSERT INTO colaborador (
  id,
  banco_origem,
  tabela_origem,
  pk_origem,
  codigo_colaborador,
  nome,
  email,
  nome_grupo,
  nome_perfil,
  ativo
) VALUES
  ('$ADMIN_ID', 'SMOKE', 'colaborador', 'admin', 'ADM', 'Admin Smoke', 'admin.smoke@example.local', 'Administradores', 'Administrador', 1),
  ('$COLAB_1_ID', 'SMOKE', 'colaborador', 'joao', 'C001', 'Joao Silva', 'joao.silva@example.local', 'Operacao', 'Operador', 1),
  ('$COLAB_2_ID', 'SMOKE', 'colaborador', 'maria', 'C002', 'Maria Souza', 'maria.souza@example.local', 'Operacao', 'Encarregada', 1);
SQL

TOKEN="$(generate_jwt)"

log "validando preflight CORS loopback e rede privada"
for origin in "http://127.0.0.1:5173" "http://192.168.0.50:5173"; do
  headers="$TMP_DIR/cors-$(echo "$origin" | tr '/:.' '____').headers"
  status="$(
    curl -sS \
      -X OPTIONS \
      -H "Origin: $origin" \
      -H "Access-Control-Request-Method: POST" \
      -H "Access-Control-Request-Headers: authorization,content-type" \
      -o /dev/null \
      -D "$headers" \
      -w "%{http_code}" \
      "$BASE_URL/sync/push"
  )"
  [[ "$status" == "200" || "$status" == "204" ]] || fail "preflight CORS retornou HTTP $status para $origin"
  grep -qi "^Access-Control-Allow-Origin: $origin" "$headers" \
    || fail "preflight CORS nao liberou origem $origin"
done

log "registrando dispositivo de sync autenticado"
DEVICE_REQUEST="$TMP_DIR/device.json"
DEVICE_RESPONSE="$TMP_DIR/device-response.json"
node - "$DEVICE_ID" <<'NODE' >"$DEVICE_REQUEST"
const [deviceId] = process.argv.slice(2);
console.log(JSON.stringify({ id: deviceId, nome: "Smoke Web", tipo: "WEB", usuarioId: null }, null, 2));
NODE
api_json POST /sync/dispositivos "$DEVICE_REQUEST" "$DEVICE_RESPONSE"
assert_json "$DEVICE_RESPONSE" --arg id "$DEVICE_ID" '.id == $id' "dispositivo nao foi registrado corretamente"

log "enviando RDO completo por /sync/push"
CREATE_PUSH="$TMP_DIR/create-rdo-push.json"
CREATE_RESPONSE="$TMP_DIR/create-rdo-response.json"
node - \
  "$DEVICE_ID" "$RDO_ID" "$OBRA_ID" "$COLAB_1_ID" "$COLAB_2_ID" \
  "$PHOTO_1_ID" "$PHOTO_2_ID" "$EVENT_1_ID" "$EVENT_2_ID" <<'NODE' >"$CREATE_PUSH"
const [
  deviceId,
  rdoId,
  obraId,
  colab1Id,
  colab2Id,
  photo1Id,
  photo2Id,
  event1Id,
  event2Id,
] = process.argv.slice(2);

const payload = {
  id: rdoId,
  obraId,
  numeroRdo: "123",
  dataRdo: "2026-07-01",
  cliente: "Stavias",
  contrato: "CTR-STAVIA-SMOKE",
  rodovia: "SP-215",
  cidade: "Sao Paulo",
  uf: "SP",
  kmInicialProgramado: "10+000",
  kmFinalProgramado: "10+282",
  turno: "DIURNO",
  horaInicio: "07:00:00",
  horaFim: "17:00:00",
  condicaoManha: "Bom",
  condicaoTarde: "Bom",
  observacoes: "Smoke de sincronizacao e StavIA",
  preenchidoPor: "Joao Silva",
  apontadorRdo: "Joao Silva",
  encarregadoObra: "Maria Souza",
  fiscalizacaoCampo: "Fiscal Smoke",
  maoObra: [
    {
      colaboradorId: colab1Id,
      nomeColaborador: "Joao Silva",
      cargo: "Operador",
      tipoVinculo: "PROPRIO",
      quantidade: 1,
      horaInicio: "07:00:00",
      horaFim: "17:00:00",
      observacoes: "Equipe frente 1"
    },
    {
      colaboradorId: colab2Id,
      nomeColaborador: "Maria Souza",
      cargo: "Encarregada",
      tipoVinculo: "PROPRIO",
      quantidade: 1,
      horaInicio: "07:00:00",
      horaFim: "17:00:00",
      observacoes: "Responsavel pela frente 2"
    }
  ],
  equipamentos: [
    {
      prefixo: "RC-01",
      descricao: "Vibroacabadora",
      tipoEquipamento: "Acabadora",
      tipoVinculo: "PROPRIO",
      quantidade: 1,
      horaInicio: "07:30:00",
      horaFim: "16:30:00",
      observacoes: "Equipamento principal"
    },
    {
      prefixo: "RC-02",
      descricao: "Rolo compactador",
      tipoEquipamento: "Rolo",
      tipoVinculo: "PROPRIO",
      quantidade: 1,
      horaInicio: "08:00:00",
      horaFim: "16:00:00",
      observacoes: "Compactacao trecho 2"
    }
  ],
  materiais: [
    {
      materialNome: "CAP 50/70",
      unidade: "t",
      quantidadePrevista: 30,
      quantidadeUsinada: 31,
      quantidadeAplicada: 28.5,
      notaFiscal: "NF-001",
      fornecedor: "Usina A",
      observacoes: "Aplicado no trecho 1"
    },
    {
      materialNome: "Massa asfaltica",
      unidade: "t",
      quantidadePrevista: 35,
      quantidadeUsinada: 36,
      quantidadeAplicada: 33.5,
      notaFiscal: "NF-002",
      fornecedor: "Usina B",
      observacoes: "Aplicado no trecho 2"
    }
  ],
  controlesGeometricos: [
    {
      subtrecho: "Trecho 1",
      numero: "1",
      estacaInicial: "E0",
      estacaFinal: "E10",
      kmInicial: "10+000",
      kmFinal: "10+120",
      pista: "Norte",
      faixa: "1",
      ordemServico: "OS-001",
      atividadeObservacoes: "Regularizacao",
      comprimentoM: 120,
      larguraM: 3.5,
      espessura1Cm: 4,
      espessura2Cm: 4.2,
      espessura3Cm: 4.1,
      densidade: 2.35,
      observacoes: "Dentro da tolerancia"
    },
    {
      subtrecho: "Trecho 2",
      numero: "2",
      estacaInicial: "E10",
      estacaFinal: "E34",
      kmInicial: "10+120",
      kmFinal: "10+402",
      pista: "Norte",
      faixa: "2",
      ordemServico: "OS-002",
      atividadeObservacoes: "Recomposicao",
      comprimentoM: 282,
      larguraM: 3.6,
      espessura1Cm: 4.5,
      espessura2Cm: 4.4,
      espessura3Cm: 4.6,
      densidade: 2.4,
      observacoes: "Trecho critico do smoke"
    }
  ],
  servicosExecutados: [
    {
      servicoNome: "Fresagem",
      quantidadeExecutada: 25,
      unidade: "m",
      trechoInicial: "10+000",
      trechoFinal: "10+120",
      localizacao: "SP-215 faixa 1",
      turno: "DIURNO",
      statusValidacao: "REGISTRADA",
      custoRealizado: 1200.5,
      retrabalho: false,
      producaoRejeitada: false,
      observacoes: "Servico 1"
    },
    {
      servicoNome: "Aplicacao CBUQ",
      quantidadeExecutada: 40,
      unidade: "m",
      trechoInicial: "10+120",
      trechoFinal: "10+402",
      localizacao: "SP-215 faixa 2",
      turno: "DIURNO",
      statusValidacao: "VALIDADA",
      custoRealizado: 2300.75,
      retrabalho: false,
      producaoRejeitada: false,
      observacoes: "Servico 2"
    }
  ],
  alocacoesColaboradores: [
    {
      colaboradorId: colab1Id,
      equipe: "Equipe A",
      servicoNome: "Fresagem",
      horaInicio: "07:00:00",
      horaFim: "11:00:00",
      turno: "DIURNO",
      funcao: "Operador",
      centroCusto: "CC-001",
      tipoAlocacao: "TRABALHO",
      fonte: "RDO",
      status: "REGISTRADA",
      custoHora: 50,
      observacoes: "Alocacao 1"
    },
    {
      colaboradorId: colab2Id,
      equipe: "Equipe B",
      servicoNome: "Aplicacao CBUQ",
      horaInicio: "12:00:00",
      horaFim: "16:00:00",
      turno: "DIURNO",
      funcao: "Encarregada",
      centroCusto: "CC-002",
      tipoAlocacao: "TRABALHO",
      fonte: "RDO",
      status: "REGISTRADA",
      custoHora: 70,
      observacoes: "Alocacao 2"
    }
  ],
  attachments: [
    {
      id: photo1Id,
      rdoId,
      obraId,
      tipo: "FOTO",
      nome: "trecho-1.webp",
      nomeOriginal: "trecho-1.jpg",
      mimeType: "image/webp",
      tamanhoOriginalBytes: 900000,
      tamanhoComprimidoBytes: 240000,
      tamanhoBytes: 240000,
      syncStatus: "PENDING_SYNC",
      createdAt: "2026-07-01T10:00:00",
      updatedAt: "2026-07-01T10:00:00",
      metadata: { origem: "indexeddb", legenda: "Trecho 1" }
    },
    {
      id: photo2Id,
      rdoId,
      obraId,
      tipo: "FOTO",
      nome: "acabamento.webp",
      nomeOriginal: "acabamento.jpg",
      mimeType: "image/webp",
      tamanhoOriginalBytes: 1000000,
      tamanhoComprimidoBytes: 260000,
      tamanhoBytes: 260000,
      syncStatus: "SYNCED",
      createdAt: "2026-07-01T10:02:00",
      updatedAt: "2026-07-01T10:02:00",
      metadata: { origem: "camera", legenda: "Acabamento trecho 2" }
    }
  ],
  operationalEvents: [
    {
      id: event1Id,
      type: "FOTO_ADICIONADA",
      principalEntity: { tipo: "ATTACHMENT", id: photo1Id, nome: "trecho-1.webp" },
      relatedEntities: [{ tipo: "RDO", id: rdoId, nome: "RDO 123" }],
      obraId,
      rdoId,
      colaboradorId: colab1Id,
      occurredAt: "2026-07-01T10:01:00",
      syncedAt: null,
      origin: "OFFLINE",
      responsibleUserId: colab1Id,
      responsibleUserName: "Joao Silva",
      payload: { foto: "trecho-1.webp", local: "Trecho 1" },
      syncStatus: "PENDING_SYNC",
      schemaVersion: 1
    },
    {
      id: event2Id,
      type: "RDO_SINCRONIZADO",
      principalEntity: { tipo: "RDO", id: rdoId, nome: "RDO 123" },
      relatedEntities: [{ tipo: "OBRA", id: obraId, nome: "Obra Smoke StavIA" }],
      obraId,
      rdoId,
      colaboradorId: colab2Id,
      occurredAt: "2026-07-01T10:03:00",
      syncedAt: "2026-07-01T10:04:00",
      origin: "ONLINE",
      responsibleUserId: colab2Id,
      responsibleUserName: "Maria Souza",
      payload: { resultado: "sync concluido" },
      syncStatus: "SYNCED",
      schemaVersion: 1
    }
  ]
};

console.log(JSON.stringify({
  dispositivoId: deviceId,
  mutacoes: [{
    clientMutationId: "smoke-create-rdo-completo",
    entidadeTipo: "RDO",
    entidadeId: rdoId,
    operacao: "CRIAR_RDO",
    baseVersao: null,
    payload,
    criadaNoClienteEm: "2026-07-01T10:05:00"
  }]
}, null, 2));
NODE
api_json POST /sync/push "$CREATE_PUSH" "$CREATE_RESPONSE"
assert_json "$CREATE_RESPONSE" '.resultados[0].status == "APLICADA"' "sync push nao aplicou o RDO completo"
assert_json "$CREATE_RESPONSE" --arg rdo "$RDO_ID" '.resultados[0].entidadeId == $rdo' "resultado aplicado nao aponta para o RDO esperado"

log "validando caminho de conflito de versao no sync"
CONFLICT_PUSH="$TMP_DIR/conflict-push.json"
CONFLICT_RESPONSE="$TMP_DIR/conflict-response.json"
node - "$DEVICE_ID" "$RDO_ID" <<'NODE' >"$CONFLICT_PUSH"
const [deviceId, rdoId] = process.argv.slice(2);
console.log(JSON.stringify({
  dispositivoId: deviceId,
  mutacoes: [{
    clientMutationId: "smoke-conflict-rdo-versao",
    entidadeTipo: "RDO",
    entidadeId: rdoId,
    operacao: "ATUALIZAR_RDO_RASCUNHO",
    baseVersao: 0,
    payload: {},
    criadaNoClienteEm: "2026-07-01T10:06:00"
  }]
}, null, 2));
NODE
api_json POST /sync/push "$CONFLICT_PUSH" "$CONFLICT_RESPONSE"
assert_json "$CONFLICT_RESPONSE" '.resultados[0].status == "DESCARTADA"' "mutacao de versao antiga nao virou conflito"
assert_json "$CONFLICT_RESPONSE" '.resultados[0].conflito.versaoAtual > .resultados[0].conflito.baseVersao' "conflito nao trouxe versaoAtual maior que baseVersao"

log "validando caminho de erro de payload no sync"
ERROR_PUSH="$TMP_DIR/error-push.json"
ERROR_RESPONSE="$TMP_DIR/error-response.json"
node - "$DEVICE_ID" <<'NODE' >"$ERROR_PUSH"
const [deviceId] = process.argv.slice(2);
console.log(JSON.stringify({
  dispositivoId: deviceId,
  mutacoes: [{
    clientMutationId: "smoke-error-rdo-sem-obra",
    entidadeTipo: "RDO",
    entidadeId: "88888888-8888-8888-8888-888888888888",
    operacao: "CRIAR_RDO",
    baseVersao: null,
    payload: { id: "88888888-8888-8888-8888-888888888888", dataRdo: "2026-07-01" },
    criadaNoClienteEm: "2026-07-01T10:07:00"
  }]
}, null, 2));
NODE
api_json POST /sync/push "$ERROR_PUSH" "$ERROR_RESPONSE"
assert_json "$ERROR_RESPONSE" '.resultados[0].status == "ERRO"' "payload invalido nao virou ERRO"
assert_json "$ERROR_RESPONSE" '.resultados[0].erro | contains("obraId")' "erro de payload nao apontou obraId obrigatorio"

log "validando pull/ack de eventos de sync"
PULL_RESPONSE="$TMP_DIR/pull-response.json"
api_get "/sync/pull?dispositivoId=$DEVICE_ID&afterCommitSeq=0&limit=50" "$PULL_RESPONSE"
assert_json "$PULL_RESPONSE" --arg rdo "$RDO_ID" '.eventos[] | select(.entidadeId == $rdo and .tipoEvento == "RDO_CRIADO")' "pull nao retornou evento RDO_CRIADO"

ACK_REQUEST="$TMP_DIR/ack.json"
ACK_RESPONSE="$TMP_DIR/ack-response.json"
node - "$DEVICE_ID" "$(jq -r '.nextCommitSeq' "$PULL_RESPONSE")" <<'NODE' >"$ACK_REQUEST"
const [deviceId, commitSeq] = process.argv.slice(2);
console.log(JSON.stringify({
  dispositivoId: deviceId,
  ultimoEventoRecebidoCommitSeq: Number(commitSeq)
}, null, 2));
NODE
api_json POST /sync/ack "$ACK_REQUEST" "$ACK_RESPONSE"
assert_json "$ACK_RESPONSE" --arg id "$DEVICE_ID" '.dispositivoId == $id' "ACK nao confirmou o dispositivo"

log "validando snapshot da StavIA com todos os blocos do RDO"
SNAPSHOT="$TMP_DIR/stavia-snapshot.json"
api_get /stavia/snapshot "$SNAPSHOT"
assert_json "$SNAPSHOT" '.metadata.status == "COMPLETO"' "snapshot nao esta completo"
assert_json "$SNAPSHOT" --arg rdo "$RDO_ID" '
  .rdos[] |
  select(.id == $rdo) |
  (.maoObra | length == 2)
  and (.equipamentos | length == 2)
  and (.materiais | length == 2)
  and (.controlesGeometricos | length == 2)
  and (.servicosExecutados | length == 2)
  and (.alocacoesColaboradores | length == 2)
  and (.attachments | length == 2)
  and ((.controlesGeometricos[1].comprimentoM | tonumber) == 282)
  and ((.materiais[1].quantidadeAplicada | tonumber) == 33.5)
  and (.attachments[0].syncStatus == "PENDING_SYNC")
' "snapshot nao expôs todos os blocos/campos esperados do RDO"
assert_json "$SNAPSHOT" --arg event "$EVENT_2_ID" '.operationalEvents[] | select(.id == $event and .origin == "ONLINE")' "snapshot nao expôs evento ONLINE sincronizado"

ask_stavia() {
  local pergunta="$1"
  local expected="$2"
  local request="$TMP_DIR/stavia-question.json"
  local response="$TMP_DIR/stavia-answer-$(printf '%s' "$pergunta" | shasum | cut -d' ' -f1).json"
  local answer

  node - "$pergunta" "$OBRA_ID" "$RDO_ID" <<'NODE' >"$request"
const [pergunta, obraId, rdoId] = process.argv.slice(2);
console.log(JSON.stringify({
  pergunta,
  usuarioId: "smoke",
  obraId,
  rdoId
}, null, 2));
NODE
  api_json POST /stavia/consultas "$request" "$response"
  answer="$(jq -r '.answer.answer' "$response")"
  assert_contains "$answer" "$expected" "StavIA nao respondeu '$expected' para: $pergunta"
}

log "consultando StavIA nos blocos repetidos do RDO"
ask_stavia "Qual o comprimento do trecho 2?" "282"
ask_stavia "Qual a quantidade aplicada do material 2?" "33.5"
ask_stavia "Qual o cargo do colaborador 2?" "Encarregada"
ask_stavia "Qual o equipamento da maquina 2?" "Rolo compactador"
ask_stavia "Qual a quantidade executada da atividade 2?" "40"
ask_stavia "Qual a funcao da alocacao 2?" "Encarregada"
ask_stavia "Qual o status da foto 2?" "SYNCED"
ask_stavia "Qual a origem do evento 2?" "ONLINE"

log "resumo dos estados de mutacao no banco descartavel"
docker exec "$MYSQL_CONTAINER" mysql -uroot -p"$DB_ROOT_PASSWORD" -NBe \
  "SELECT status, COUNT(*) FROM $DB_NAME.sync_mutacao_cliente GROUP BY status ORDER BY status"

log "OK: CORS, conexao API, sync aplicado, conflito, erro de payload, pull/ack, snapshot e StavIA validados"
