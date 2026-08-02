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

COPY --from=build /app/target/*.jar app.jar

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
# CORTEX_JAVA_OPTS permanece por último para que a operação possa sobrepor
# qualquer um destes valores sem reconstruir a imagem.
# PropertiesLauncher honors CORTEX_MAIN_CLASS for isolated operational modes
# (for example, Flyway) while preserving the normal web application default.
ENTRYPOINT ["sh", "-c", "exec java -XX:MaxRAMPercentage=70.0 -XX:+UseSerialGC -Dserver.port=${PORT:-8080} -Dloader.main=${CORTEX_MAIN_CLASS:-com.projeto.cortex.CortexApplication} ${CORTEX_JAVA_OPTS:-} -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher"]
