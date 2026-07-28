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

# PropertiesLauncher honors CORTEX_MAIN_CLASS for isolated operational modes
# (for example, Flyway) while preserving the normal web application default.
ENTRYPOINT ["sh", "-c", "exec java -Dserver.port=${PORT:-8080} -Dloader.main=${CORTEX_MAIN_CLASS:-com.projeto.cortex.CortexApplication} -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher"]
