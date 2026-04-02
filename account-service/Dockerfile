# ── Stage 1: Build ──────────────────────────────────────────
FROM eclipse-temurin:17-jdk AS build
RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

# Install shared events library (must precede service build)
WORKDIR /build-common
COPY banking-events-common/pom.xml .
RUN mvn dependency:go-offline -B
COPY banking-events-common/src src
RUN mvn install -DskipTests -B

# Build account-service
WORKDIR /build
COPY account-service/pom.xml .
RUN mvn dependency:go-offline -B
COPY account-service/src src
RUN mvn clean package -DskipTests -B

# ── Stage 2: Run ────────────────────────────────────────────
FROM eclipse-temurin:17-jre
RUN groupadd -r banking && useradd -r -g banking banking
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
RUN chown -R banking:banking /app
RUN mkdir -p /var/cache/routing && chown banking:banking /var/cache/routing
USER banking
EXPOSE 8081
HEALTHCHECK --interval=15s --timeout=5s --retries=3 \
    CMD wget -qO- http://localhost:8081/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
