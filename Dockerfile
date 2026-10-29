# ---- build ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline || true
COPY src ./src
RUN mvn -B -q package -DskipTests

# ---- run ----
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 1001 zones && mkdir -p /app/data && chown zones /app/data
COPY --from=build /src/target/polygon-zones-1.0.0.jar /app/app.jar
USER zones
ENV ZONES_JOURNAL_PATH=/app/data/zones.jsonl
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
