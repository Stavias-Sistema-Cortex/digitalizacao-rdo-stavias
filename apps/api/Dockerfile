FROM maven:3.9.9-eclipse-temurin-21@sha256:5a8b906c4faa11d33f6c74758f67db8eac25441e14b0729f6d50ff78992be58a AS build

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine@sha256:3f08b13888f595cc49edabea7250ba69499ba25602b267da591720769400e08c

WORKDIR /app

RUN apk add --no-cache wget \
    && addgroup -S cortex \
    && adduser -S cortex -G cortex \
    && render_secret_group="$(getent group 1000 | cut -d: -f1)" \
    && if [ -z "$render_secret_group" ]; then \
         addgroup -S -g 1000 render-secrets; \
         render_secret_group=render-secrets; \
       fi \
    && addgroup cortex "$render_secret_group" \
    && mkdir -p /var/lib/cortex/objects \
    && chown -R cortex:cortex /var/lib/cortex

# A container without an explicit override must start in the fail-closed mode.
ENV SPRING_PROFILES_ACTIVE=production

# O jar aninhado é desmontado em jar fino mais `lib/`.
#
# Não é arrumação: é o que torna o arquivo de classes abaixo possível. Dentro do
# jar aninhado quem carrega as classes da aplicação é um carregador próprio do
# Spring Boot, e o compartilhamento da JVM só alcança o que veio do classpath de
# verdade — daí o aviso "Sharing is only supported for boot loader classes" e um
# arquivo que não cobriria nada do que interessa.
COPY --from=build /app/target/*.jar /tmp/boot.jar

RUN java -Djarmode=tools -jar /tmp/boot.jar extract --destination /app \
    && mv /app/*.jar /app/app.jar \
    && rm -f /tmp/boot.jar

# Arquivo de classes (AppCDS), gerado uma vez aqui e lido a cada partida.
#
# Subir o Spring é, em boa parte, achar, ler, verificar e ligar dezenas de
# milhares de classes — trabalho idêntico em toda partida, refeito do zero
# porque nada o guardava. O arquivo guarda esse resultado já mastigado, e a
# partida passa a mapeá-lo em memória em vez de reconstruí-lo.
#
# Medido neste repositório, do primeiro byte até o mesmo ponto da subida:
# 4794 ms sem o arquivo, 3443 ms com ele — 28% a menos, e numa máquina com CPU
# de sobra. Na fração de CPU da instância real a diferença tende a ser maior,
# porque justamente carregar classe é o que depende de CPU.
#
# O treino não alcança o fim da subida: sem banco ao lado, o contexto falha ao
# conectar. Isso é aceitável e não é silencioso — o arquivo é gravado na saída
# da JVM de qualquer forma, cobrindo tudo que foi carregado até ali, que é a
# maior parte. Daí `|| true` e o aviso: uma imagem sem o arquivo continua
# válida, apenas sobe mais devagar.
RUN SPRING_PROFILES_ACTIVE=production \
    CORTEX_POSTGRES_URL=jdbc:postgresql://127.0.0.1:5432/cds-training \
    timeout 300 java -XX:ArchiveClassesAtExit=/app/cortex.jsa \
      -Dspring.context.exit=onRefresh -jar /app/app.jar > /tmp/cds.log 2>&1 \
    || true; \
    if [ ! -s /app/cortex.jsa ]; then \
      echo "AVISO: arquivo AppCDS não gerado; a imagem sobe sem ele."; \
      tail -n 20 /tmp/cds.log; \
    fi; \
    rm -f /tmp/cds.log

USER cortex

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
  CMD wget -q -O - http://127.0.0.1:8080/api/readiness | grep -q '"status":"READY"' || exit 1

# Ajuste do arranque para a instância pequena onde este contêiner realmente
# roda. Ela dorme quando ninguém usa, então a partida a frio é o que uma pessoa
# em campo sente ao entrar de manhã — não um detalhe de operação.
#
# MaxRAMPercentage: o padrão da JVM reserva um quarto da memória do contêiner.
# Em 512 MB isso dá um heap de 128 MB, apertado justamente durante a subida,
# quando carregar classes e montar o contexto do Spring é o que mais aloca; o
# coletor entra em ação repetidas vezes antes de a aplicação existir.
#
# UseSerialGC: com uma fração de CPU não há paralelismo a explorar, e as
# threads dos coletores paralelos disputam a mesma fatia que precisa subir a
# aplicação. Declarado, e não deixado à heurística, para que o comportamento
# não mude quando o plano da instância mudar.
#
# SharedArchiveFile: lê o arquivo de classes montado na construção da imagem.
# Não é declarado com `-Xshare:on`, e isso é deliberado — se o arquivo faltar ou
# não corresponder ao classpath, a JVM apenas avisa e sobe sem ele. Um ganho de
# arranque não pode ser motivo para o contêiner deixar de subir.
#
# CORTEX_JAVA_OPTS permanece por último para que a operação possa sobrepor
# qualquer um destes valores sem reconstruir a imagem.
#
# CORTEX_MAIN_CLASS continua selecionando modos operacionais isolados (o Flyway,
# por exemplo). Esse ramo entra pelo classpath explícito, porque o jar agora está
# desmontado e não há mais um lançador para receber `-Dloader.main`. Ele não usa
# o arquivo de classes: é tarefa de uma execução só, onde arranque não importa,
# e o classpath difere do que foi gravado no treino.
ENTRYPOINT ["sh", "-c", "set -- -XX:MaxRAMPercentage=70.0 -XX:+UseSerialGC -Dserver.port=${PORT:-8080} ${CORTEX_JAVA_OPTS:-}; if [ -n \"${CORTEX_MAIN_CLASS:-}\" ]; then exec java \"$@\" -cp '/app/app.jar:/app/lib/*' \"${CORTEX_MAIN_CLASS}\"; fi; exec java \"$@\" -XX:SharedArchiveFile=/app/cortex.jsa -jar /app/app.jar"]
