FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN apk add --no-cache wget \
    && addgroup -S cortex \
    && adduser -S cortex -G cortex \
    && mkdir -p /var/lib/cortex/objects \
    && chown -R cortex:cortex /app /var/lib/cortex

# A container without an explicit override must start in the fail-closed mode.
ENV SPRING_PROFILES_ACTIVE=production

COPY --from=build /app/target/*.jar app.jar

USER cortex

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
  CMD wget -q -O - http://127.0.0.1:8080/api/readiness | grep -q '"status":"READY"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java -Dserver.port=${PORT:-8080} -jar app.jar"]
